/*
 * Copyright (c) 2023 Moataz Abdelnasser
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

package com.github.mizosoft.methanol;

import static com.github.mizosoft.methanol.internal.Utils.requireNonNegativeDuration;
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

import com.github.mizosoft.methanol.MultipartBodyPublisher.Part;
import com.github.mizosoft.methanol.MultipartBodyPublisher.PartSequenceListener;
import com.github.mizosoft.methanol.internal.extensions.ForwardingBodyPublisher;
import com.github.mizosoft.methanol.internal.flow.AbstractSubscription;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.flow.ForwardingSubscriber;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/** A progress tracker for upload and download operations. */
public final class ProgressTracker {
  private final Options options;
  private final boolean userVisibleExecutor;

  private ProgressTracker(Builder builder) {
    var configuredExecutor = builder.executor;
    Executor executor;
    if (configuredExecutor != null) {
      executor = configuredExecutor;
      userVisibleExecutor = true;
    } else {
      executor = FlowSupport.SYNC_EXECUTOR;
      userVisibleExecutor = false;
    }

    options =
        new Options(
            builder.bytesTransferredThreshold,
            builder.timePassedThreshold,
            executor,
            builder.enclosedProgress,
            builder.clock);
  }

  /** Returns the minimum number of bytes to be transferred for a progress event to be signalled. */
  public long bytesTransferredThreshold() {
    return options.bytesTransferredThreshold;
  }

  /** Returns the minimum time to pass for a progress event to be signalled. */
  public Optional<Duration> timePassedThreshold() {
    return Optional.ofNullable(options.timePassedThreshold);
  }

  /**
   * Returns whether the sequence of progress events is enclosed between {@code 0%} and {@code 100%}
   * progress events.
   */
  public boolean enclosedProgress() {
    return options.enclosedProgress;
  }

  /** Returns the optional executor on which {@link Listener} methods are called. */
  public Optional<Executor> executor() {
    return userVisibleExecutor ? Optional.of(options.executor) : Optional.empty();
  }

  /**
   * Returns a {@code BodyPublisher} that tracks the given {@code BodyPublisher}'s upload progress.
   */
  public BodyPublisher tracking(BodyPublisher upstream, Listener listener) {
    requireNonNull(upstream, "upstream");
    requireNonNull(listener, "listener");
    var trackingPublisher = new TrackingBodyPublisher(upstream, listener, options);
    // Don't swallow upstream's MediaType if there's one
    return upstream instanceof MimeBodyPublisher
        ? MoreBodyPublishers.ofMediaType(
            trackingPublisher, ((MimeBodyPublisher) upstream).mediaType())
        : trackingPublisher;
  }

  /**
   * Returns a {@code BodyPublisher} that tracks the given {@code MultipartBodyPublisher}'s upload
   * progress with per-part progress events.
   */
  public MimeBodyPublisher trackingMultipart(
      MultipartBodyPublisher upstream, MultipartListener listener) {
    requireNonNull(upstream, "upstream");
    requireNonNull(listener, "listener");
    return new MultipartTrackingBodyPublisher(upstream, listener, options);
  }

  /**
   * Returns a {@code BodySubscriber} that tracks the given {@code BodySubscriber}'s download
   * progress. {@code contentLengthIfKnown} is the total length of downloaded content or a negative
   * value if unknown.
   */
  public <T> BodySubscriber<T> tracking(
      BodySubscriber<T> downstream, Listener listener, long contentLengthIfKnown) {
    requireNonNull(downstream, "downstream");
    requireNonNull(listener, "listener");
    return new TrackingBodySubscriber<>(downstream, listener, options, contentLengthIfKnown);
  }

  /**
   * Returns a {@code BodyHandler} that tracks the download progress of the {@code BodySubscriber}
   * returned by the given handler.
   */
  public <T> BodyHandler<T> tracking(BodyHandler<T> handler, Listener listener) {
    requireNonNull(handler, "handler");
    requireNonNull(listener, "listener");
    return responseInfo ->
        tracking(
            handler.apply(responseInfo),
            listener,
            responseInfo.headers().firstValueAsLong("Content-Length").orElse(-1));
  }

  /** Returns a default {@code ProgressTracker} with no thresholds or executor. */
  public static ProgressTracker create() {
    return ProgressTracker.newBuilder().build();
  }

