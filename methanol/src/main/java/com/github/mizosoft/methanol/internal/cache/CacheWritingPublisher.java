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

import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.cache.Store.EntryWriter;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.flow.Upstream;
import com.github.mizosoft.methanol.internal.function.Unchecked;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A {@code Publisher} that writes the body stream into cache while simultaneously forwarding it to
 * downstream. The publisher prefers writing the whole stream if downstream cancels the subscription
 * midway transmission. Forwarding downstream items is advanced independently of writing them.
 * Consequently, writing may lag behind downstream consumption, and may proceed after downstream has
 * been completed. This affords the downstream not having to unnecessarily wait for the entire body
 * to be cached. If an error occurs while writing, the edit is silently discarded.
 */
public final class CacheWritingPublisher implements Publisher<List<ByteBuffer>> {
  private static final Logger logger = System.getLogger(CacheWritingPublisher.class.getName());

  private static final boolean DEFAULT_PROPAGATE_CANCELLATION =
      Boolean.getBoolean(
          "com.github.mizosoft.methanol.internal.cache.CacheWritingPublisher.propagateCancellation");

  private final Publisher<List<ByteBuffer>> upstream;
  private final Editor editor;
  private final ByteBuffer metadata;
  private final Executor executor;
  private final Listener listener;

  /**
   * Whether to propagate cancellation by downstream to upstream, or prefer draining the response
   * body into cache instead.
   */
  private final boolean propagateCancellation;

  private final AtomicBoolean subscribed = new AtomicBoolean();

  public CacheWritingPublisher(
      Publisher<List<ByteBuffer>> upstream, Editor editor, ByteBuffer metadata, Executor executor) {
    this(
        upstream,
        editor,
        metadata,
        executor,
        DisabledListener.INSTANCE,
        DEFAULT_PROPAGATE_CANCELLATION);
  }

  public CacheWritingPublisher(
      Publisher<List<ByteBuffer>> upstream,
      Editor editor,
      ByteBuffer metadata,
      Executor executor,
      Listener listener) {
    this(upstream, editor, metadata, executor, listener, DEFAULT_PROPAGATE_CANCELLATION);
  }

  public CacheWritingPublisher(
      Publisher<List<ByteBuffer>> upstream,
      Editor editor,
      ByteBuffer metadata,
      Executor executor,
      Listener listener,
      boolean propagateCancellation) {
    this.upstream = requireNonNull(upstream);
    this.editor = requireNonNull(editor);
    this.metadata = requireNonNull(metadata);
    this.executor = requireNonNull(executor);
    this.listener = requireNonNull(listener);
    this.propagateCancellation = propagateCancellation;
  }

  @Override
  public void subscribe(Subscriber<? super List<ByteBuffer>> subscriber) {
    requireNonNull(subscriber);
    if (subscribed.compareAndSet(false, true)) {
      upstream.subscribe(
          new CacheWritingSubscriber(
              subscriber, editor, metadata, executor, listener, propagateCancellation));
    } else {
      FlowSupport.rejectMulticast(subscriber);
    }
  }

  public interface Listener {
    void onWriteSuccess();

    void onWriteFailure(Throwable exception);

