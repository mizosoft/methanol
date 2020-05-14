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

import com.github.mizosoft.methanol.internal.flow.AbstractSubscription;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
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
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public final class ProgressTracker {

  private static final long UNKNOWN_CONTENT_LENGTH = -1;

  private final long bytesTransferredThreshold;
  private final Optional<Duration> timePassedThreshold;
  private final boolean enclosedProgress;
  private final Executor executor;
  private final boolean userExecutor;

  // helps with testing
  private final Clock clock;

  private ProgressTracker(Builder builder) {
    this.bytesTransferredThreshold = builder.bytesTransferredThreshold;
    this.timePassedThreshold = Optional.ofNullable(builder.timePassedThreshold);
    this.enclosedProgress = builder.enclosedProgress;
    var configuredExecutor = builder.executor;
    if (configuredExecutor != null) {
      executor = configuredExecutor;
      userExecutor = true;
    } else {
      executor = FlowSupport.SYNC_EXECUTOR;
      userExecutor = false;
    }

    clock = builder.clock;
  }

  /** Returns the minimum number of bytes to be transferred for a progress event to be signalled. */
  public long bytesTransferredThreshold() {
    return bytesTransferredThreshold;
  }

  /** Returns the minimum time to pass for a progress event to be signalled. */
  public Optional<Duration> timePassedThreshold() {
    return timePassedThreshold;
  }

  /**
   * Returns whether the sequence of progress events is enclosed between {@code 0%} and {@code 100%}
   * progress events.
   */
  public boolean enclosedProgress() {
    return enclosedProgress;
  }

  /** Returns the optional executor on which {@link Listener} methods are called. */
  public Optional<Executor> executor() {
    return userExecutor ? Optional.of(executor) : Optional.empty();
  }

  /**
   * Returns a {@code BodyPublisher} that tracks the given {@code BodyPublisher}'s upload progress.
   */
  public BodyPublisher tracking(BodyPublisher upstream, Listener listener) {
    requireNonNull(upstream, "upstream");
    requireNonNull(listener, "listener");
    return new TrackingBodyPublisher(
        upstream,
        listener,
        bytesTransferredThreshold,
        timePassedThreshold.orElse(null),
        enclosedProgress,
        executor,
        clock);
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
    return new TrackingBodySubscriber<>(
        downstream,
        listener,
        contentLengthIfKnown,
        bytesTransferredThreshold,
        timePassedThreshold.orElse(null),
        enclosedProgress,
        executor,
        clock);
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

  /** Returns a default {@code ProgressTracker} with no thresholds. */
  public static ProgressTracker create() {
    return ProgressTracker.newBuilder().build();
  }

  /** Returns a new {@code ProgressTracker.Builder}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * A listener of {@link Progress progress events}. {@code Listener} extends from {@code
   * Subscriber<Progress>} and provides sufficient default implementations for all methods but
   * {@code onNext}, allowing it to be used as a functional interface in most cases. Override
   * default methods if you want to handle other subscriber notifications. Note that if progress
   * is {@link #enclosedProgress() enclosed}, {@code onSubscribe} and {@code onComplete} can still
   * be detected by {@code onNext} as {@code 0%} or {@link Progress#completed() 100%} progress
   * events respectively.
   */
  @FunctionalInterface
  public interface Listener extends Subscriber<Progress> {

    @Override
    default void onSubscribe(Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    default void onError(Throwable throwable) {}

    @Override
    default void onComplete() {}
  }

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

    /** Returns {@code true} if the upload or download operation is complete. */
    boolean completed();

    /**
     * Returns a double between {@code 0} and {@code 1} indicating progress, or {@link Double#NaN}
     * if content length is not known.
     */
    default double value() {
      long length = contentLength();
      if (length <= 0L) {
        // Special case: length is 0 indicates no body and hence 100% progress
        return length == 0L ? 1.d : Double.NaN;
      }
      return 1.d * totalBytesTransferred() / length;
    }

    @Override
    String toString();
  }

  /** A builder of {@code ProgressTrackers}. */
  public static final class Builder {

    private long bytesTransferredThreshold;
    private boolean enclosedProgress;
    private @MonotonicNonNull Duration timePassedThreshold;
    private @MonotonicNonNull Executor executor;
    public Clock clock;

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

  static final class ImmutableProgress implements Progress {

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
    public boolean completed() {
      return lastlyEnclosing || (contentLength >= 0 && totalBytesTransferred >= contentLength);
    }

    @Override
    public String toString() {
      return String.format(
          "Progress[bytes=%d, totalBytes=%d, time=%s, totalTime=%s, contentLength=%s]%s",
          bytesTransferred,
          totalBytesTransferred,
          timePassed,
          totalTimePassed,
          contentLength >= 0L ? contentLength : "UNKNOWN",
          contentLength >= 0L ? " " + Math.round(10000 * value()) / 100.d + "%" : "");
    }
  }

  private abstract static class AbstractTrackingSubscriber<B> extends ForwardingSubscriber<B> {

    private final Listener listener;
    private final long contentLength;
    private final long bytesTransferredThreshold;
    private final @Nullable Duration timePassedThreshold;
    private final boolean enclosedProgress;
    private final Executor executor;
    private @Nullable ProgressSubscription progressSubscription;
    private boolean signalledCompletedProgress;
    private final Clock clock;

    AbstractTrackingSubscriber(
        Listener listener,
        long contentLength,
        long bytesTransferredThreshold,
        @Nullable Duration timePassedThreshold,
        boolean enclosedProgress,
        Executor executor,
        Clock clock) {
      this.listener = listener;
      this.contentLength = contentLength;
      this.bytesTransferredThreshold = bytesTransferredThreshold;
      this.timePassedThreshold = timePassedThreshold;
      this.enclosedProgress = enclosedProgress;
      this.executor = executor;
      this.clock = clock;
    }

    abstract long countBytes(B batch);

    @Override
    public void onSubscribe(Subscription subscription) {
      if (upstream.setOrCancel(subscription)) {
        downstream().onSubscribe(subscription);
        listenerOnSubscribe();
      }
    }

    @Override
    public void onNext(B item) {
      listenerOnNext(countBytes(item));
      super.onNext(item);
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

    private void listenerOnSubscribe() {
      var subscription = new ProgressSubscription(listener, executor, clock.instant());
      progressSubscription = subscription;
      if (enclosedProgress) {
        // put 0% progress
        subscription.progressEvents.offer(
            new ImmutableProgress(0L, 0L, contentLength, Duration.ZERO, Duration.ZERO, false));
      }
      subscription.signal(true);
    }

    private void listenerOnNext(long bytesTransferred) {
      var subscription = progressSubscription;
      if (subscription != null) {
        subscription.updateProgress(bytesTransferred);
        if (subscription.shouldSignalProgress()) {
          var progress = subscription.currentProgress(false);
          signalledCompletedProgress = progress.completed();
          subscription.reset();
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
          // signal 100% progress if enclosing & not signalled from onNext
          if (enclosedProgress && !signalledCompletedProgress) {
            subscription.updateProgress(0L); // update time passed
            subscription.progressEvents.offer(subscription.currentProgress(true));
          }
          subscription.signalCompletion();
        }
      }
    }

    private final class ProgressSubscription extends AbstractSubscription<Progress> {

      private final Instant startTime;
      private long bytesTransferred;
      private long totalBytesTransferred;
      private Instant lastUpdateTime;
      private Instant updateTime;

      private final ConcurrentLinkedQueue<Progress> progressEvents;
      private volatile boolean complete;

      ProgressSubscription(Listener listener, Executor executor, Instant startTime) {
        super(listener, executor);
        this.startTime = startTime;
        lastUpdateTime = startTime;
        updateTime = startTime;
        this.progressEvents = new ConcurrentLinkedQueue<>();
      }

      @Override
      protected long emit(Subscriber<? super Progress> downstream, long emit) {
        long submitted = 0L;
        while (true) {
          Progress progress;
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

      void updateProgress(long byteCount) {
        bytesTransferred += byteCount;
        totalBytesTransferred += byteCount;
        updateTime = clock.instant();
      }

      boolean shouldSignalProgress() {
        return bytesTransferred >= bytesTransferredThreshold
            && (timePassedThreshold == null
                || updateTime.compareTo(lastUpdateTime.plus(timePassedThreshold)) >= 0);
      }

      void reset() {
        bytesTransferred = 0L;
        lastUpdateTime = updateTime;
      }

      Progress currentProgress(boolean lastlyEnclosing) {
        return new ImmutableProgress(
            bytesTransferred,
            totalBytesTransferred,
            contentLength,
            Duration.between(lastUpdateTime, updateTime),
            Duration.between(startTime, updateTime),
            lastlyEnclosing);
      }

      void signalProgress(Progress progress) {
        progressEvents.offer(progress);
        signal(false); // signal drain task
      }

      void signalCompletion() {
        complete = true;
        signal(true); // force completion signal
      }
    }
  }

  private static final class TrackingBodyPublisher implements BodyPublisher {

    private final BodyPublisher upstream;
    private final Listener listener;
    private final long bytesTransferredThreshold;
    private final @Nullable Duration timePassedThreshold;
    private final boolean enclosedProgress;
    private final Executor executor;
    private final Clock clock;

    TrackingBodyPublisher(
        BodyPublisher upstream,
        Listener listener,
        long bytesTransferredThreshold,
        @Nullable Duration timePassedThreshold,
        boolean enclosedProgress,
        Executor executor,
        Clock clock) {
      this.upstream = upstream;
      this.listener = listener;
      this.bytesTransferredThreshold = bytesTransferredThreshold;
      this.timePassedThreshold = timePassedThreshold;
      this.enclosedProgress = enclosedProgress;
      this.executor = executor;
      this.clock = clock;
    }

    @Override
    public long contentLength() {
      return upstream.contentLength();
    }

    @Override
    public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
      requireNonNull(subscriber);
      upstream.subscribe(new TrackingSubscriber(subscriber, this));
    }

    private static final class TrackingSubscriber extends AbstractTrackingSubscriber<ByteBuffer> {

      private final Subscriber<? super ByteBuffer> downstream;

      TrackingSubscriber(Subscriber<? super ByteBuffer> downstream, TrackingBodyPublisher parent) {
        super(
            parent.listener,
            parent.contentLength(),
            parent.bytesTransferredThreshold,
            parent.timePassedThreshold,
            parent.enclosedProgress,
            parent.executor,
            parent.clock);
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
  }

  private static final class TrackingBodySubscriber<T>
      extends AbstractTrackingSubscriber<List<ByteBuffer>> implements BodySubscriber<T> {

    private final BodySubscriber<T> downstream;

    TrackingBodySubscriber(
        BodySubscriber<T> downstream,
        Listener listener,
        long contentLength,
        long bytesTransferredThreshold,
        @Nullable Duration timePassedThreshold,
        boolean enclosedProgress,
        Executor executor,
        Clock clock) {
      super(
          listener,
          contentLength,
          bytesTransferredThreshold,
          timePassedThreshold,
          enclosedProgress,
          executor,
          clock);
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
    protected Subscriber<? super List<ByteBuffer>> downstream() {
      return downstream;
    }

    @Override
    public CompletionStage<T> getBody() {
      return downstream.getBody();
    }
  }
}
