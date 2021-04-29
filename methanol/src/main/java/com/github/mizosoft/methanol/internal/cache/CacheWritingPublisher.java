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

import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static com.github.mizosoft.methanol.internal.cache.CacheWritingPublisher.CacheWritingSubscription.WritingState.DISPOSED;
import static com.github.mizosoft.methanol.internal.cache.CacheWritingPublisher.CacheWritingSubscription.WritingState.IDLE;
import static com.github.mizosoft.methanol.internal.cache.CacheWritingPublisher.CacheWritingSubscription.WritingState.WRITING;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.flow.Upstream;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A {@code Publisher} operator that writes the body stream into cache while it's being forwarded to
 * downstream. The publisher prefers writing the whole stream if downstream cancels the subscription
 * midway transmission. Writing and forwarding downstream items are advanced independently at
 * different rates. This affords the downstream not having to unnecessarily wait for the whole body
 * to be cached. If an error occurs while writing, the entry is silently discarded.
 */
public final class CacheWritingPublisher implements Publisher<List<ByteBuffer>> {
  private static final Logger LOGGER = Logger.getLogger(CacheWritingPublisher.class.getName());

  /** Whether to propagate cancellation by downstream to upstream. Intended for TCK tests. */
  private static final boolean PROPAGATE_CANCELLATION =
      Boolean.getBoolean(
          "com.github.mizosoft.methanol.internal.cache.CacheWritingPublisher.propagateCancellation");

  private final Publisher<List<ByteBuffer>> upstream;
  private final Editor editor;
  private final Listener listener;
  private final AtomicBoolean subscribed = new AtomicBoolean();

  public CacheWritingPublisher(Publisher<List<ByteBuffer>> upstream, Editor editor) {
    this(upstream, editor, DisabledListener.INSTANCE);
  }

  public CacheWritingPublisher(
      Publisher<List<ByteBuffer>> upstream, Editor editor, Listener listener) {
    this.upstream = upstream;
    this.editor = editor;
    this.listener = listener;
  }

  @Override
  public void subscribe(Subscriber<? super List<ByteBuffer>> subscriber) {
    requireNonNull(subscriber);
    if (subscribed.compareAndSet(false, true)) {
      upstream.subscribe(new CacheWritingSubscriber(subscriber, editor, listener));
    } else {
      FlowSupport.refuse(subscriber, FlowSupport.multipleSubscribersToUnicast());
    }
  }

  public interface Listener {
    void onWriteSuccess();

    void onWriteFailure();

    default Listener guarded() {
      return new Listener() {
        @Override
        public void onWriteSuccess() {
          try {
            Listener.this.onWriteSuccess();
          } catch (Throwable error) {
            LOGGER.log(Level.WARNING, "Listener::onWriteSuccess unexpectedly failed", error);
          }
        }

        @Override
        public void onWriteFailure() {
          try {
            Listener.this.onWriteFailure();
          } catch (Throwable error) {
            LOGGER.log(Level.WARNING, "Listener::onWriteFailure unexpectedly failed", error);
          }
        }
      };
    }
  }

  private enum DisabledListener implements Listener {
    INSTANCE;

    @Override
    public void onWriteSuccess() {}

    @Override
    public void onWriteFailure() {}
  }

  private static final class CacheWritingSubscriber implements Subscriber<List<ByteBuffer>> {
    private final CacheWritingSubscription downstreamSubscription;

    CacheWritingSubscriber(
        Subscriber<? super List<ByteBuffer>> downstream, Editor editor, Listener listener) {
      downstreamSubscription = new CacheWritingSubscription(downstream, editor, listener);
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      requireNonNull(subscription);
      downstreamSubscription.onSubscribe(subscription);
    }

    @Override
    public void onNext(List<ByteBuffer> item) {
      requireNonNull(item);
      downstreamSubscription.onNext(item);
    }

    @Override
    public void onError(Throwable throwable) {
      requireNonNull(throwable);
      downstreamSubscription.onError(throwable);
    }

    @Override
    public void onComplete() {
      downstreamSubscription.onComplete();
    }
  }

  static final class CacheWritingSubscription implements Subscription {
    private static final VarHandle DOWNSTREAM;
    private static final VarHandle STATE;
    private static final VarHandle POSITION;

    static {
      try {
        var lookup = MethodHandles.lookup();
        DOWNSTREAM =
            lookup.findVarHandle(CacheWritingSubscription.class, "downstream", Subscriber.class);
        STATE = lookup.findVarHandle(CacheWritingSubscription.class, "state", WritingState.class);
        POSITION = lookup.findVarHandle(CacheWritingSubscription.class, "position", long.class);
      } catch (NoSuchFieldException | IllegalAccessException e) {
        throw new ExceptionInInitializerError(e);
      }
    }

    @SuppressWarnings("FieldMayBeFinal") // No it may not IDEA!
    private volatile @Nullable Subscriber<? super List<ByteBuffer>> downstream;

    private final Editor editor;
    private final Listener listener;
    private final Upstream upstream = new Upstream();
    private final ConcurrentLinkedQueue<ByteBuffer> writeQueue = new ConcurrentLinkedQueue<>();

    @SuppressWarnings("FieldMayBeFinal") // No it may not IDEA!!
    private volatile WritingState state = IDLE;

