package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
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
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.NonNull;
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

  private static final int AVAILABLE = 0x1; // Downstream is available to receive signals
  private static final int RUN = 0x2; // Drain task is running
  private static final int KEEP_ALIVE = 0x4; // Drain task should keep running (avoid missed signal)
  private static final int DONE = 0x8; // Downstream completed

  private static final VarHandle SYNC;
  private static final VarHandle DOWNSTREAM_SUBSCRIPTION;

  static {
    try {
      var lookup = MethodHandles.lookup();
      SYNC = lookup.findVarHandle(CacheWritingBodySubscriber.class, "sync", int.class);
      DOWNSTREAM_SUBSCRIPTION =
          lookup.findVarHandle(
              CacheWritingBodySubscriber.class,
              "downstreamSubscription",
              CacheWritingSubscription.class);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** Whether to propagate cancellation by downstream to upstream. Intended for TCK tests. */
  private static final boolean PROPAGATE_CANCELLATION =
      Boolean.getBoolean(
          "com.github.mizosoft.methanol.internal.cache.CacheWritingBodySubscriber.propagateCancellation");

  /**
   * Metadata of the response being cached. Will be set when the body stream is completed and the
   * editor is to be closed.
   */
  private final ByteBuffer metadata;

  private final Editor editor;
  private final Upstream upstream = new Upstream();
  private final ConcurrentLinkedQueue<Consumer<CacheWritingSubscription>> signals =
      new ConcurrentLinkedQueue<>();

  /**
   * Control field used to synchronize calls to onNext & (onError | onComplete) so that they are
   * called serially strictly after onSubscribe.
   */
  private volatile int sync;

  /** Schedules calling downstream's onSubscribe after upstream subscription arrives. */
  private final CompletableFuture<Void> upstreamAvailable = new CompletableFuture<>();

  private volatile @MonotonicNonNull CacheWritingSubscription downstreamSubscription;

  public CacheWritingBodySubscriber(Editor editor, ByteBuffer metadata) {
    this.editor = editor;
    this.metadata = metadata;
  }

  @Override
  public CompletionStage<Publisher<List<ByteBuffer>>> getBody() {
    return CompletableFuture.completedFuture(this::subscribe);
  }

  private void subscribe(Subscriber<? super List<ByteBuffer>> subscriber) {
    requireNonNull(subscriber);
    var subscription = new CacheWritingSubscription(editor, metadata, upstream, subscriber);
    if (DOWNSTREAM_SUBSCRIPTION.compareAndSet(this, null, subscription)) {
      // Apply downstream's onSubscribe when upstream arrives so it can start requesting items
      upstreamAvailable.thenRun(
          () -> {
            try {
              subscription.onSubscribe();
            } catch (Throwable t) {
              upstream.cancel();
              subscription.onError(t);
              return;
            }
            SYNC.getAndBitwiseOr(this, AVAILABLE);
            tryDrain(subscription);
          });
    } else {
      FlowSupport.refuse(subscriber, FlowSupport.multipleSubscribersToUnicast());
    }
  }

  @Override
  public void onSubscribe(Subscription subscription) {
    requireNonNull(subscription);
    if (upstream.setOrCancel(subscription)) {
      upstreamAvailable.complete(null);
    }
  }

  @Override
  public void onNext(List<ByteBuffer> item) {
    requireNonNull(item);
    if (downstreamSubscription != null) {
      putSignal(subscription -> subscription.onNext(item));
    } else {
      // Downstream must subscribe first and start requesting elements before any arrives
      upstream.cancel();
      onError(new IllegalStateException("items are arriving before requesting anything"));
    }
  }

  @Override
  public void onError(Throwable throwable) {
    requireNonNull(throwable);
    upstream.clear();
    putTerminalSignal(subscription -> subscription.onError(throwable));
  }

  @Override
  public void onComplete() {
    upstream.clear();
    putTerminalSignal(CacheWritingSubscription::onComplete);
  }

  private void putSignal(Consumer<CacheWritingSubscription> signal) {
    signals.offer(signal);
    var subscription = downstreamSubscription;
    if (subscription != null) {
      tryDrain(subscription);
    }
  }

  private void putTerminalSignal(Consumer<CacheWritingSubscription> signal) {
    putSignal(signal.andThen(__ -> SYNC.getAndBitwiseOr(this, DONE)));
  }

  private void tryDrain(CacheWritingSubscription subscription) {
    for (int s; ((s = sync) & AVAILABLE) != 0 && (s & DONE) == 0; ) {
      int bit = (s & RUN) == 0 ? RUN : KEEP_ALIVE; // Run or keep-alive
      if (SYNC.compareAndSet(this, s, (s | bit))) {
        if (bit == RUN) {
          drainSignals(subscription);
        }
        break;
      }
    }
  }

  private void drainSignals(CacheWritingSubscription subscription) {
    for (int s; ((s = sync) & DONE) == 0; ) {
      var signal = signals.poll();
      if (signal != null) {
        signal.accept(subscription);
      } else {
        // Exit or consume keep-alive bit
        int bit = (s & KEEP_ALIVE) != 0 ? KEEP_ALIVE : RUN;
        if (SYNC.compareAndSet(this, s, s & ~bit) && bit == RUN) {
          break;
        }
      }
    }
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
    private final ByteBuffer metadata;
    private final Upstream upstream;
    private final ConcurrentLinkedQueue<ByteBuffer> writeQueue = new ConcurrentLinkedQueue<>();

    @SuppressWarnings("FieldMayBeFinal") // No it may not IDEA!
    private volatile @Nullable Subscriber<? super List<ByteBuffer>> downstream;

    private volatile long position;
    private volatile WritingState state = IDLE;

    // Package-Private for static import
    enum WritingState {
      IDLE,
      WRITING,
      DISPOSED
    }

    CacheWritingSubscription(
        Editor editor,
        ByteBuffer metadata,
        Upstream upstream,
        @NonNull Subscriber<? super List<ByteBuffer>> downstream) {
      this.editor = editor;
      this.metadata = metadata;
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
        if (state == DISPOSED || PROPAGATE_CANCELLATION) {
          upstream.cancel(); // Nothing is being written or propagating cancellation is allowed
        } else {
          upstream.request(Long.MAX_VALUE); // Drain the whole body
        }
      }
    }

    void onSubscribe() {
      // Downstream can't be null now since it couldn't have been disposed
      castNonNull(downstream).onSubscribe(this);
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
      discardEdit(null); // Discard anything written as the body might not have been completed

      var subscriber = getAndClearDownstream();
      if (subscriber != null) {
        subscriber.onError(error);
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
      if (buffer == COMPLETE) {
        commitEdit();
        return true;
      } else if (buffer != null
          && ((maintainWritingState && state == WRITING)
              || STATE.compareAndSet(this, IDLE, WRITING))) {
        scheduleWrite(buffer);
        return true;
      }
      return false;
    }

    private void scheduleWrite(ByteBuffer buffer) {
      try {
        editor
            .writeAsync((long) POSITION.getAndAdd(this, buffer.remaining()), buffer)
            .whenComplete((__, error) -> onWriteCompletion(error));
      } catch (Throwable t) {
        discardEdit(t);
      }
    }

    private void commitEdit() {
      state = DISPOSED;
      try (editor) {
        editor.metadata(metadata);
      }
    }

    private void discardEdit(@Nullable Throwable cause) {
      if (cause != null) {
        LOGGER.log(
            Level.WARNING,
            cause,
            () ->
                "caching response with key <"
                    + editor.key()
                    + "> will be discarded as a problem occurred while writing");
      }

      state = DISPOSED;
      writeQueue.clear();
      try (editor) {
        editor.discard();
      }
    }

    private void onWriteCompletion(@Nullable Throwable error) {
      if (error != null) {
        // Cancel upstream if downstream was disposed
        if (downstream == null) {
          upstream.cancel();
        }
        discardEdit(error);
      } else if (!tryScheduleWrite(true) && STATE.compareAndSet(this, WRITING, IDLE)) {
        // There might be signals missed just before CASing to IDLE
        tryScheduleWrite(false);
      }
    }
  }
}
