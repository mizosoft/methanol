package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.internal.flow.AbstractSubscription;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Publisher for the response body read from a cached entry's {@code Viewer}. */
public final class CacheReadingPublisher implements Publisher<List<ByteBuffer>> {
  private final Viewer viewer;
  private final Executor executor;

  public CacheReadingPublisher(Viewer viewer, Executor executor) {
    this.viewer = requireNonNull(viewer);
    this.executor = requireNonNull(executor);
  }

  @Override
  public void subscribe(Subscriber<? super List<ByteBuffer>> subscriber) {
    requireNonNull(subscriber);
    new CacheReadingSubscription(subscriber, executor, viewer).signal(true);
  }

  @SuppressWarnings("unused") // VarHandle indirection
  private static final class CacheReadingSubscription
      extends AbstractSubscription<List<ByteBuffer>> {
    public static final int PREFETCH = FlowSupport.prefetch();
    public static final int PREFETCH_THRESHOLD = FlowSupport.prefetchThreshold();

    // Note: these 2 fields are mirrored in CacheReadingPublisherTck
    private static final int BUFFER_SIZE = 4 * 1024;
    private static final int MAX_BATCH_SIZE = 3;

    private static final int WINDOW_BITS = Integer.SIZE - 2; // There are 2 state bits
    private static final int WINDOW_MASK = (1 << WINDOW_BITS) - 1;
    private static final int PENDING_READ = 1 << WINDOW_BITS;
    private static final int DISPOSED = 2 << WINDOW_BITS;

    private static final VarHandle STATE;
    private static final VarHandle POSITION;

    static {
      var lookup = MethodHandles.lookup();
      try {
        STATE = lookup.findVarHandle(CacheReadingSubscription.class, "state", int.class);
        POSITION = lookup.findVarHandle(CacheReadingSubscription.class, "position", long.class);
      } catch (NoSuchFieldException | IllegalAccessException e) {
        throw new ExceptionInInitializerError(e);
      }

      // Sanity check to ensure max prefetch is safely representable
      if (PREFETCH > WINDOW_MASK) {
        throw new UnsupportedOperationException("too large prefetch count");
      }
    }

    private final Viewer viewer;
    private final ConcurrentLinkedQueue<ByteBuffer> available = new ConcurrentLinkedQueue<>();

    /**
     * The state field maintains the count of available buffers + 1 if a read is pending (referred
     * to as window), along with disposed and pending read states at its first and second MSBs
     * respectively.
     */
    private volatile int state;

    private volatile long position;
    private volatile boolean endOfFile;

    CacheReadingSubscription(
        Subscriber<? super List<ByteBuffer>> downstream, Executor executor, Viewer viewer) {
      super(downstream, executor);
      this.viewer = viewer;
    }

    @Override
    protected long emit(Subscriber<? super List<ByteBuffer>> downstream, long emit) {
      // Try firing a read if this might be the first run, which guarantees completion
      // regardless of demand in case of an empty data stream.
      if (state == 0) {
        tryScheduleReadOnDrain();
      }

      long submitted = 0L;
      while (true) {
        List<ByteBuffer> batch;
        if (available.isEmpty() && endOfFile) {
          cancelOnComplete(downstream);
          return 0L;
        } else if (submitted >= emit
            || (batch = nextBatch()).isEmpty()) { // Exhausted demand or batches
          return submitted;
        } else if (submitOnNext(downstream, batch)) {
          submitted++;
        } else {
          return 0L;
        }
      }
    }

    @Override
    protected void abort(boolean flowInterrupted) {
      STATE.getAndBitwiseOr(this, DISPOSED);
      viewer.close();
    }

    private List<ByteBuffer> nextBatch() {
      var batch = collectNextBatch();
      updateStateOnPoll(batch.size());
      return batch;
    }

    private List<ByteBuffer> collectNextBatch() {
      List<ByteBuffer> batch = null;
      ByteBuffer buffer;
      do {
        buffer = available.poll();
        if (buffer != null) {
          if (batch == null) {
            batch = new ArrayList<>();
          }
          batch.add(buffer);
        }
      } while (buffer != null && batch.size() < MAX_BATCH_SIZE);
      return batch != null ? Collections.unmodifiableList(batch) : List.of();
    }

    private void updateStateOnPoll(int polled) {
      // Decrease window while maintaining the pending read bit
      int window = -1;
      for (int currentState; ((currentState = state) & DISPOSED) == 0; ) {
        window = currentState & WINDOW_MASK;
        if (STATE.compareAndSet(
            this, currentState, (window - polled) | (currentState & PENDING_READ))) {
          break;
        }
      }

      if (window >= 0 && window <= PREFETCH_THRESHOLD) {
        tryScheduleReadOnDrain();
      }
    }

    /** Schedules a read if none is currently pending and the subscription is not disposed yet. */
    private void tryScheduleReadOnDrain() {
      for (int currentState, window;
          ((currentState = state) & (PENDING_READ | DISPOSED)) == 0
              && (window = currentState & WINDOW_MASK) < PREFETCH; ) {
        if (STATE.compareAndSet(this, currentState, (window + 1) | PENDING_READ)) {
          scheduleRead();
          break;
        }
      }
    }

    /**
     * Either schedules a read while retaining the pending read bit or unsets the bit if just enough
     * buffers are prefetched, all if not yet disposed.
     */
    private void tryScheduleReadOnReadCompletion() {
      for (int currentState; ((currentState = state) & DISPOSED) == 0; ) {
        assert (currentState & PENDING_READ) != 0;

        int window = currentState & WINDOW_MASK;
        int nextState =
            window < PREFETCH
                ? (window + 1) | PENDING_READ
                : currentState & ~PENDING_READ;
        if (STATE.compareAndSet(this, currentState, nextState)) {
          if ((nextState & PENDING_READ) != 0) {
            scheduleRead();
          }
          break;
        }
      }
    }

    private void scheduleRead() {
      var buffer = ByteBuffer.allocate(BUFFER_SIZE);
      viewer
          .readAsync(position, buffer)
          .whenComplete((result, error) -> onReadCompletion(buffer, result, error));
    }

    private void onReadCompletion(
        ByteBuffer buffer, @Nullable Integer result, @Nullable Throwable error) {
      // The subscription could've been cancelled while a read is pending
      if ((state & DISPOSED) != 0) {
        return;
      }

      if (error != null) {
        STATE.getAndBitwiseOr(this, DISPOSED);
        signalError(error);
        return;
      }

      int read = castNonNull(result);
      if (read < 0) { // EOF
        STATE.getAndBitwiseOr(this, DISPOSED);
        endOfFile = true;
        signal(true); // Force completion signal
      } else {
        available.offer(buffer.flip().asReadOnlyBuffer());
        POSITION.getAndAdd(this, read);
        tryScheduleReadOnReadCompletion();
        signal(false);
      }
    }
  }
}