    @SuppressWarnings("unused") // VarHandle indirection
    private volatile long position;

    /**
     * Set to true when onComplete() is called, after then the edit is to be committed as soon as
     * writeQueue becomes empty.
     */
    private volatile boolean receivedBodyCompletion;

    // Package-Private for static import
    enum WritingState {
      IDLE,
      WRITING,
      DISPOSED
    }

    CacheWritingSubscription(
        @NonNull Subscriber<? super List<ByteBuffer>> downstream,
        Editor editor,
        Listener listener) {
      this.downstream = downstream;
      this.editor = editor;
      this.listener = listener.guarded(); // Ensure the listener doesn't throw
    }

    @Override
    public void request(long n) {
      // Only forward the request if downstream is not disposed
      if (downstream != null) {
        assert upstream.isSet();
        upstream.request(n);
      }
    }

    @Override
    public void cancel() {
      // Downstream isn't interested in the body anymore. However, we are!
      // Since we're also writing the body to cache. This will be done in
      // background since downstream is probably completed by now.
      if (getAndClearDownstream() != null) {
        assert upstream.isSet();
        if (state == DISPOSED || PROPAGATE_CANCELLATION) {
          // Nothing is being written or propagating cancellation is allowed
          upstream.cancel();
        } else {
          upstream.request(Long.MAX_VALUE); // Drain the whole body
        }
      }
    }

    void onSubscribe(Subscription subscription) {
      if (upstream.setOrCancel(subscription)) {
        // Downstream can't be null now since it couldn't have been disposed
        castNonNull(downstream).onSubscribe(this);
      }
    }

    void onNext(List<ByteBuffer> buffers) {
      // Duplicate buffers since they'll be operated upon concurrently
      var duplicateBuffers =
          buffers.stream().map(ByteBuffer::duplicate).collect(Collectors.toUnmodifiableList());
      writeQueue.addAll(duplicateBuffers);
      tryScheduleWrite(false);

      var subscriber = downstream;
      if (subscriber != null) {
        subscriber.onNext(buffers);
      }
    }

    void onError(Throwable error) {
      upstream.clear();

      discardEdit(null); // Discard anything written as the body might not have been completed

      var subscriber = getAndClearDownstream();
      if (subscriber != null) {
        subscriber.onError(error);
      }
    }

    void onComplete() {
      upstream.clear();

      receivedBodyCompletion = true;
      tryScheduleWrite(false);

      var subscriber = getAndClearDownstream();
      if (subscriber != null) {
        subscriber.onComplete();
      }
    }

    @SuppressWarnings("unchecked")
    private Subscriber<? super List<ByteBuffer>> getAndClearDownstream() {
      return (Subscriber<? super List<ByteBuffer>>) DOWNSTREAM.getAndSet(this, null);
    }

    /**
     * @param maintainWritingState whether the write is to be scheduled directly after a previous
     *     write is completed, allowing to leave the WRITING state as is
     */
    private boolean tryScheduleWrite(boolean maintainWritingState) {
      var buffer = writeQueue.peek();
      if (buffer != null
          && ((maintainWritingState && state == WRITING)
              || STATE.compareAndSet(this, IDLE, WRITING))) {
        writeQueue.poll(); // Consume
        scheduleWrite(buffer);
        return true;
      } else if (buffer == null
          && (maintainWritingState || state == IDLE) // No write is currently scheduled?
          && receivedBodyCompletion) {
        commitEdit();
        return true;
      }
      return false;
    }

    private void scheduleWrite(ByteBuffer buffer) {
      try {
        editor
            .writeAsync((long) POSITION.getAndAdd(this, buffer.remaining()), buffer)
            .whenComplete((__, error) -> onWriteCompletion(error));
      } catch (RuntimeException t) {
        discardEdit(t);
      }
    }

    private void commitEdit() {
      if (STATE.getAndSet(this, DISPOSED) != DISPOSED) {
        boolean failedToCommitEdit = false;
        try (editor) {
          editor.commitOnClose();
        } catch (IOException ioe) {
          failedToCommitEdit = true;
          LOGGER.log(Level.WARNING, "failed to commit the edit", ioe);
        }

        if (failedToCommitEdit) {
          listener.onWriteFailure();
        } else {
          listener.onWriteSuccess();
        }
      }
    }

    private void discardEdit(@Nullable Throwable writeFailureCause) {
      if (STATE.getAndSet(this, DISPOSED) != DISPOSED) {
        if (writeFailureCause != null) {
          LOGGER.log(
              Level.WARNING,
              "aborting the cache operation as a problem occurred while writing",
              writeFailureCause);

          listener.onWriteFailure();
        }

        writeQueue.clear();
        try {
          editor.close();
        } catch (IOException ioe) {
          LOGGER.log(Level.WARNING, "failed to discard the edit", ioe);
        }
      }
    }

    private void onWriteCompletion(@Nullable Throwable error) {
      if (error != null) {
        discardEdit(error);
        // Cancel upstream if downstream was disposed and we're only caching the body
        if (downstream == null) {
          upstream.cancel();
        }
      } else if (!tryScheduleWrite(true) && STATE.compareAndSet(this, WRITING, IDLE)) {
        // There might be signals missed just before CASing to IDLE
        tryScheduleWrite(false);
      }
    }
  }
}