  /** Returns a new {@code ProgressTracker.Builder}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  @FunctionalInterface
  private interface BaseListener<P extends Progress> extends Subscriber<P> {
    @Override
    default void onSubscribe(Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    default void onError(Throwable throwable) {}

    @Override
    default void onComplete() {}
  }

  /**
   * A listener of {@link Progress progress events}. {@code Listener} extends from {@code
   * Subscriber<Progress>} and provides sufficient default implementations for all methods but
   * {@code onNext}, allowing it to be used as a functional interface in most cases. Override
   * default methods if you want to handle other subscriber notifications. Note that if progress is
   * {@link #enclosedProgress() enclosed}, {@code onSubscribe} and {@code onComplete} can still be
   * detected by {@code onNext} as {@code 0%} or {@link Progress#done() 100%} progress events
   * respectively.
   */
  @FunctionalInterface
  public interface Listener extends BaseListener<Progress> {}

  /** A {@link Listener} of {@link MultipartProgress multipart progress events}. */
  @FunctionalInterface
  public interface MultipartListener extends BaseListener<MultipartProgress> {}

  /** A progress event. */
  public interface Progress {

    /** Returns the number of transferred bytes for this event. */
    long bytesTransferred();

    /** Returns the total number of bytes transferred so far. */
    long totalBytesTransferred();

    /** Returns the time passed between this and the previous progress events. */
    Duration timePassed();

    /** Returns the total time passed since the upload or download operation has begun. */
    Duration totalTimePassed();

    /** Returns content length, or a value less than zero if unknown. */
    long contentLength();

    /** Returns {@code true} if the upload or download operation is done. */
    boolean done();

    /**
     * Returns a double between {@code 0} and {@code 1} indicating progress, or {@link Double#NaN}
     * if not {@link #determinate()}.
     */
    default double value() {
      long length = contentLength();
      if (length <= 0L) {
        // Special case: length is 0 indicates no body and hence 100% progress
        return length == 0L ? 1.d : Double.NaN;
      }
      return 1.d * totalBytesTransferred() / length;
    }

    /** Returns {@code true} if this progress is determinate (i.e. content length is known). */
    default boolean determinate() {
      return contentLength() >= 0;
    }

    @Override
    String toString();
  }

  /** A progress event for a multipart body with per-part progress info. */
  public interface MultipartProgress extends Progress {

    /** Returns the currently progressing part. */
    Part part();

    /** Returns current part's progress. */
    Progress partProgress();

    /** Returns {@code true} if the currently progressing part has changed from the last event. */
    boolean partChanged();
  }

  /** A builder of {@code ProgressTrackers}. */
  public static final class Builder {
    private long bytesTransferredThreshold;
    private @MonotonicNonNull Duration timePassedThreshold;
    private @MonotonicNonNull Executor executor;
    private boolean enclosedProgress = true;
    private Clock clock = Clock.systemUTC();

    Builder() {}

    Builder clock(Clock clock) {
      this.clock = requireNonNull(clock);
      return this;
    }

    /**
     * Sets the minimum number of transferred bytes for a progress event to be signalled. The
     * default value is zero.
     *
     * @throws IllegalArgumentException if value is negative
     */
    public Builder bytesTransferredThreshold(long value) {
      requireArgument(value >= 0, "negative threshold: %s", value);
      this.bytesTransferredThreshold = value;
      return this;
    }

    /**
     * Sets the minimum amount of time to pass for a progress event to be signalled.
     *
     * @throws IllegalArgumentException if duration is not positive
     */
    public Builder timePassedThreshold(Duration duration) {
      requireNonNull(duration);
      requireNonNegativeDuration(duration);
      this.timePassedThreshold = duration;
      return this;
    }

    /**
     * If set to {@code true} (default), the sequence of progress events will be enclosed between
     * {@code 0%} and {@code 100%} progresses to detect begin and completion events respectively.
     */
    public Builder enclosedProgress(boolean enclosedProgress) {
      this.enclosedProgress = enclosedProgress;
      return this;
    }

    /**
     * Sets the executor on which {@link Listener} methods are called. If not set, the listener will
     * be signalled inline with body receiver.
     */
    public Builder executor(Executor executor) {
      this.executor = requireNonNull(executor);
      return this;
    }

    /** Builds a new {@code ProgressTracker}. */
    public ProgressTracker build() {
      return new ProgressTracker(this);
    }
  }

  private static final class Options {
    final long bytesTransferredThreshold;
    final @Nullable Duration timePassedThreshold;
    final Executor executor;
    final boolean enclosedProgress;
    final Clock clock;

    Options(
        long bytesTransferredThreshold,
        @Nullable Duration timePassedThreshold,
        Executor executor,
        boolean enclosedProgress,
        Clock clock) {
      this.bytesTransferredThreshold = bytesTransferredThreshold;
      this.timePassedThreshold = timePassedThreshold;
      this.executor = executor;
      this.enclosedProgress = enclosedProgress;
      this.clock = clock;
    }
  }

