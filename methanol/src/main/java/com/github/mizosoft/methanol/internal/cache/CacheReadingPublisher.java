/*
 * Copyright (c) 2024 Moataz Abdelnasser
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

import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.Store.EntryReader;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.internal.flow.AbstractPollableSubscription;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Publisher for the response body as read from a cached entry's {@link Viewer}. */
public final class CacheReadingPublisher implements Publisher<List<ByteBuffer>> {
  private static final Logger logger = System.getLogger(CacheReadingPublisher.class.getName());

  private final Viewer viewer;
  private final Executor executor;
  private final Listener listener;
  private final int bufferSize;
  private final AtomicBoolean subscribed = new AtomicBoolean();

  public CacheReadingPublisher(Viewer viewer, Executor executor) {
    this(viewer, executor, DisabledListener.INSTANCE);
  }

  public CacheReadingPublisher(Viewer viewer, Executor executor, Listener listener) {
    this(viewer, executor, listener, Utils.BUFFER_SIZE);
  }

  public CacheReadingPublisher(
      Viewer viewer, Executor executor, Listener listener, int bufferSize) {
    this.viewer = requireNonNull(viewer);
    this.executor = requireNonNull(executor);
    this.listener = requireNonNull(listener);
    this.bufferSize = bufferSize;
    requireArgument(bufferSize > 0, "Expected a positive buffer size: %d", bufferSize);
  }

  @Override
  public void subscribe(Subscriber<? super List<ByteBuffer>> subscriber) {
    requireNonNull(subscriber);
    if (subscribed.compareAndSet(false, true)) {
      new CacheReadingSubscription(subscriber, executor, viewer, listener, bufferSize)
          .fireOrKeepAlive();
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
            logger.log(Level.WARNING, "Exception thrown by Listener::onReadSuccess", e);
          }
        }

        @Override
        public void onReadFailure(Throwable exception) {
          try {
            Listener.this.onReadFailure(exception);
          } catch (Throwable e) {
            logger.log(Level.WARNING, "Exception thrown by Listener::onReadFailure", e);
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
    public void onReadFailure(Throwable exception) {}
  }

  @SuppressWarnings("unused") // VarHandle indirection.
  static final class CacheReadingSubscription
      extends AbstractPollableSubscription<List<ByteBuffer>> {

    /**
     * The maximum number of buffers to allocate in bulk (gathering) reads. This has to be big
     * enough so that bulk reads are faster than single-buffer reads, taking into account that we
     * pass lists of buffers downstream, but small enough so that each read doesn't take too long,
     * and little allocation is wasted when the response size is small. The chosen number seems good
     * enough considering the default buffer size of 16kB (see {@link Utils#BUFFER_SIZE}).
     */
    private static final int MAX_BULK_READ_SIZE = 4;

    /**
     * The number of buffers to fill readQueue with, without necessarily being requested by
     * downstream.
     */
    private static final int PREFETCH = 2 * MAX_BULK_READ_SIZE;

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
    private final Executor executor;
    private final EntryReader reader;
    private final Listener listener;
    private final int bufferSize;
    private final ConcurrentLinkedQueue<List<ByteBuffer>> readQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger buffersPromised = new AtomicInteger();

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
        Listener listener,
        int bufferSize) {
      super(downstream, FlowSupport.SYNC_EXECUTOR);
      this.viewer = viewer;
      this.executor = executor;
      this.reader = viewer.newReader();
      this.listener = listener.guarded(); // Ensure the listener doesn't throw.
      this.bufferSize = bufferSize;
    }

    @Override
    protected @Nullable List<ByteBuffer> poll() {
      var batch = readQueue.poll();
      if (batch != null) {
        buffersPromised.getAndAdd(-batch.size());
        batch = sliceNonEmptyBuffers(batch);
      }
      if (!exhausted) {
        tryScheduleRead(false);
      }
      return batch;
    }

    @Override
    protected boolean isComplete() {
      return exhausted && readQueue.isEmpty();
    }

    @Override
    protected long emit(Subscriber<? super List<ByteBuffer>> downstream, long emit) {
      // Fire a read if this is the first run ever.
      if (state == State.INITIAL && STATE.compareAndSet(this, State.INITIAL, State.IDLE)) {
        tryScheduleRead(false);
      }
      return super.emit(downstream, emit);
    }

    @Override
    protected void abort(boolean flowInterrupted) {
      state = State.DONE;
      try {
        viewer.close();
      } catch (Throwable t) {
        logger.log(Level.WARNING, "Exception while closing viewer", t);
      }
    }

    /**
     * @param maintainReadingState whether the read is to be scheduled directly after a previous
     *     read is completed, allowing to leave the READING state as is
     */
    @SuppressWarnings("FutureReturnValueIgnored")
    private boolean tryScheduleRead(boolean maintainReadingState) {
      if (buffersPromised.get() < PREFETCH
          && ((maintainReadingState && state == State.READING)
              || STATE.compareAndSet(this, State.IDLE, State.READING))) {
        // Re-read buffersPromised twice as it can only decrease if we're here, and if so we want
        // the decreased quantity to be reflected.
        int buffersNeeded = Math.min(PREFETCH - buffersPromised.get(), MAX_BULK_READ_SIZE);
        var buffers =
            Stream.generate(() -> ByteBuffer.allocate(bufferSize))
                .limit(buffersNeeded)
                .collect(Collectors.toUnmodifiableList());
        buffersPromised.getAndAdd(buffersNeeded);
        try {
          reader
              .read(buffers, executor)
              .whenComplete((read, exception) -> onReadCompletion(buffers, read, exception));
          return true;
        } catch (Throwable t) {
          state = State.DONE;
          listener.onReadFailure(t);
          fireOrKeepAliveOnError(t);
          return false;
        }
      } else {
        return false;
      }
    }

    @SuppressWarnings("NullAway")
    private void onReadCompletion(
        List<ByteBuffer> buffers, @Nullable Long read, @Nullable Throwable exception) {
      assert read != null || exception != null;
      if (exception != null) {
        state = State.DONE;
        listener.onReadFailure(exception);
        fireOrKeepAliveOnError(exception);
      } else if (read < 0) {
        state = State.DONE;
        exhausted = true;
        listener.onReadSuccess();
        fireOrKeepAlive();
      } else {
        readQueue.add(
            buffers.stream()
                .map(buffer -> buffer.flip().asReadOnlyBuffer())
                .collect(Collectors.toUnmodifiableList()));
        if (!tryScheduleRead(true) && STATE.compareAndSet(this, State.READING, State.IDLE)) {
          // There might've been missed signals just before CASing to IDLE.
          tryScheduleRead(false);
        }
        fireOrKeepAliveOnNext();
      }
    }

    /**
     * Removes empty buffers from the end of the list, which can exist when reading at the end of
     * stream.
     */
    private List<ByteBuffer> sliceNonEmptyBuffers(List<ByteBuffer> buffers) {
      if (buffers.get(buffers.size() - 1).hasRemaining()) {
        return buffers;
      }

      var nonEmptyBuffers = new ArrayList<>(buffers);
      for (int i = buffers.size() - 1; i >= 0 && !buffers.get(i).hasRemaining(); i--) {
        nonEmptyBuffers.remove(i);
      }
      return Collections.unmodifiableList(nonEmptyBuffers);
    }
  }
}
