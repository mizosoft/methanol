/*
 * Copyright (c) 2021 Moataz Abdelnasser
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

import static com.github.mizosoft.methanol.internal.cache.CacheReadingPublisher.CacheReadingSubscription.ReadingState.DISPOSED;
import static com.github.mizosoft.methanol.internal.cache.CacheReadingPublisher.CacheReadingSubscription.ReadingState.IDLE;
import static com.github.mizosoft.methanol.internal.cache.CacheReadingPublisher.CacheReadingSubscription.ReadingState.INITIAL;
import static com.github.mizosoft.methanol.internal.cache.CacheReadingPublisher.CacheReadingSubscription.ReadingState.READING;
import static java.util.Objects.requireNonNull;

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

/** Publisher for the response body read from a cached entry's {@code Viewer}. */
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
      FlowSupport.refuse(subscriber, FlowSupport.multipleSubscribersToUnicast());
    }
  }

  public interface Listener {
    void onReadSuccess();

    void onReadFailure(Throwable error);

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
        public void onReadFailure(Throwable error) {
          try {
            Listener.this.onReadFailure(error);
          } catch (Throwable e) {
            logger.log(Level.WARNING, "exception thrown by Listener::onReadFailure", e);
          }
        }
      };
    }

    static Listener disabled() {
      return DisabledListener.INSTANCE;
    }
  }

  private enum DisabledListener implements Listener {
    INSTANCE;

    @Override
    public void onReadSuccess() {}

    @Override
    public void onReadFailure(Throwable unused) {}
  }

  @SuppressWarnings("unused") // VarHandle indirection
  static final class CacheReadingSubscription extends AbstractSubscription<List<ByteBuffer>> {
    /**
     * The number of buffers to fill readQueue with, without necessarily being requested by
     * downstream.
     */
    private static final int PREFETCH = 8;
    /**
     * The maximum number of buffers remaining in readQueue, after consumption by downstream, that
     * cause the publisher to trigger a readQueue refill.
     */
    private static final int PREFETCH_THRESHOLD = 4;

    // Note: these 2 fields are mirrored in CacheReadingPublisherTck
    private static final int BUFFER_SIZE = 8 * 1024;
    private static final int MAX_BATCH_SIZE = 4;

    private static final VarHandle STATE;
    private static final VarHandle POSITION;

    static {
      var lookup = MethodHandles.lookup();
      try {
        STATE = lookup.findVarHandle(CacheReadingSubscription.class, "state", ReadingState.class);
        POSITION = lookup.findVarHandle(CacheReadingSubscription.class, "position", long.class);
      } catch (NoSuchFieldException | IllegalAccessException e) {
        throw new ExceptionInInitializerError(e);
      }
    }

    private final Viewer viewer;
    private final Listener listener;
    private final ConcurrentLinkedQueue<ByteBuffer> readQueue = new ConcurrentLinkedQueue<>();

    private volatile ReadingState state = INITIAL;
    private volatile long position;
    private volatile boolean endOfFile;

    enum ReadingState {
      INITIAL,
      IDLE,
      READING,
      DISPOSED
    }

    CacheReadingSubscription(
        Subscriber<? super List<ByteBuffer>> downstream,
        Executor executor,
        Viewer viewer,
        Listener listener) {
      super(downstream, executor);
      this.viewer = viewer;
      this.listener = listener.guarded(); // Ensure the listener doesn't throw
    }

    @Override
    protected long emit(Subscriber<? super List<ByteBuffer>> downstream, long emit) {
      // Fill readQueue if this is the first run
      if (state == INITIAL && STATE.compareAndSet(this, INITIAL, IDLE)) {
        tryScheduleRead(false);
      }

      long submitted = 0L;
      while (true) {
        List<ByteBuffer> batch;
        if (readQueue.isEmpty() && endOfFile) {
          cancelOnComplete(downstream);
          return 0L;
        } else if (submitted >= emit
            || (batch = pollBatch()).isEmpty()) { // Exhausted demand or batches
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
      state = DISPOSED;
      viewer.close();
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

      // Refill readQueue if enough buffers are consumed
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
          && ((maintainReadingState && state == READING)
              || STATE.compareAndSet(this, IDLE, READING))) {
        scheduleRead();
        return true;
      }
      return false;
    }

    private void scheduleRead() {
      var buffer = ByteBuffer.allocate(BUFFER_SIZE);
      try {
        viewer
            .readAsync(position, buffer)
            .whenComplete((read, error) -> onReadCompletion(buffer, read, error));
      } catch (RuntimeException e) {
        state = DISPOSED;
        signalError(e);
      }
    }

    private void onReadCompletion(
        ByteBuffer buffer, @Nullable Integer read, @Nullable Throwable error) {
      assert read != null || error != null;

      // The subscription could've been cancelled while this read was in progress
      if (state == DISPOSED) {
        return;
      }

      if (error != null) {
        state = DISPOSED;
        listener.onReadFailure(error);
        signalError(error);
      } else if (read < 0) {
        endOfFile = true;
        state = DISPOSED;
        listener.onReadSuccess();
        signal(true); // Force completion signal
      } else {
        if (read > 0) {
          readQueue.offer(buffer.flip().asReadOnlyBuffer());
          POSITION.getAndAdd(this, read);
        }

        if (!tryScheduleRead(true) && STATE.compareAndSet(this, READING, IDLE)) {
          // There might've been missed signals just before CASing to IDLE
          tryScheduleRead(false);
        }

        signal(false);
      }
    }
  }
}
