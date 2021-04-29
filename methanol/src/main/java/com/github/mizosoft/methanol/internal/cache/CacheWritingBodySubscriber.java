package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static com.github.mizosoft.methanol.internal.cache.CacheWritingBodySubscriber.CacheWritingSubscription.WritingState.DISPOSED;
import static com.github.mizosoft.methanol.internal.cache.CacheWritingBodySubscriber.CacheWritingSubscription.WritingState.IDLE;
import static com.github.mizosoft.methanol.internal.cache.CacheWritingBodySubscriber.CacheWritingSubscription.WritingState.WRITING;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.flow.Upstream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A {@code BodySubscriber} that writes the response body into cache while it's being forwarded to
 * downstream. The subscriber attempts to write the whole body even if downstream cancels the
 * subscription midway transmission. If an error is encountered while writing, the cache entry is
 * silently discarded.
 */
@SuppressWarnings("unused") // VarHandle indirection
public final class CacheWritingBodySubscriber
    implements BodySubscriber<Publisher<List<ByteBuffer>>> {
  private static final Logger LOGGER = Logger.getLogger(CacheWritingBodySubscriber.class.getName());

  private static final VarHandle DOWNSTREAM_SUBSCRIPTION;

  static {
    try {
      DOWNSTREAM_SUBSCRIPTION =
          MethodHandles.lookup()
              .findVarHandle(
                  CacheWritingBodySubscriber.class,
                  "downstreamSubscription",
                  CacheWritingSubscription.class);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final Editor editor;
  private final Upstream upstream = new Upstream();

  /**
   * Future used to schedule onComplete and onError as these can occur before downstream subscribes
   * to use (before downstreamSubscription is set).
   */
  private final CompletableFuture<CacheWritingSubscription> downstreamSubscriptionCompletion =
      new CompletableFuture<>();

  private volatile @MonotonicNonNull CacheWritingSubscription downstreamSubscription;

  public CacheWritingBodySubscriber(Editor editor) {
    this.editor = editor;
  }

  @Override
  public CompletionStage<Publisher<List<ByteBuffer>>> getBody() {
    return CompletableFuture.completedFuture(this::subscribe);
  }

  private void subscribe(Subscriber<? super List<ByteBuffer>> subscriber) {
    requireNonNull(subscriber);
    var subscription = new CacheWritingSubscription(editor, upstream, subscriber);
    boolean upstreamIsSet = upstream.isSet();
    if (DOWNSTREAM_SUBSCRIPTION.compareAndSet(this, null, subscription)) {
      if (upstreamIsSet) { // Upstream subscription arrived first
        subscription.onSubscribe();
      }
      downstreamSubscriptionCompletion.complete(subscription);
    } else {
      FlowSupport.refuse(subscriber, FlowSupport.multipleSubscribersToUnicast());
    }
  }

  @Override
  public void onSubscribe(Subscription upstreamSubscription) {
    var subscription = downstreamSubscription;
    if (upstream.setOrCancel(upstreamSubscription)) {
      if (subscription != null) { // Downstream subscribed first
        subscription.onSubscribe();
      }
    }
  }

  @Override
  public void onNext(List<ByteBuffer> item) {
    requireNonNull(item);
    try {
      var subscription = downstreamSubscription;
      // Downstream must subscribe first and start requesting elements before any arrives
      requireState(subscription != null, "items are arriving before requesting anything");
      castNonNull(subscription).onNext(item);
    } catch (IllegalStateException e) {
      upstream.cancel();
      onError(e);
    }
  }

  @Override
  public void onError(Throwable throwable) {
    requireNonNull(throwable);
    upstream.clear();
    downstreamSubscriptionCompletion.thenAccept(s -> s.onError(throwable));
  }

  @Override
  public void onComplete() {
    upstream.clear();
    downstreamSubscriptionCompletion.thenAccept(CacheWritingSubscription::onComplete);
  }

  // Package-Private for static import
  static final class CacheWritingSubscription implements Subscription {
    private static final ByteBuffer COMPLETE = ByteBuffer.allocate(0);

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

    private final Editor editor;
    private final Upstream upstream;
    private final ConcurrentLinkedQueue<ByteBuffer> writeQueue = new ConcurrentLinkedQueue<>();

    @SuppressWarnings("FieldMayBeFinal") // No it may not IDEA!
    private volatile @Nullable Subscriber<? super List<ByteBuffer>> downstream;

    private volatile long position;
    private volatile WritingState state = WritingState.IDLE;

    // Package-Private for static import
    enum WritingState {
      IDLE,
      WRITING,
      DISPOSED
    }

    CacheWritingSubscription(
        Editor editor,
        Upstream upstream,
        @SuppressWarnings("NullableProblems") Subscriber<? super List<ByteBuffer>> downstream) {
      this.editor = editor;
      this.upstream = upstream;
      this.downstream = downstream;
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
        if (state != DISPOSED) {
          upstream.request(Long.MAX_VALUE); // Drain the response body
        } else {
          // Nothing is being written so propagate cancellation
          upstream.cancel();
        }
      }
    }

    void onSubscribe() {
      // Downstream can't be null now since it couldn't have been disposed
      castNonNull(downstream).onSubscribe(this);
    }

    void onNext(List<ByteBuffer> buffers) {
      // Duplicate buffers since they're operated upon concurrently
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
      // Discard anything written as the body might not have been completed
      state = DISPOSED;
      writeQueue.clear();
      try (editor) {
        editor.discard();
      } finally {
        var subscriber = getAndClearDownstream();
        if (subscriber != null) {
          subscriber.onError(error);
        }
      }
    }

    void onComplete() {
      writeQueue.offer(COMPLETE);
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

    @SuppressWarnings("ReferenceEquality") // ByteBuffer sentinel
    private boolean tryScheduleWrite(boolean maintainWritingState) {
      var buffer = writeQueue.poll();
      if (buffer != null
          && ((maintainWritingState && state == WRITING)
              || STATE.compareAndSet(this, IDLE, WRITING))) {
        if (buffer == COMPLETE) {
          editor.close(); // TODO close quietly
        } else {
          scheduleWrite(buffer);
        }
        return true;
      }
      return false;
    }

    private void scheduleWrite(ByteBuffer buffer) {
      editor
          .writeAsync((long) POSITION.getAndAdd(this, buffer.remaining()), buffer)
          .whenComplete((__, error) -> onWriteCompletion(error));
    }

    private void onWriteCompletion(@Nullable Throwable error) {
      if (error != null) {
        LOGGER.log(
            Level.WARNING,
            error,
            () ->
                "caching response with key <"
                    + editor.key()
                    + "> will be discarded as a problem occurred while writing");

        // Cancel upstream if downstream was disposed
        if (downstream == null) {
          upstream.cancel();
        }

        state = DISPOSED;
        writeQueue.clear();
        try (editor) {
          editor.discard();
        }
      } else if (!tryScheduleWrite(true) && STATE.compareAndSet(this, WRITING, IDLE)) {
        // There might be signals missed just before CASing to IDLE
        tryScheduleWrite(false);
      }
    }
  }
}
