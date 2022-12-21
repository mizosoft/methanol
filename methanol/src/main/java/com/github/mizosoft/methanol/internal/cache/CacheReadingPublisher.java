/*
 * Copyright (c) 2022 Moataz Abdelnasser
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.mizosoft.methanol.internal.cache;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.cache.Store.EntryReader;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.internal.flow.AbstractSubscription;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.atomic.AtomicBoolean;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Publisher for the response body as read from a cached entry's {@link Viewer}. */
public final class CacheReadingPublisher implements Publisher<List<ByteBuffer>> {
  private static final Logger logger = System.getLogger(CacheReadingPublisher.class.getName());

  private final Viewer viewer;
  private final Executor executor;
  private final Listener listener;
  private final AtomicBoolean subscribed = new AtomicBoolean();

  public CacheReadingPublisher(Viewer viewer, Executor executor) {
    this(viewer, executor, DisabledListener.INSTANCE);
  }

  public CacheReadingPublisher(Viewer viewer, Executor executor, Listener listener) {
    this.viewer = requireNonNull(viewer);
    this.executor = requireNonNull(executor);
    this.listener = requireNonNull(listener);
  }

  @Override
  public void subscribe(Subscriber<? super List<ByteBuffer>> subscriber) {
    requireNonNull(subscriber);
    if (subscribed.compareAndSet(false, true)) {
      new CacheReadingSubscription(subscriber, executor, viewer, listener).signal(true);
    } else {
      FlowSupport.rejectMulticast(subscriber);
    }
  }

  public interface Listener {
    void onReadSuccess();

    void onReadFailure(Throwable exception);

    default Listener guarded() {
      return new Listener() {
        @Override
        public void onReadSuccess() {
          try {
            Listener.this.onReadSuccess();
          } catch (Throwable e) {
            logger.log(Level.WARNING, "exception thrown by Listener::onReadSuccess", e);
          }
        }

        @Override
        public void onReadFailure(Throwable exception) {
          try {
            Listener.this.onReadFailure(exception);
          } catch (Throwable e) {
            logger.log(Level.WARNING, "exception thrown by Listener::onReadFailure", e);
          }
        }
      };
    }
  }

  private enum DisabledListener implements Listener {
    INSTANCE;

    @Override
    public void onReadSuccess() {}

    @Override
    public void onReadFailure(Throwable exception) {}
  }

  @SuppressWarnings("unused") // VarHandle indirection.
  static final class CacheReadingSubscription extends AbstractSubscription<List<ByteBuffer>> {
    /**
     * The number of buffers to fill readQueue with, without necessarily being requested by
     * downstream.
     */
    private static final int PREFETCH = 8;

    /**
     * The maximum number of buffers remaining in readQueue, after consumption by downstream, that
     * cause the publisher to trigger a readQueue refill. This allows reading and processing of
     * earlier items to occur simultaneously.
     */
    private static final int PREFETCH_THRESHOLD = 4;

    // Note: these 2 fields are mirrored in CacheReadingPublisherTck.
    private static final int BUFFER_SIZE = 8 * 1024;
    private static final int MAX_BATCH_SIZE = 4;

    private static final VarHandle STATE;

    static {
      try {
        STATE =
            MethodHandles.lookup()
                .findVarHandle(CacheReadingSubscription.class, "state", State.class);
      } catch (NoSuchFieldException | IllegalAccessException e) {
        throw new ExceptionInInitializerError(e);
      }
    }

    private final Viewer viewer;
    private final EntryReader reader;
    private final Listener listener;
    private final ConcurrentLinkedQueue<ByteBuffer> readQueue = new ConcurrentLinkedQueue<>();

    private volatile State state = State.INITIAL;

    enum State {
      INITIAL,
      IDLE,
      READING,
      DONE
    }

    private volatile boolean exhausted;

    CacheReadingSubscription(
        Subscriber<? super List<ByteBuffer>> downstream,
        Executor executor,
        Viewer viewer,
        Listener listener) {
      super(downstream, executor);
      this.viewer = viewer;
      this.reader = viewer.newReader();
      this.listener = listener.guarded(); // Ensure the listener doesn't throw.
    }

    @Override
    protected long emit(Subscriber<? super List<ByteBuffer>> downstream, long emit) {
      // Fire a read if this is the first run ever.
      if (state == State.INITIAL && STATE.compareAndSet(this, State.INITIAL, State.IDLE)) {
        tryScheduleRead(false);
      }

      long submitted = 0L;
      while (true) {
        List<ByteBuffer> batch;
        if (readQueue.isEmpty() && exhausted) {
          cancelOnComplete(downstream);
          return 0L;
        } else if (submitted >= emit
            || (batch = pollBatch()).isEmpty()) { // Exhausted demand or batches.
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
      state = State.DONE;
      try {
        viewer.close();
      } catch (Throwable t) {
        logger.log(Level.WARNING, "exception thrown by Viewer::close", t);
      }
    }

    private List<ByteBuffer> pollBatch() {
      List<ByteBuffer> batch = null;
      do {
        var buffer = readQueue.poll();
        if (buffer == null) {
          break;
        }

        if (batch == null) {
          batch = new ArrayList<>();
        }
        batch.add(buffer);
      } while (batch.size() < MAX_BATCH_SIZE);

      // Refill readQueue if enough buffers are consumed.
      if (readQueue.size() <= PREFETCH_THRESHOLD) {
        tryScheduleRead(false);
      }
      return batch != null ? List.copyOf(batch) : List.of();
    }

    /**
     * @param maintainReadingState whether the read is to be scheduled directly after a previous
     *     read is completed, allowing to leave the READING state as is
     */
    private boolean tryScheduleRead(boolean maintainReadingState) {
      if (readQueue.size() < PREFETCH
          && ((maintainReadingState && state == State.READING)
              || STATE.compareAndSet(this, State.IDLE, State.READING))) {
        var buffer = ByteBuffer.allocate(BUFFER_SIZE);
        try {
          reader
              .read(buffer)
              .whenComplete((read, exception) -> onReadCompletion(buffer, read, exception));
        } catch (Throwable t) {
          state = State.DONE;
          listener.onReadFailure(t);
          signalError(t);
          return false;
        }
        return true;
      }
      return false;
    }

    private void onReadCompletion(
        ByteBuffer buffer, @Nullable Integer read, @Nullable Throwable exception) {
      assert read != null || exception != null;

      // The subscription could've been cancelled while this read was in progress.
      if (state == State.DONE) {
        return;
      }

      if (exception != null) {
        state = State.DONE;
        listener.onReadFailure(exception);
        signalError(exception);
      } else if (read < 0) {
        state = State.DONE;
        exhausted = true;
        listener.onReadSuccess();
        signal(true); // Force completion signal.
      } else {
        if (read > 0) {
          readQueue.offer(buffer.flip().asReadOnlyBuffer());
        }
        if (!tryScheduleRead(true) && STATE.compareAndSet(this, State.READING, State.IDLE)) {
          // There might've been missed signals just before CASing to IDLE.
          tryScheduleRead(false);
        }
        signal(false);
      }
    }
  }
}
