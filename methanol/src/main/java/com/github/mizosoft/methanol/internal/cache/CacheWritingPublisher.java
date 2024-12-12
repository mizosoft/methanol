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

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.cache.Store.EntryWriter;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.flow.Upstream;
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
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A {@code Publisher} that writes the body stream into cache while simultaneously forwarding it to
 * downstream. Forwarding data downstream and writing it run at different paces. Consequently,
 * writing may lag behind downstream consumption, and may, by default, proceed in background after
 * downstream has been completed. If the {@code
 * com.github.mizosoft.methanol.internal.cache.CacheWritingPublisher.waitForCommit} is true,
 * downstream isn't completed unless all data is written and the edit is committed. If an error
 * occurs while writing, the edit is silently discarded.
 */
public final class CacheWritingPublisher implements Publisher<List<ByteBuffer>> {
  private static final Logger logger = System.getLogger(CacheWritingPublisher.class.getName());

  private static final int SOFT_MAX_BULK_WRITE_SIZE = 8;

  private final Publisher<List<ByteBuffer>> upstream;
  private final Editor editor;
  private final ByteBuffer metadata;
  private final Executor executor;
  private final Listener listener;

  /** Whether to wait till the cache entry is committed before completing downstream. */
  private final boolean waitForCommit;

  private final AtomicBoolean subscribed = new AtomicBoolean();

  public CacheWritingPublisher(
      Publisher<List<ByteBuffer>> upstream, Editor editor, ByteBuffer metadata, Executor executor) {
    this(upstream, editor, metadata, executor, DisabledListener.INSTANCE, false);
  }

  public CacheWritingPublisher(
      Publisher<List<ByteBuffer>> upstream,
      Editor editor,
      ByteBuffer metadata,
      Executor executor,
      Listener listener) {
    this(upstream, editor, metadata, executor, listener, false);
  }

  public CacheWritingPublisher(
      Publisher<List<ByteBuffer>> upstream,
      Editor editor,
      ByteBuffer metadata,
      Executor executor,
      Listener listener,
      boolean waitForCommit) {
    this.upstream = requireNonNull(upstream);
    this.editor = requireNonNull(editor);
    this.metadata = requireNonNull(metadata);
    this.executor = requireNonNull(executor);
    this.listener = requireNonNull(listener);
    this.waitForCommit = waitForCommit;
  }

  @Override
  public void subscribe(Subscriber<? super List<ByteBuffer>> subscriber) {
    requireNonNull(subscriber);
    if (subscribed.compareAndSet(false, true)) {
      upstream.subscribe(
          new CacheWritingSubscriber(
              subscriber, editor, metadata, executor, listener, waitForCommit));
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
            logger.log(Level.WARNING, "Exception thrown by Listener::onWriteSuccess", e);
          }
        }