    default Listener guarded() {
      return new Listener() {
        @Override
        public void onWriteSuccess() {
          try {
            Listener.this.onWriteSuccess();
          } catch (Throwable e) {
            logger.log(Level.WARNING, "exception thrown by Listener::onWriteSuccess", e);
          }
        }

        @Override
        public void onWriteFailure(Throwable exception) {
          try {
            Listener.this.onWriteFailure(exception);
          } catch (Throwable e) {
            logger.log(Level.WARNING, "exception thrown by Listener::onWriteFailure", e);
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
    public void onWriteSuccess() {}

    @Override
    public void onWriteFailure(Throwable exception) {}
  }

  private static final class CacheWritingSubscriber implements Subscriber<List<ByteBuffer>> {
    private final CacheWritingSubscription downstreamSubscription;

    CacheWritingSubscriber(
        Subscriber<? super List<ByteBuffer>> downstream,
        Editor editor,
        ByteBuffer metadata,
        Executor executor,
        Listener listener,
        boolean propagateCancellation) {
      downstreamSubscription =
          new CacheWritingSubscription(
              downstream, editor, metadata, executor, listener, propagateCancellation);
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      downstreamSubscription.onSubscribe(requireNonNull(subscription));
    }

    @Override
    public void onNext(List<ByteBuffer> item) {
      downstreamSubscription.onNext(requireNonNull(item));
    }

    @Override
    public void onError(Throwable throwable) {
      downstreamSubscription.onError(requireNonNull(throwable));
    }

    @Override
    public void onComplete() {
      downstreamSubscription.onComplete();
    }
  }

  static final class CacheWritingSubscription implements Subscription {
    private static final VarHandle DOWNSTREAM;
    private static final VarHandle STATE;

    static {
      try {
        var lookup = MethodHandles.lookup();
        DOWNSTREAM =
            lookup.findVarHandle(CacheWritingSubscription.class, "downstream", Subscriber.class);
        STATE = lookup.findVarHandle(CacheWritingSubscription.class, "state", WritingState.class);
      } catch (NoSuchFieldException | IllegalAccessException e) {
        throw new ExceptionInInitializerError(e);
      }
    }

    @SuppressWarnings("FieldMayBeFinal") // VarHandle indirection.
    private volatile @Nullable Subscriber<? super List<ByteBuffer>> downstream;

    private final Editor editor;
    private final ByteBuffer metadata;
    private final EntryWriter writer;
    private final Executor executor;
    private final Listener listener;
    private final boolean propagateCancellation;
    private final Upstream upstream = new Upstream();
    private final ConcurrentLinkedQueue<ByteBuffer> writeQueue = new ConcurrentLinkedQueue<>();

    @SuppressWarnings("FieldMayBeFinal") // VarHandle indirection.
    private volatile WritingState state = WritingState.IDLE;

    private enum WritingState {
      IDLE,
      WRITING,
      DONE
    }

    @SuppressWarnings("unused") // VarHandle indirection.
    private volatile long position;

    /**
     * Set to true when onComplete() is called, after then the edit is to be committed as soon as
     * writeQueue becomes empty.
     */
    private volatile boolean receivedBodyCompletion;

    CacheWritingSubscription(
        @NonNull Subscriber<? super List<ByteBuffer>> downstream,
        Editor editor,
        ByteBuffer metadata,
        Executor executor,
        Listener listener,
        boolean propagateCancellation) {
      this.downstream = downstream;
      this.editor = editor;
      this.metadata = metadata;
      this.executor = executor;
      this.writer = editor.writer();
      this.listener = listener.guarded(); // Ensure the listener doesn't throw.
      this.propagateCancellation = propagateCancellation;
    }

    @Override
    public void request(long n) {
      // Only forward the request if downstream is not disposed.
      if (downstream != null) {
        assert upstream.isSet();
        upstream.request(n);
      }
    }

    @Override
    public void cancel() {
      // Downstream isn't interested in the body anymore. However, we are! So we won't propagate
      // cancellation upwards (unless propagateCancellation is set or we're not writing). This
      // will be done in background as downstream is probably done by now.
      if (state == WritingState.DONE || propagateCancellation) {
        upstream.cancel();
        discardEdit();
      } else {
        // TODO to avoid getting bombarded with buffers, consider requesting a bunch at a time.
        upstream.request(Long.MAX_VALUE);
      }
      getAndClearDownstream();
    }

    void onSubscribe(Subscription subscription) {
      if (upstream.setOrCancel(subscription)) {
        // Downstream can't be null now since it couldn't have been disposed.
        castNonNull(downstream).onSubscribe(this);
      }
    }

    void onNext(List<ByteBuffer> buffers) {
      if (state != WritingState.DONE) {
        // Create independent buffers for writing.
        writeQueue.addAll(
            buffers.stream().map(ByteBuffer::duplicate).collect(Collectors.toUnmodifiableList()));
        tryScheduleWrite(false);
      }

      var subscriber = downstream;
      if (subscriber != null) {
        subscriber.onNext(buffers);
      }
    }

    void onError(Throwable exception) {
      upstream.clear();
      writeQueue.clear();
      discardEdit();
      listener.onWriteFailure(exception);

      var subscriber = getAndClearDownstream();
      if (subscriber != null) {
        subscriber.onError(exception);
      } else {
        FlowSupport.onDroppedException(exception);
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
    @SuppressWarnings("FutureReturnValueIgnored")
    private boolean tryScheduleWrite(boolean maintainWritingState) {
      var buffer = writeQueue.peek();
      if (buffer != null
          && ((maintainWritingState && state == WritingState.WRITING)
              || STATE.compareAndSet(this, WritingState.IDLE, WritingState.WRITING))) {
        writeQueue.poll(); // Consume
        try {
          Unchecked.supplyAsync(() -> writer.write(buffer), executor)
              .whenComplete((__, ex) -> onWriteCompletion(ex));
        } catch (Throwable t) {
          discardEdit();
          listener.onWriteFailure(t);
          return false;
        }
        return true;
      } else if (buffer == null
          && (maintainWritingState
              || state == WritingState.IDLE) // No write is currently scheduled?
          && receivedBodyCompletion) {
        commitEdit();
        return true;
      }
      return false;
    }

    private void onWriteCompletion(@Nullable Throwable exception) {
      if (exception != null) {
        // Cancel upstream if downstream was disposed and we were only writing the body.
        if (downstream == null) {
          upstream.cancel();
        }
        discardEdit();
        listener.onWriteFailure(exception);
      } else if (!tryScheduleWrite(true)
          && STATE.compareAndSet(this, WritingState.WRITING, WritingState.IDLE)) {
        // There might be signals missed just before CASing to IDLE.
        tryScheduleWrite(false);
      }
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void commitEdit() {
      if (STATE.getAndSet(this, WritingState.DONE) != WritingState.DONE) {
        try {
          Unchecked.runAsync(
                  () -> {
                    try (editor) {
                      editor.commit(metadata);
                    }
                  },
                  executor)
              .whenComplete(
                  (__, ex) -> {
                    if (ex != null) {
                      listener.onWriteFailure(ex);
                    } else {
                      listener.onWriteSuccess();
                    }
                  });
        } catch (Throwable t) {
          // Make sure the editor is closed.
          try {
            editor.close();
          } catch (Throwable x) {
            t.addSuppressed(x);
          }

          logger.log(Level.ERROR, "exception while committing edit", t);
        }
      }
    }

    private void discardEdit() {
      if (STATE.getAndSet(this, WritingState.DONE) != WritingState.DONE) {
        writeQueue.clear();
        try {
          editor.close();
        } catch (Throwable t) {
          logger.log(Level.WARNING, "exception when closing the editor", t);
        }
      }
    }
  }
}