  // @VisibleForTesting
  static class ProgressSnapshot implements Progress {
    private final long bytesTransferred;
    private final long totalBytesTransferred;
    private final long contentLength;
    private final Duration timePassed;
    private final Duration totalTimePassed;
    private final boolean lastProgress;

    ProgressSnapshot(
        long bytesTransferred,
        long totalBytesTransferred,
        Duration timePassed,
        Duration totalTimePassed,
        long contentLength,
        boolean lastProgress) {
      this.bytesTransferred = bytesTransferred;
      this.totalBytesTransferred = totalBytesTransferred;
      this.contentLength = contentLength;
      this.timePassed = timePassed;
      this.totalTimePassed = totalTimePassed;
      this.lastProgress = lastProgress;
    }

    @Override
    public long bytesTransferred() {
      return bytesTransferred;
    }

    @Override
    public long totalBytesTransferred() {
      return totalBytesTransferred;
    }

    @Override
    public Duration timePassed() {
      return timePassed;
    }

    @Override
    public Duration totalTimePassed() {
      return totalTimePassed;
    }

    @Override
    public long contentLength() {
      return contentLength;
    }

    @Override
    public boolean done() {
      return lastProgress || (determinate() && totalBytesTransferred >= contentLength);
    }

    @Override
    public String toString() {
      return String.format(
          "Progress[bytesTransferred=%d, totalBytesTransferred=%d, timePassed=%s, totalTimePassed=%s, contentLength=%s]%s",
          bytesTransferred,
          totalBytesTransferred,
          timePassed,
          totalTimePassed,
          determinate() ? contentLength : "UNKNOWN",
          determinate() ? " " + (Math.round(10000.d * value()) / 100.d) + "%" : "");
    }
  }

  private static final class MultipartProgressSnapshot extends ProgressSnapshot
      implements MultipartProgress {
    private final Part part;
    private final Progress partProgress;
    private final boolean partChanged;

    MultipartProgressSnapshot(
        long bytesTransferred,
        long totalBytesTransferred,
        Duration timePassed,
        Duration totalTimePassed,
        long contentLength,
        boolean lastProgress,
        Part part,
        Progress partProgress,
        boolean partChanged) {
      super(
          bytesTransferred,
          totalBytesTransferred,
          timePassed,
          totalTimePassed,
          contentLength,
          lastProgress);
      this.part = part;
      this.partProgress = partProgress;
      this.partChanged = partChanged;
    }

    @Override
    public Part part() {
      return part;
    }

    @Override
    public Progress partProgress() {
      return partProgress;
    }

    @Override
    public boolean partChanged() {
      return partChanged;
    }
  }

  private abstract static class Progression<P extends Progress> {
    private final long bytesTransferredThreshold;
    private final Duration timePassedThreshold;
    final long contentLength;
    long bytesTransferred;
    long totalBytesTransferred;
    Instant startTime = Instant.MIN;
    Instant lastUpdateTime = Instant.MIN;
    Instant updateTime = Instant.MIN;

    Progression(
        long bytesTransferredThreshold,
        @Nullable Duration timePassedThreshold,
        long contentLength) {
      this.bytesTransferredThreshold = bytesTransferredThreshold;
      this.timePassedThreshold = requireNonNullElse(timePassedThreshold, Duration.ZERO);
      this.contentLength = contentLength;
    }

    void start(Instant startTime) {
      this.startTime = startTime;
      lastUpdateTime = startTime;
      updateTime = startTime;
    }

    void update(Instant updateTime, long byteCount) {
      this.updateTime = updateTime;
      bytesTransferred += byteCount;
      totalBytesTransferred += byteCount;
    }

    boolean hasPendingProgress() {
      return bytesTransferred >= bytesTransferredThreshold
          && Duration.between(lastUpdateTime, updateTime).compareTo(timePassedThreshold) >= 0;
    }

    void rewind() {
      bytesTransferred = 0L;
      lastUpdateTime = updateTime;
    }

    abstract P snapshot(boolean completed);
  }

  private static final class UnipartProgression extends Progression<Progress> {
    UnipartProgression(
        long bytesTransferredThreshold,
        @Nullable Duration timePassedThreshold,
        long contentLength) {
      super(bytesTransferredThreshold, timePassedThreshold, contentLength);
    }

    @Override
    Progress snapshot(boolean completed) {
      return new ProgressSnapshot(
          bytesTransferred,
          totalBytesTransferred,
          Duration.between(lastUpdateTime, updateTime),
          Duration.between(startTime, updateTime),
          contentLength,
          completed);
    }
  }