        @Override
        public void onWriteFailure(Throwable exception) {
          try {
            Listener.this.onWriteFailure(exception);
          } catch (Throwable e) {
            logger.log(Level.WARNING, "Exception thrown by Listener::onWriteFailure", e);
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
        boolean waitForCommit) {
      downstreamSubscription =
          new CacheWritingSubscription(
              downstream, editor, metadata, executor, listener, waitForCommit);
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
    private final boolean waitForCommit;
    private final Upstream upstream = new Upstream();
    private final ConcurrentLinkedQueue<List<ByteBuffer>> writeQueue =
        new ConcurrentLinkedQueue<>();

    @SuppressWarnings("FieldMayBeFinal") // VarHandle indirection.
    private volatile WritingState state = WritingState.IDLE;

    private enum WritingState {
      IDLE,
      WRITING,
      COMMITTING,
      DONE
    }

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
        boolean waitForCommit) {
      this.downstream = downstream;
      this.editor = editor;
      this.metadata = metadata;
      this.executor = executor;
      this.writer = editor.writer();
      this.listener = listener.guarded(); // Ensure the listener doesn't throw.
      this.waitForCommit = waitForCommit;
    }

    @Override
    public void request(long n) {
      upstream.request(n);
    }

    @Override
    public void cancel() {
      getAndClearDownstream();
      upstream.cancel();

      // If we've received the entire body there's no need to discard the edit as it can continue in
      // background till committing.
      if (!receivedBodyCompletion) {
        discardEdit();
      }
    }

    void onSubscribe(Subscription subscription) {
      if (upstream.setOrCancel(subscription)) {
        var subscriber = downstream;
        if (subscriber != null) {
          subscriber.onSubscribe(this);
        } else {
          logger.log(
              Level.WARNING,
              "Bad reactive-streams implementation: downstream is disposed (completed, errored) before calling onSubscribe");
        }
      }
    }

    void onNext(List<ByteBuffer> buffers) {
      if (state != WritingState.DONE) {
        // Create independent buffers for writing to cache.
        writeQueue.add(
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
      if (!waitForCommit || state == WritingState.DONE) {
        completeDownstream();
      }
    }

    private void completeDownstream() {
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
    @SuppressWarnings({"FutureReturnValueIgnored", "StatementWithEmptyBody"})
    private boolean tryScheduleWrite(boolean maintainWritingState) {
      List<ByteBuffer> buffers = null;
      boolean queueWasEmpty;
      while (true) {
        if ((buffers != null || (buffers = writeQueue.peek()) != null)
            && ((maintainWritingState && state == WritingState.WRITING)
                || STATE.compareAndSet(this, WritingState.IDLE, WritingState.WRITING))) {
          writeQueue.poll(); // Consume.

          // Take this chance to write as much as we can up to a soft limit. The limit might be
          // exceeded, although expectedly slightly.
          List<ByteBuffer> moreBuffers;
          boolean polledMoreBuffers = false;
          while (buffers.size() < SOFT_MAX_BULK_WRITE_SIZE
              && (moreBuffers = writeQueue.poll()) != null) {
            if (!polledMoreBuffers) {
              polledMoreBuffers = true;
              buffers = new ArrayList<>(buffers);
            }
            buffers.addAll(moreBuffers);
          }
          if (polledMoreBuffers) {
            buffers = Collections.unmodifiableList(buffers);
          }

          try {
            writer.write(buffers, executor).whenComplete((__, ex) -> onWriteCompletion(ex));
            return true;
          } catch (Throwable t) { // write might throw.
            discardEdit();
            listener.onWriteFailure(t);
            completeDownstreamOnDiscardedEdit();
            return false;
          }
        } else if ((queueWasEmpty = (buffers == null))
            && receivedBodyCompletion
            && (buffers = writeQueue.peek()) == null // Might've missed items before completion.
            && STATE.compareAndSet(
                this,
                maintainWritingState ? WritingState.WRITING : WritingState.IDLE,
                WritingState.COMMITTING)) {
          try {
            editor
                .commit(metadata, executor)
                .whenComplete(
                    (__, ex) -> {
                      state = WritingState.DONE;
                      closeEditor();
                      if (ex != null) {
                        listener.onWriteFailure(ex);
                      } else {
                        listener.onWriteSuccess();
                      }
                      completeDownstreamOnCommittedEdit();
                    });
            return true;
          } catch (Throwable t) { // commit might throw.
            discardEdit();
            listener.onWriteFailure(t);
            completeDownstreamOnDiscardedEdit();
            return false;
          }
        } else if (queueWasEmpty && buffers != null) {
          // Picked up new buffers after perceiving completion, retry.
        } else {
          return false;
        }
      }
    }

    private void onWriteCompletion(@Nullable Throwable exception) {
      if (exception != null) {
        discardEdit();
        listener.onWriteFailure(exception);
        completeDownstreamOnDiscardedEdit();
      } else if (!tryScheduleWrite(true)
          && STATE.compareAndSet(this, WritingState.WRITING, WritingState.IDLE)) {
        // There might be signals missed just before CASing to IDLE.
        tryScheduleWrite(false);
      }
    }

    private void discardEdit() {
      while (true) {
        var currentState = state;
        if (currentState == WritingState.COMMITTING || currentState == WritingState.DONE) {
          return;
        } else if (STATE.compareAndSet(this, currentState, WritingState.DONE)) {
          writeQueue.clear();
          closeEditor();
          return;
        }
      }
    }

    private void closeEditor() {
      try {
        editor.close();
      } catch (Throwable t) {
        logger.log(Level.WARNING, "Exception thrown when closing the editor", t);
      }
    }

    private void completeDownstreamOnCommittedEdit() {
      if (waitForCommit) {
        completeDownstream();
      }
    }

    private void completeDownstreamOnDiscardedEdit() {
      if (waitForCommit && receivedBodyCompletion) {
        completeDownstream();
      }
    }
  }
}
