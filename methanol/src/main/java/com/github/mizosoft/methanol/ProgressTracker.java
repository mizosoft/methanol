/*
 * Copyright (c) 2019, 2020 Moataz Abdelnasser
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

import static com.github.mizosoft.methanol.internal.Utils.requirePositiveDuration;
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.MultipartBodyPublisher.Part;
import com.github.mizosoft.methanol.MultipartBodyPublisher.PartPeeker;
import com.github.mizosoft.methanol.internal.Validate;
import com.github.mizosoft.methanol.internal.flow.AbstractSubscription;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.flow.ForwardingBodyPublisher;
import com.github.mizosoft.methanol.internal.flow.ForwardingSubscriber;
import java.net.http.HttpHeaders;
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
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Allows attaching a listener to un upload or download operation for tracking progress. */
public final class ProgressTracker {

  private static final long UNKNOWN_CONTENT_LENGTH = -1;

  private final Options options;
  private final boolean userExecutor;

  private ProgressTracker(Builder builder) {
    var configuredExecutor = builder.executor;
    Executor executor;
    if (configuredExecutor != null) {
      executor = configuredExecutor;
      userExecutor = true;
    } else {
      executor = FlowSupport.SYNC_EXECUTOR;
      userExecutor = false;
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
    return userExecutor ? Optional.of(options.executor) : Optional.empty();
  }

  /**
   * Returns a {@code BodyPublisher} that tracks the given {@code BodyPublisher}'s upload progress.
   */
  public BodyPublisher tracking(BodyPublisher upstream, Listener listener) {
    requireNonNull(upstream, "upstream");
    requireNonNull(listener, "listener");
    if (upstream instanceof MultipartBodyPublisher) {
      return new MultipartTrackingBodyPublisher(
          options, new MultipartListenerAdapter(listener), (MultipartBodyPublisher) upstream);
    }
    return new UnipartTrackingBodyPublisher(options, listener, upstream);
  }

  /**
   * Returns a {@code BodyPublisher} that tracks the given {@code MultipartBodyPublisher}'s upload
   * progress with per-part progress events.
   */
  public BodyPublisher trackingMultipart(
      MultipartBodyPublisher upstream, MultipartListener listener) {
    requireNonNull(upstream, "upstream");
    requireNonNull(listener, "listener");
    return new MultipartTrackingBodyPublisher(options, listener, upstream);
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
    return new TrackingBodySubscriber<>(options, listener, contentLengthIfKnown, downstream);
  }

  /**
   * Returns a {@code BodyHandler} that tracks the download progress of the {@code BodySubscriber}
   * returned by the given handler.
   */
  public <T> BodyHandler<T> tracking(BodyHandler<T> handler, Listener listener) {
    requireNonNull(handler, "handler");
    requireNonNull(listener, "listener");
    return info -> tracking(handler.apply(info), listener, contentLengthIfKnown(info.headers()));
  }

  private static long contentLengthIfKnown(HttpHeaders headers) {
    return headers.firstValueAsLong("Content-Length").orElse(UNKNOWN_CONTENT_LENGTH);
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

    /** Returns content length, or a value less than zero if unknown. */
    long contentLength();

    /** Returns the time passed between this and the previous progress events. */
    Duration timePassed();

    /** Returns the total time passed since the upload or download operation has begun. */
    Duration totalTimePassed();

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

    /** Returns {@code true} if the currently progressing part has changed. */
    boolean partChanged();

    /** Returns the currently progressing part. */
    Part part();

    /** Returns current part's progress. */
    Progress partProgress();
  }

  /** A builder of {@code ProgressTrackers}. */
  public static final class Builder {

    private long bytesTransferredThreshold;
    private boolean enclosedProgress;
    private @MonotonicNonNull Duration timePassedThreshold;
    private @MonotonicNonNull Executor executor;
    private Clock clock;

    Builder() {
      enclosedProgress = true;
      clock = Clock.systemUTC();
    }

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
      requirePositiveDuration(duration);
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

  private static final class MultipartListenerAdapter
      extends ForwardingSubscriber<MultipartProgress> implements MultipartListener {

    private final Listener listener;

    MultipartListenerAdapter(Listener listener) {
      this.listener = listener;
    }

    @Override
    protected Subscriber<? super MultipartProgress> downstream() {
      return listener;
    }
  }

  private abstract static class Progression<P extends Progress> {

    final long bytesTransferredThreshold;
    final long contentLength;
    final @Nullable Duration timePassedThreshold;
    final Instant startTime;
    long bytesTransferred;
    long totalBytesTransferred;
    Instant lastUpdateTime;
    Instant updateTime;

    Progression(
        Instant startTime,
        long bytesTransferredThreshold,
        @Nullable Duration timePassedThreshold,
        long contentLength) {
      this.bytesTransferredThreshold = bytesTransferredThreshold;
      this.startTime = startTime;
      this.timePassedThreshold = timePassedThreshold;
      this.contentLength = contentLength;
      lastUpdateTime = startTime;
      updateTime = startTime;
    }

    void updateProgress(Instant updateTime, long byteCount) {
      this.updateTime = updateTime;
      bytesTransferred += byteCount;
      totalBytesTransferred += byteCount;
    }

    boolean shouldSignalProgress() {
      return bytesTransferred >= bytesTransferredThreshold
          && (timePassedThreshold == null
              || updateTime.compareTo(lastUpdateTime.plus(timePassedThreshold)) >= 0);
    }

    void rewind() {
      bytesTransferred = 0L;
      lastUpdateTime = updateTime;
    }

    abstract P snapshot(boolean lastlyEnclosing);

    abstract P zeroProgress();
  }

  private static final class UnipartProgression extends Progression<Progress> {

    UnipartProgression(
        Instant startTime,
        long bytesTransferredThreshold,
        @Nullable Duration timePassedThreshold,
        long contentLength) {
      super(startTime, bytesTransferredThreshold, timePassedThreshold, contentLength);
    }

    @Override
    Progress snapshot(boolean lastlyEnclosing) {
      return new ImmutableProgress(
          bytesTransferred,
          totalBytesTransferred,
          contentLength,
          Duration.between(lastUpdateTime, updateTime),
          Duration.between(startTime, updateTime),
          lastlyEnclosing);
    }

    @Override
    Progress zeroProgress() {
      return new ImmutableProgress(0L, 0L, contentLength, Duration.ZERO, Duration.ZERO, false);
    }
  }

  private static final class MultipartProgression extends Progression<MultipartProgress> {

    private final PartPeeker peeker;
    private int currentPartIndex;

    // becomes non-null on first updateProgress() call
    private @MonotonicNonNull UnipartProgression currentPartProgression;

    // The part is prefixed with headers (a ByteBuffer signal) so
    // one signal must be ignored to begin progressing the part itself
    private boolean partIsProgressing;

    // true only right after a part is updated before a MultipartProgress snapshot
    private boolean partChangePending;

    MultipartProgression(
        Instant startTime,
        long bytesTransferredThreshold,
        @Nullable Duration timePassedThreshold,
        long contentLength,
        PartPeeker peeker) {
      super(startTime, bytesTransferredThreshold, timePassedThreshold, contentLength);
      this.peeker = peeker;
    }

    @Override
    void updateProgress(Instant updateTime, long byteCount) {
      super.updateProgress(updateTime, byteCount);

      long partByteCount;
      if (partIsProgressing) {
        partByteCount = byteCount;
      } else {
        partIsProgressing = true;
        partByteCount = 0L;
      }

      var progression = Validate.castNonNull(currentPartProgression);
      progression.updateProgress(updateTime, partByteCount);
    }

    @Override
    void rewind() {
      super.rewind();

      var progression = Validate.castNonNull(currentPartProgression);
      progression.rewind();
    }

    @Override
    MultipartProgress snapshot(boolean lastlyEnclosing) {
      return new ImmutableMultipartProgress(
          bytesTransferred,
          totalBytesTransferred,
          contentLength,
          Duration.between(lastUpdateTime, updateTime),
          Duration.between(startTime, updateTime),
          lastlyEnclosing,
          partChanged(),
          peeker.at(currentPartIndex),
          Validate.castNonNull(currentPartProgression).snapshot(lastlyEnclosing));
    }

    @Override
    MultipartProgress zeroProgress() {
      var firstPart = peeker.at(0); // at least one part is guaranteed to exist
      return new ImmutableMultipartProgress(
          0L, 0L, contentLength, Duration.ZERO, Duration.ZERO, false, true, firstPart,
          new ImmutableProgress(0L, 0L, firstPart.bodyPublisher().contentLength(),
              Duration.ZERO, Duration.ZERO, false));
    }

    void updatePartIfNeeded(Instant newPartStartTime) {
      if (currentPartProgression == null || currentPartIndex != peeker.peekIndex()) {
        currentPartIndex = peeker.peekIndex();
        currentPartProgression = newPartProgression(newPartStartTime, peeker.at(currentPartIndex));
        partChangePending = true;
        partIsProgressing = false;
      }
    }

    private boolean partChanged() {
      if (partChangePending) {
        partChangePending = false;
        return true;
      } else {
        return false;
      }
    }

    private UnipartProgression newPartProgression(Instant startTime, Part part) {
      return new UnipartProgression(startTime, 0, null, part.bodyPublisher().contentLength());
    }
  }

  static class ImmutableProgress implements Progress {

    private final long bytesTransferred;
    private final long totalBytesTransferred;
    private final long contentLength;
    private final Duration timePassed;
    private final Duration totalTimePassed;
    private final boolean lastlyEnclosing;

    ImmutableProgress(
        long bytesTransferred,
        long totalBytesTransferred,
        long contentLength,
        Duration timePassed,
        Duration totalTimePassed,
        boolean lastlyEnclosing) {
      this.bytesTransferred = bytesTransferred;
      this.totalBytesTransferred = totalBytesTransferred;
      this.contentLength = contentLength;
      this.timePassed = timePassed;
      this.totalTimePassed = totalTimePassed;
      this.lastlyEnclosing = lastlyEnclosing;
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
    public long contentLength() {
      return contentLength;
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
    public boolean done() {
      return lastlyEnclosing || (determinate() && totalBytesTransferred >= contentLength);
    }

    @Override
    public String toString() {
      return String.format(
          "Progress[bytes=%d, totalBytes=%d, time=%s, totalTime=%s, contentLength=%s]%s",
          bytesTransferred,
          totalBytesTransferred,
          timePassed,
          totalTimePassed,
          determinate() ? contentLength : "UNKNOWN",
          determinate() ? " " + Math.round(10000 * value()) / 100.d + "%" : "");
    }
  }

  static final class ImmutableMultipartProgress extends ImmutableProgress
      implements MultipartProgress {

    private final boolean partChanged;
    private final Part part;
    private final Progress partProgress;

    ImmutableMultipartProgress(
        long bytesTransferred,
        long totalBytesTransferred,
        long contentLength,
        Duration timePassed,
        Duration totalTimePassed,
        boolean lastlyEnclosing,
        boolean partChanged,
        Part part,
        Progress partProgress) {
      super(
          bytesTransferred,
          totalBytesTransferred,
          contentLength,
          timePassed,
          totalTimePassed,
          lastlyEnclosing);
      this.partChanged = partChanged;
      this.part = part;
      this.partProgress = partProgress;
    }

    @Override
    public boolean partChanged() {
      return partChanged;
    }

    @Override
    public Part part() {
      return part;
    }

    @Override
    public Progress partProgress() {
      return partProgress;
    }
  }

  private abstract static class AbstractTrackingSubscriber<
          B, P extends Progress, R extends Progression<P>>
      extends ForwardingSubscriber<B> {

    final Options options;
    final BaseListener<P> listener;
    final long contentLength;

    private @Nullable ProgressSubscription progressSubscription;
    private boolean signalledCompletedProgress;

    AbstractTrackingSubscriber(Options options, BaseListener<P> listener, long contentLength) {
      this.options = options;
      this.listener = listener;
      this.contentLength = contentLength;
    }

    abstract long countBytes(B batch);

    abstract R createProgression(Instant startTime, Subscription upstreamSubscription);

    @Override
    public void onSubscribe(Subscription subscription) {
      if (upstream.setOrCancel(subscription)) {
        downstream().onSubscribe(subscription);
        listenerOnSubscribe(createProgression(options.clock.instant(), subscription));
      }
    }

    @Override
    public void onNext(B batch) {
      listenerOnNext(countBytes(batch));
      super.onNext(batch);
    }

    @Override
    public void onError(Throwable throwable) {
      try {
        listenerOnComplete(throwable);
      } finally {
        super.onError(throwable);
      }
    }

    @Override
    public void onComplete() {
      try {
        listenerOnComplete(null);
      } finally {
        super.onComplete();
      }
    }

    @Nullable P updateProgress(R progression, Instant updateTime, long bytesTransferred) {
      progression.updateProgress(updateTime, bytesTransferred);
      return progression.shouldSignalProgress() ? progression.snapshot(false) : null;
    }

    private void listenerOnSubscribe(R progression) {
      var subscription = new ProgressSubscription(progression);
      progressSubscription = subscription;
      if (options.enclosedProgress) {
        // put 0% progress
        subscription.progressEvents.offer(progression.zeroProgress());
      }
      subscription.signal(true);
    }

    private void listenerOnNext(long bytesTransferred) {
      var subscription = progressSubscription;
      if (subscription != null) {
        var progression = subscription.progression;
        var progress = updateProgress(progression, options.clock.instant(), bytesTransferred);
        if (progress != null) {
          signalledCompletedProgress = progress.done();

          progression.rewind();
          subscription.signalProgress(progress);
        }
      }
    }

    private void listenerOnComplete(@Nullable Throwable throwable) {
      var subscription = progressSubscription;
      if (subscription != null) {
        if (throwable != null) {
          subscription.signalError(throwable);
        } else {
          var progression = subscription.progression;
          // signal 100% progress if enclosing & not signalled from onNext
          if (options.enclosedProgress && !signalledCompletedProgress) {
            progression.updateProgress(options.clock.instant(), 0L); // update time
            subscription.progressEvents.offer(progression.snapshot(true));
          }

          subscription.signalCompletion();
        }
      }
    }

    private final class ProgressSubscription extends AbstractSubscription<P> {

      final R progression;
      final ConcurrentLinkedQueue<P> progressEvents;

      private volatile boolean complete;

      ProgressSubscription(R progression) {
        super(listener, options.executor);
        this.progression = progression;
        progressEvents = new ConcurrentLinkedQueue<>();
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
              || (progress = progressEvents.poll()) == null) { // exhausted demand or progresses
            return submitted;
          } else if (submitOnNext(downstream, progress)) {
            submitted++;
          } else {
            return 0L;
          }
        }
      }

      @Override
      protected void abort(boolean flowInterrupted) {
        // make parent loose reference to `this`
        AbstractTrackingSubscriber.this.progressSubscription = null;
      }

      void signalProgress(P progress) {
        progressEvents.offer(progress);
        signal(false); // signal drain task
      }

      void signalCompletion() {
        complete = true;
        signal(true); // force completion signal
      }
    }
  }

  /** Common superclass for request Unipart and Multipart tracking subscribers. */
  private abstract static class AbstractRequestTrackingSubscriber<
          P extends Progress, R extends Progression<P>>
      extends AbstractTrackingSubscriber<ByteBuffer, P, R> {

    private final Subscriber<? super ByteBuffer> downstream;

    AbstractRequestTrackingSubscriber(
        Options options,
        BaseListener<P> listener,
        long contentLength,
        Subscriber<? super ByteBuffer> downstream) {
      super(options, listener, contentLength);
      this.downstream = downstream;
    }

    @Override
    long countBytes(ByteBuffer batch) {
      return batch.remaining();
    }

    @Override
    protected Subscriber<? super ByteBuffer> downstream() {
      return downstream;
    }
  }

  private static final class TrackingBodySubscriber<T>
      extends AbstractTrackingSubscriber<List<ByteBuffer>, Progress, UnipartProgression>
      implements BodySubscriber<T> {

    private final BodySubscriber<T> downstream;

    TrackingBodySubscriber(
        Options options, Listener listener, long contentLength, BodySubscriber<T> downstream) {
      super(options, listener, contentLength);
      this.downstream = downstream;
    }

    @Override
    long countBytes(List<ByteBuffer> batch) {
      long count = 0L;
      for (var buffer : batch) {
        count += buffer.remaining();
      }
      return count;
    }

    @Override
    UnipartProgression createProgression(Instant startTime, Subscription ignored) {
      return new UnipartProgression(
          startTime, options.bytesTransferredThreshold, options.timePassedThreshold, contentLength);
    }

    @Override
    protected Subscriber<? super List<ByteBuffer>> downstream() {
      return downstream;
    }

    @Override
    public CompletionStage<T> getBody() {
      return downstream.getBody();
    }
  }

  private abstract static class AbstractTrackingBodyPublisher<
          P extends Progress, R extends Progression<P>>
      extends ForwardingBodyPublisher {

    final Options options;
    final BaseListener<P> listener;

    AbstractTrackingBodyPublisher(
        Options options, BaseListener<P> listener, BodyPublisher upstream) {
      super(upstream);
      this.options = options;
      this.listener = listener;
    }

    abstract AbstractRequestTrackingSubscriber<P, R> tracking(
        Subscriber<? super ByteBuffer> downstream);

    @Override
    public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
      requireNonNull(subscriber);
      upstream().subscribe(tracking(subscriber));
    }
  }

  private static final class UnipartTrackingBodyPublisher
      extends AbstractTrackingBodyPublisher<Progress, UnipartProgression> {

    UnipartTrackingBodyPublisher(Options options, Listener listener, BodyPublisher upstream) {
      super(options, listener, upstream);
    }

    @Override
    AbstractRequestTrackingSubscriber<Progress, UnipartProgression> tracking(
        Subscriber<? super ByteBuffer> downstream) {
      return new UnipartTrackingSubscriber(options, listener, contentLength(), downstream);
    }

    private static final class UnipartTrackingSubscriber
        extends AbstractRequestTrackingSubscriber<Progress, UnipartProgression> {

      UnipartTrackingSubscriber(
          Options options,
          BaseListener<Progress> listener,
          long contentLength,
          Subscriber<? super ByteBuffer> downstream) {
        super(options, listener, contentLength, downstream);
      }

      @Override
      UnipartProgression createProgression(Instant startTime, Subscription ignored) {
        return new UnipartProgression(
            startTime,
            options.bytesTransferredThreshold,
            options.timePassedThreshold,
            contentLength);
      }
    }
  }

  private static final class MultipartTrackingBodyPublisher
      extends AbstractTrackingBodyPublisher<MultipartProgress, MultipartProgression> {

    MultipartTrackingBodyPublisher(
        Options options,
        BaseListener<MultipartProgress> listener,
        MultipartBodyPublisher upstream) {
      super(options, listener, upstream);
    }

    @Override
    AbstractRequestTrackingSubscriber<MultipartProgress, MultipartProgression> tracking(
        Subscriber<? super ByteBuffer> downstream) {
      return new MultipartProgressSubscriber(options, listener, contentLength(), downstream);
    }

    private static final class MultipartProgressSubscriber
        extends AbstractRequestTrackingSubscriber<MultipartProgress, MultipartProgression> {

      MultipartProgressSubscriber(
          Options options,
          BaseListener<MultipartProgress> listener,
          long contentLength,
          Subscriber<? super ByteBuffer> downstream) {
        super(options, listener, contentLength, downstream);
      }

      @Override
      MultipartProgression createProgression(Instant startTime, Subscription upstreamSubscription) {
        return new MultipartProgression(
            startTime,
            options.bytesTransferredThreshold,
            options.timePassedThreshold,
            contentLength,
            PartPeeker.peeking(upstreamSubscription));
      }

      @Override
      @Nullable MultipartProgress updateProgress(
          MultipartProgression progression, Instant updateTime, long bytesTransferred) {
        progression.updatePartIfNeeded(updateTime); // updateTime is to be the start time of the next part
        return super.updateProgress(progression, updateTime, bytesTransferred);
      }
    }
  }
}