  private static final class MultipartProgression extends Progression<MultipartProgress> {
    private Part currentPart;
    private UnipartProgression partProgression;
    private boolean partChangePending;

    MultipartProgression(
        long bytesTransferredThreshold,
        @Nullable Duration timePassedThreshold,
        long contentLength,
        Part firstPart) {
      super(bytesTransferredThreshold, timePassedThreshold, contentLength);
      currentPart = firstPart;
      partProgression =
          new UnipartProgression(0, Duration.ZERO, firstPart.bodyPublisher().contentLength());
      partChangePending = true;
    }

    @Override
    void rewind() {
      super.rewind();
      partProgression.rewind();
    }

    void updatePart(Part part, Instant progressionStartTime) {
      currentPart = part;
      partProgression =
          new UnipartProgression(0, Duration.ZERO, part.bodyPublisher().contentLength());
      partProgression.start(progressionStartTime);
      partChangePending = true;
    }

    void updatePartProgress(Instant updateTime, long partByteCount) {
      partProgression.update(updateTime, partByteCount);
    }

    @Override
    MultipartProgress snapshot(boolean completed) {
      boolean partChanged = partChangePending;
      partChangePending = false;
      return new MultipartProgressSnapshot(
          bytesTransferred,
          totalBytesTransferred,
          Duration.between(lastUpdateTime, updateTime),
          Duration.between(startTime, updateTime),
          contentLength,
          completed,
          currentPart,
          partProgression.snapshot(false),
          partChanged);
    }
  }

  private abstract static class AbstractTrackingSubscriber<
          B, P extends Progress, R extends Progression<P>>
      extends ForwardingSubscriber<B> {
    private final Subscriber<? super B> downstream;
    private final ProgressSubscription listenerSubscription;

    AbstractTrackingSubscriber(
        Subscriber<? super B> downstream,
        BaseListener<P> listener,
        Options options,
        R progression) {
      this.downstream = downstream;
      listenerSubscription = new ProgressSubscription(listener, options, progression);
    }

    abstract long countBytes(B batch);

    // Overriden by MultipartTrackingSubscriber
    void updateProgression(R progression, Instant updateTime, long byteCount) {
      progression.update(updateTime, byteCount);
    }

    @Override
    protected Subscriber<? super B> downstream() {
      return downstream;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      requireNonNull(subscription);
      if (upstream.setOrCancel(subscription)) {
        try {
          listenerSubscription.onSubscribe();
        } finally {
          downstream().onSubscribe(subscription);
        }
      }
    }

    @Override
    public void onNext(B item) {
      listenerSubscription.onNext(countBytes(item));
      super.onNext(item);
    }

    @Override
    public void onError(Throwable throwable) {
      try {
        listenerSubscription.onError(throwable);
      } finally {
        super.onError(throwable);
      }
    }

    @Override
    public void onComplete() {
      try {
        listenerSubscription.onComplete();
      } finally {
        super.onComplete();
      }
    }

    private final class ProgressSubscription extends AbstractSubscription<P> {
      private final Options options;
      private final R progression;
      private final ConcurrentLinkedQueue<P> progressEvents = new ConcurrentLinkedQueue<>();
      private volatile boolean complete;

      /**
       * Whether to signal a 100% progress from onComplete(). This can be avoided in case a 100%
       * progress is already signalled from onNext().
       */
      private boolean signaledLastProgress;

      ProgressSubscription(BaseListener<P> listener, Options options, R progression) {
        super(listener, options.executor);
        this.options = options;
        this.progression = progression;
      }

      @Override
      protected long emit(Subscriber<? super P> downstream, long emit) {
        long submitted = 0L;
        while (true) {
          P progress;
          if (progressEvents.isEmpty() && complete) {
            cancelOnComplete(downstream);
            return 0L;
          } else if (submitted >= emit
              || (progress = progressEvents.poll()) == null) { // Exhausted demand or progresses
            return submitted;
          } else if (submitOnNext(downstream, progress)) {
            submitted++;
          } else {
            return 0L;
          }
        }
      }

      void onSubscribe() {
        progression.start(options.clock.instant());
        if (options.enclosedProgress) {
          // Publish a 0% progress event
          progressEvents.offer(progression.snapshot(false));
        }
        signal(true);
      }

      void onNext(long byteCount) {
        updateProgression(progression, options.clock.instant(), byteCount);
        if (progression.hasPendingProgress()) {
          var progress = progression.snapshot(false);
          signaledLastProgress = progress.done();
          progressEvents.offer(progress);
          progression.rewind();
          signal(false);
        }
      }

      void onError(Throwable error) {
        signalError(error);
      }

      void onComplete() {
        if (options.enclosedProgress && !signaledLastProgress) {
          // Signal 100% progress after updating time
          updateProgression(progression, options.clock.instant(), 0);
          progressEvents.offer(progression.snapshot(true));
        }
        complete = true;
        signal(true);
      }
    }
  }

  private static final class TrackingBodySubscriber<T>
      extends AbstractTrackingSubscriber<List<ByteBuffer>, Progress, UnipartProgression>
      implements BodySubscriber<T> {
    private final Supplier<CompletionStage<T>> bodySupplier;

    TrackingBodySubscriber(
        BodySubscriber<T> downstream,
        BaseListener<Progress> listener,
        Options options,
        long contentLength) {
      super(
          downstream,
          listener,
          options,
          new UnipartProgression(
              options.bytesTransferredThreshold, options.timePassedThreshold, contentLength));
      bodySupplier = downstream::getBody;
    }

    @Override
    long countBytes(List<ByteBuffer> buffers) {
      return buffers.stream().mapToLong(ByteBuffer::remaining).sum();
    }

    @Override
    public CompletionStage<T> getBody() {
      return bodySupplier.get();
    }
  }

  private static final class TrackingBodyPublisher extends ForwardingBodyPublisher {
    private final Listener listener;
    private final Options options;

    TrackingBodyPublisher(BodyPublisher upstream, Listener listener, Options options) {
      super(upstream);
      this.listener = listener;
      this.options = options;
    }

    @Override
    public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
      requireNonNull(subscriber);
      upstream().subscribe(new TrackingSubscriber(subscriber, listener, options, contentLength()));
    }

    private static final class TrackingSubscriber
        extends AbstractTrackingSubscriber<ByteBuffer, Progress, UnipartProgression> {
      TrackingSubscriber(
          Subscriber<? super ByteBuffer> downstream,
          Listener listener,
          Options options,
          long contentLength) {
        super(
            downstream,
            listener,
            options,
            new UnipartProgression(
                options.bytesTransferredThreshold, options.timePassedThreshold, contentLength));
      }

      @Override
      long countBytes(ByteBuffer buffer) {
        return buffer.remaining();
      }
    }
  }

  private static final class MultipartTrackingBodyPublisher extends ForwardingBodyPublisher
      implements MimeBodyPublisher {
    private final MultipartListener listener;
    private final Options options;
    private final List<Part> parts;
    private final MediaType mediaType;

    MultipartTrackingBodyPublisher(
        MultipartBodyPublisher upstream, MultipartListener listener, Options options) {
      super(upstream);
      this.listener = listener;
      this.options = options;
      this.parts = upstream.parts();
      this.mediaType = upstream.mediaType();
    }

    @Override
    public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
      requireNonNull(subscriber);
      upstream()
          .subscribe(
              new MultipartTrackingSubscriber(
                  subscriber, listener, options, contentLength(), parts.get(0)));
    }

    @Override
    public MediaType mediaType() {
      return mediaType;
    }

    private static final class MultipartTrackingSubscriber
        extends AbstractTrackingSubscriber<ByteBuffer, MultipartProgress, MultipartProgression>
        implements PartSequenceListener {
      private Part currentPart;
      private boolean partUpdatePending;
      private boolean partSequenceCompleted;

      MultipartTrackingSubscriber(
          Subscriber<? super ByteBuffer> downstream,
          MultipartListener listener,
          Options options,
          long contentLength,
          Part firstPart) {
        super(
            downstream,
            listener,
            options,
            new MultipartProgression(
                options.bytesTransferredThreshold,
                options.timePassedThreshold,
                contentLength,
                firstPart));
        currentPart = firstPart;
      }

      @Override
      public void onSubscribe(Subscription subscription) {
        PartSequenceListener.register(subscription, this);
        super.onSubscribe(subscription);
      }

      @Override
      long countBytes(ByteBuffer buffer) {
        return buffer.remaining();
      }

      @Override
      synchronized void updateProgression(
          MultipartProgression progression, Instant updateTime, long byteCount) {
        if (partUpdatePending) {
          partUpdatePending = false;
          progression.updatePart(currentPart, updateTime);
        } else if (!partSequenceCompleted) {
          progression.updatePartProgress(updateTime, byteCount);
        }
        progression.update(updateTime, byteCount);
      }

      @Override
      public synchronized void onNextPart(Part part) {
        currentPart = part;
        partUpdatePending = true;
      }

      @Override
      public synchronized void onSequenceCompletion() {
        partSequenceCompleted = true;
      }
    }
  }
}
