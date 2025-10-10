/*
 * Copyright (c) 2025 Moataz Hussein
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

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.HttpDates;
import com.github.mizosoft.methanol.internal.concurrent.Delayer;
import com.github.mizosoft.methanol.internal.util.Compare;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An interceptor that retries requests based on a specified policy.
 *
 * @see Builder
 */
public final class RetryingInterceptor implements Methanol.Interceptor {
  private final BiPredicate<HttpRequest, Chain<?>> selector;
  private final int maxRetries;
  private final Function<HttpRequest, HttpRequest> beginWith;
  private final List<RetryCondition> conditions;
  private final BackoffStrategy backoffStrategy;
  private final @Nullable Duration timeout;
  private final Clock clock;
  private final Delayer delayer;
  private final Listener listener;
  private final boolean throwOnExhaustion;

  private RetryingInterceptor(BiPredicate<HttpRequest, Chain<?>> selector, Builder builder) {
    this.selector = requireNonNull(selector);
    this.maxRetries = builder.maxRetries;
    this.beginWith = builder.beginWith;
    this.conditions = List.copyOf(builder.conditions);
    this.backoffStrategy = builder.backoffStrategy;
    this.timeout = builder.timeout;
    this.clock = builder.clock;
    this.delayer = builder.delayer;
    this.listener = builder.listener;
    this.throwOnExhaustion = builder.throwOnExhaustion;
  }

  @Override
  public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
      throws IOException, InterruptedException {
    if (!selector.test(request, chain)) {
      return chain.forward(request);
    }

    int retryCount = 0;
    var retry = new Retry(beginWith.apply(request), Duration.ZERO);
    var deadline = timeout != null ? clock.instant().plus(timeout) : null;
    listener.onFirstAttempt(retry.request);
    while (true) {
      if (!retry.delay.isZero()) {
        TimeUnit.MILLISECONDS.sleep(retry.delay.toMillis());
      }

      HttpResponse<T> response = null;
      Throwable exception = null;
      try {
        response = chain.forward(retry.request);
      } catch (Throwable e) {
        exception = e;
      }

      var context = Context.of(request, response, exception, retryCount, deadline);
      var action = proceed(context);
      if (action instanceof Retry) {
        // Continue retrying.
        retry = (Retry) action;
        retryCount++;
      } else if (action == Timeout.INSTANCE) {
        throw suppressing(
            context,
            new HttpRetryTimeoutException(
                "Retries for "
                    + request
                    + " timed out after "
                    + context.retryCount()
                    + " attempts"));
      } else if (action == Exhausted.INSTANCE) {
        throw suppressing(
            context,
            new HttpRetriesExhaustedException(
                "Retries for "
                    + request
                    + " exhausted after "
                    + context.retryCount()
                    + " attempts"));
      } else if (action == Complete.INSTANCE) {
        if (response != null) {
          return response;
        } else if (exception instanceof IOException) {
          throw (IOException) exception;
        } else if (exception instanceof InterruptedException) {
          throw (InterruptedException) exception;
        } else if (exception instanceof RuntimeException) {
          throw (RuntimeException) exception;
        } else {
          throw new IOException(exception);
        }
      } else {
        throw new AssertionError("Unexpected action: " + action);
      }
    }
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
      HttpRequest request, Chain<T> chain) {
    return selector.test(request, chain)
        ? new AsyncRetrier<>(request, chain, timeout != null ? clock.instant().plus(timeout) : null)
            .call()
        : chain.forwardAsync(request);
  }

  private final class AsyncRetrier<T> {
    private final HttpRequest request;
    private final Chain<T> chain;
    private final @Nullable Instant deadline;

    AsyncRetrier(HttpRequest request, Chain<T> chain, @Nullable Instant deadline) {
      this.request = request;
      this.chain = chain;
      this.deadline = deadline;
    }

    CompletableFuture<HttpResponse<T>> call() {
      var modifiedRequest = beginWith.apply(request);
      listener.onFirstAttempt(modifiedRequest);
      return continueRetry(null, new Retry(modifiedRequest, Duration.ZERO));
    }

    private CompletableFuture<HttpResponse<T>> continueRetry(
        @Nullable Context<T> context, Retry retry) {
      return retry.delay.isZero()
          ? continueRetryAfterDelay(context, retry)
          : delayer
              .delay(() -> {}, retry.delay, Runnable::run)
              .thenCompose(__ -> continueRetryAfterDelay(context, retry));
    }

    private CompletableFuture<HttpResponse<T>> continueRetryAfterDelay(
        @Nullable Context<T> context, Retry retry) {
      return chain
          .forwardAsync(retry.request)
          .handle(
              (response, exception) ->
                  handleRetry(
                      Context.of(
                          retry.request,
                          response,
                          Utils.getDeepCompletionCause(exception),
                          context != null ? context.retryCount() + 1 : 0,
                          deadline)))
          .thenCompose(Function.identity());
    }

    private CompletableFuture<HttpResponse<T>> handleRetry(Context<T> context) {
      var action = proceed(context);
      if (action instanceof Retry) {
        return continueRetry(context, (Retry) action);
      } else if (action == Timeout.INSTANCE) {
        return CompletableFuture.failedFuture(
            suppressing(
                context,
                new HttpRetryTimeoutException(
                    "Retries for "
                        + context.request()
                        + " timed out after "
                        + context.retryCount()
                        + " attempts")));
      } else if (action == Exhausted.INSTANCE) {
        return CompletableFuture.failedFuture(
            suppressing(
                context,
                new HttpRetriesExhaustedException(
                    "Retries for "
                        + context.request()
                        + " exhausted after "
                        + context.retryCount()
                        + " attempts")));
      } else if (action == Complete.INSTANCE) {
        return context
            .response()
            .map(CompletableFuture::completedFuture)
            .or(() -> context.exception().map(CompletableFuture::<HttpResponse<T>>failedFuture))
            .orElseThrow(
                () ->
                    new AssertionError(
                        "Expected response or exception to be present in context: " + context));
      } else {
        throw new AssertionError("Unexpected action: " + action);
      }
    }
  }

  private static <E extends Exception> E suppressing(Context<?> context, E exception) {
    context.exception().ifPresent(exception::addSuppressed);
    return exception;
  }

  private RetryAction proceed(Context<?> context) {
    if (context.deadline().isPresent() && !clock.instant().isBefore(context.deadline().get())) {
      listener.onTimeout(context);
      return Timeout.INSTANCE;
    } else if (context.retryCount() >= maxRetries) {
      if (throwOnExhaustion) {
        listener.onExhaustion(context);
        return Exhausted.INSTANCE;
      } else {
        listener.onComplete(context);
        return Complete.INSTANCE;
      }
    } else {
      return eval(context)
          .<RetryAction>map(
              nextRequest -> {
                var delay = backoffStrategy.backoff(context);
                listener.onRetry(context, nextRequest, delay);
                return new Retry(nextRequest, delay);
              })
          .orElseGet(
              () -> {
                listener.onComplete(context);
                return Complete.INSTANCE;
              });
    }
  }

  private Optional<HttpRequest> eval(Context<?> context) {
    return conditions.stream()
        .map(condition -> condition.test(context))
        .flatMap(Optional::stream)
        .findFirst()
        .map(
            request ->
                context
                    .deadline()
                    .map(deadline -> applyDeadline(request, deadline))
                    .orElse(request));
  }

  private HttpRequest applyDeadline(HttpRequest request, Instant deadline) {
    // Apply a timeout satisfying the deadline.
    var newTimeout = Duration.between(clock.instant(), deadline);
    return request.timeout().isEmpty() || newTimeout.compareTo(request.timeout().get()) < 0
        ? MutableRequest.copyOf(request).timeout(newTimeout).build()
        : request;
  }

  private interface RetryAction {}

  private static final class Retry implements RetryAction {
    final HttpRequest request;
    final Duration delay;

    Retry(HttpRequest request, Duration delay) {
      this.request = request;
      this.delay = delay;
    }
  }

  private enum Timeout implements RetryAction {
    INSTANCE
  }

  private enum Exhausted implements RetryAction {
    INSTANCE
  }

  private enum Complete implements RetryAction {
    INSTANCE
  }

  /** Context for deciding whether an HTTP call should be retried. */
  public interface Context<T> {

    /**
     * Returns the last-sent request. Note that this might be different from the {@link
     * HttpResponse#request() request} of this context's response (e.g. redirects).
     */
    HttpRequest request();

    /**
     * Returns the resulting response. Exactly one of {@code response()} or {@link #exception()} is
     * non-null.
     */
    Optional<HttpResponse<T>> response();

    /**
     * Returns the resulting exception. Exactly one of {@link #response()} or {@code exception()} is
     * non-null.
     */
    Optional<Throwable> exception();

    /** Returns the number of times the request has been retried. */
    int retryCount();

    /**
     * Returns an {@code Optional} specifying the deadline for retrying. If the given deadline is
     * exceeded, a {@link HttpRetryTimeoutException} is thrown.
     */
    Optional<Instant> deadline();

    default Context<T> next(
        HttpRequest request, @Nullable HttpResponse<T> response, @Nullable Throwable exception) {
      return new ContextImpl<>(
          request, response, exception, retryCount() + 1, deadline().orElse(null));
    }

    /**
     * Creates a new retry context based on the given state.
     *
     * @throws IllegalArgumentException if it is not the case that exactly one of {@code response}
     *     or {@code exception} is non-null, or if {@code retryCount} is negative
     */
    static <T> Context<T> of(
        HttpRequest request,
        @Nullable HttpResponse<T> response,
        @Nullable Throwable exception,
        int retryCount) {
      return of(request, response, exception, retryCount, null);
    }

    /**
     * Creates a new retry context based on the given state.
     *
     * @throws IllegalArgumentException if it is not the case that exactly one of {@code response}
     *     or {@code exception} is non-null, or if {@code retryCount} is negative
     */
    static <T> Context<T> of(
        HttpRequest request,
        @Nullable HttpResponse<T> response,
        @Nullable Throwable exception,
        int retryCount,
        @Nullable Instant deadline) {
      return new ContextImpl<>(request, response, exception, retryCount, deadline);
    }
  }

  /** A strategy for backing off (delaying) before a retry retries. */
  @FunctionalInterface
  public interface BackoffStrategy {

    /**
     * Returns the {@link Duration} to wait for before retrying the request for the given retry
     * number.
     */
    Duration backoff(Context<?> context);

    /**
     * Returns a {@code BackoffStrategy} that applies <a
     * href="https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/">full
     * jitter</a> to this {@code BackoffStrategy}. Calling this method is equivalent to {@link
     * #withJitter(double) withJitter(1.0)}.
     */
    default BackoffStrategy withJitter() {
      return withJitter(1.0);
    }

    /**
     * Returns a {@code BackoffStrategy} that applies <a
     * href="https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/">full
     * jitter</a> to this {@code BackoffStrategy}, where the degree of "fullness" is specified by
     * the given factor.
     */
    default BackoffStrategy withJitter(double factor) {
      requireArgument(
          Double.compare(factor, 0.0) >= 0 && Double.compare(factor, 1.0) <= 0,
          "Expected %f to be between 0.0 and 1.0",
          factor);
      return context -> {
        long delayMillis = backoff(context).toMillis();
        long jitterRangeMillis = Math.round(delayMillis * factor);
        return Duration.ofMillis(
            Math.max(
                0,
                delayMillis
                    - jitterRangeMillis
                    + Math.round(jitterRangeMillis * ThreadLocalRandom.current().nextDouble())));
      };
    }

    /** Returns a {@code BackoffStrategy} that applies no delays. */
    static BackoffStrategy none() {
      return __ -> Duration.ZERO;
    }

    /** Returns a {@code BackoffStrategy} that applies a fixed delay every retry. */
    static BackoffStrategy fixed(Duration delay) {
      requirePositiveDuration(delay);
      return __ -> delay;
    }

    /**
     * Returns a {@code BackoffStrategy} that applies a linearly increasing delay every retry, where
     * {@code base} specifies the first delay, and {@code cap} specifies the maximum delay.
     */
    static BackoffStrategy linear(Duration base, Duration cap) {
      requirePositiveDuration(base);
      return context -> {
        int retryCount = context.retryCount();
        return retryCount < Integer.MAX_VALUE // Avoid overflow.
            ? Compare.min(cap, base.multipliedBy(retryCount + 1))
            : cap;
      };
    }

    /**
     * Returns a {@code BackoffStrategy} that applies an exponentially (base 2) increasing delay
     * every retry, where {@code base} specifies the first delay, and {@code cap} specifies the
     * maximum delay.
     */
    static BackoffStrategy exponential(Duration base, Duration cap) {
      requirePositiveDuration(base);
      requirePositiveDuration(cap);
      requireArgument(
          base.compareTo(cap) <= 0,
          "Base delay (%s) must be less than or equal to cap delay (%s)",
          base,
          cap);
      return context -> {
        int retryCount = context.retryCount();
        return retryCount < Long.SIZE - 2 // Avoid overflow.
            ? Compare.min(cap, base.multipliedBy(1L << context.retryCount()))
            : cap;
      };
    }

    /**
     * Returns a {@code BackoffStrategy} that gets the delay from the value of response's {@code
     * Retry-After} header, or defers to the given {@code BackoffStrategy} if no such header exists.
     */
    static BackoffStrategy retryAfterOr(BackoffStrategy fallback) {
      return retryAfterOrBackoffStrategy(fallback, Utils.systemMillisUtc());
    }
  }

  static BackoffStrategy retryAfterOrBackoffStrategy(BackoffStrategy fallback, Clock clock) {
    requireNonNull(fallback);
    requireNonNull(clock);
    return context ->
        context
            .response()
            .flatMap(response -> tryFindDelayFromRetryAfter(response, clock))
            .orElseGet(() -> fallback.backoff(context));
  }

  private static Optional<Duration> tryFindDelayFromRetryAfter(
      HttpResponse<?> response, Clock clock) {
    return response
        .headers()
        .firstValue("Retry-After")
        .flatMap(
            value ->
                HttpDates.tryParseDeltaSeconds(value)
                    .or(
                        () ->
                            HttpDates.tryParseHttpDate(value)
                                .map(
                                    retryDate ->
                                        Compare.max(
                                            Duration.ZERO,
                                            Duration.between(
                                                clock.instant(),
                                                retryDate.toInstant(ZoneOffset.UTC))))));
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /** A listener for {@link RetryingInterceptor} events. */
  public interface Listener {

    /** Called when the interceptor is about to send the request for the first time. */
    default void onFirstAttempt(HttpRequest request) {}

    /**
     * Called when the interceptor is about to retry a request. The given context specifies the
     * retryable state. The given request and delay specify the next request to send (after any
     * request modification) and how long to wait before sending it, respectively.
     */
    default void onRetry(Context<?> context, HttpRequest nextRequest, Duration delay) {}

    /**
     * Called when the interceptor is about to return the given state as-is because no retry
     * condition matches.
     */
    default void onComplete(Context<?> context) {}

    /** Called when the interceptor times out before getting a returnable result. */
    default void onTimeout(Context<?> context) {}

    /**
     * Called when the interceptor exhausts allowed retry attempts. The given context's {@link
     * Context#retryCount()} equals the specified {@link Builder#maxRetries(int)}.
     */
    default void onExhaustion(Context<?> context) {}
  }

  private enum EmptyListener implements Listener {
    INSTANCE
  }

  /** A builder of {@link RetryingInterceptor} instances. */
  public static final class Builder {
    private static final int DEFAULT_MAX_ATTEMPTS = 5;

    private int maxRetries = DEFAULT_MAX_ATTEMPTS;
    private Function<HttpRequest, HttpRequest> beginWith = Function.identity();
    private BackoffStrategy backoffStrategy = BackoffStrategy.none();
    private @MonotonicNonNull Duration timeout;
    private Clock clock = Utils.systemMillisUtc();
    private Delayer delayer = Delayer.defaultDelayer();
    private Listener listener = EmptyListener.INSTANCE;
    private boolean throwOnExhaustion;

    private final List<RetryCondition> conditions = new ArrayList<>();

    Builder() {}

    @CanIgnoreReturnValue
    public Builder beginWith(Function<HttpRequest, HttpRequest> requestModifier) {
      this.beginWith = requireNonNull(requestModifier);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder maxRetries(int maxRetries) {
      requireArgument(maxRetries > 0, "maxRetries must be positive");
      this.maxRetries = maxRetries;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder backoff(BackoffStrategy backoffStrategy) {
      this.backoffStrategy = requireNonNull(backoffStrategy);
      return this;
    }

    @SafeVarargs
    @CanIgnoreReturnValue
    public final Builder onException(Class<? extends Throwable>... exceptionTypes) {
      return onException(Set.of(exceptionTypes), Context::request);
    }

    @CanIgnoreReturnValue
    public Builder onException(
        Set<Class<? extends Throwable>> exceptionTypes,
        Function<Context<?>, HttpRequest> requestModifier) {
      var exceptionTypesCopy = Set.copyOf(exceptionTypes);
      return onException(
          t -> exceptionTypesCopy.stream().anyMatch(c -> c.isInstance(t)), requestModifier);
    }

    @CanIgnoreReturnValue
    public Builder onException(Predicate<Throwable> exceptionPredicate) {
      return onException(exceptionPredicate, Context::request);
    }

    @CanIgnoreReturnValue
    public Builder onException(
        Predicate<Throwable> exceptionPredicate,
        Function<Context<?>, HttpRequest> requestModifier) {
      conditions.add(
          new RetryCondition(
              ctx -> ctx.exception().map(exceptionPredicate::test).orElse(false), requestModifier));
      return this;
    }

    @CanIgnoreReturnValue
    public Builder onStatus(Integer... codes) {
      return onStatus(Set.of(codes), Context::request);
    }

    @CanIgnoreReturnValue
    public Builder onStatus(Set<Integer> codes, Function<Context<?>, HttpRequest> requestModifier) {
      var codesCopy = Set.copyOf(codes);
      return onStatus(codesCopy::contains, requestModifier);
    }

    @CanIgnoreReturnValue
    public Builder onStatus(Predicate<Integer> statusPredicate) {
      return onStatus(statusPredicate, Context::request);
    }

    @CanIgnoreReturnValue
    public Builder onStatus(
        Predicate<Integer> statusPredicate, Function<Context<?>, HttpRequest> requestModifier) {
      conditions.add(
          new RetryCondition(
              ctx -> ctx.response().map(r -> statusPredicate.test(r.statusCode())).orElse(false),
              requestModifier));
      return this;
    }

    @CanIgnoreReturnValue
    public Builder onResponse(Predicate<HttpResponse<?>> responsePredicate) {
      return onResponse(responsePredicate, Context::request);
    }

    @CanIgnoreReturnValue
    public Builder onResponse(
        Predicate<HttpResponse<?>> responsePredicate,
        Function<Context<?>, HttpRequest> requestModifier) {
      conditions.add(
          new RetryCondition(
              ctx -> ctx.response().map(responsePredicate::test).orElse(false), requestModifier));
      return this;
    }

    @CanIgnoreReturnValue
    public Builder on(Predicate<Context<?>> predicate) {
      return on(predicate, Context::request);
    }

    @CanIgnoreReturnValue
    public Builder on(
        Predicate<Context<?>> predicate, Function<Context<?>, HttpRequest> requestModifier) {
      this.conditions.add(new RetryCondition(predicate, requestModifier));
      return this;
    }

    @CanIgnoreReturnValue
    public Builder timeout(Duration timeout) {
      this.timeout = requirePositiveDuration(timeout);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder listener(Listener listener) {
      this.listener = requireNonNull(listener);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder throwOnExhaustion() {
      this.throwOnExhaustion = true;
      return this;
    }

    @CanIgnoreReturnValue
    Builder delayer(Delayer delayer) {
      this.delayer = requireNonNull(delayer);
      return this;
    }

    @CanIgnoreReturnValue
    Builder clock(Clock clock) {
      this.clock = requireNonNull(clock);
      return this;
    }

    public RetryingInterceptor build() {
      return build((__, ___) -> true);
    }

    public RetryingInterceptor build(Predicate<HttpRequest> selector) {
      requireNonNull(selector);
      return build((request, __) -> selector.test(request));
    }

    public RetryingInterceptor build(BiPredicate<HttpRequest, Chain<?>> selector) {
      return new RetryingInterceptor(selector, this);
    }
  }

  private static final class RetryCondition {
    final Predicate<Context<?>> predicate;
    final Function<Context<?>, HttpRequest> requestModifier;

    RetryCondition(
        Predicate<Context<?>> predicate, Function<Context<?>, HttpRequest> requestModifier) {
      this.predicate = requireNonNull(predicate);
      this.requestModifier = requireNonNull(requestModifier);
    }

    Optional<HttpRequest> test(Context<?> context) {
      return predicate.test(context)
          ? Optional.of(requestModifier.apply(context))
          : Optional.empty();
    }
  }

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  private static final class ContextImpl<T> implements Context<T> {
    private final HttpRequest request;
    private final Optional<HttpResponse<T>> response;
    private final Optional<Throwable> exception;
    private final int retryCount;
    private final Optional<Instant> deadline;

    ContextImpl(
        HttpRequest request,
        @Nullable HttpResponse<T> response,
        @Nullable Throwable exception,
        int retryCount,
        @Nullable Instant deadline) {
      requireArgument(
          response != null ^ exception != null,
          "Exactly one of response or exception must be non-null");
      requireArgument(retryCount >= 0, "Expected retryCount to be non-negative");
      this.request = request;
      this.response = Optional.ofNullable(response);
      this.exception = Optional.ofNullable(exception);
      this.retryCount = retryCount;
      this.deadline = Optional.ofNullable(deadline);
    }

    @Override
    public HttpRequest request() {
      return request;
    }

    @Override
    public Optional<HttpResponse<T>> response() {
      return response;
    }

    @Override
    public Optional<Throwable> exception() {
      return exception;
    }

    @Override
    public int retryCount() {
      return retryCount;
    }

    @Override
    public Optional<Instant> deadline() {
      return deadline;
    }

    @Override
    public String toString() {
      return Utils.toStringIdentityPrefix(this)
          + "[request="
          + request
          + ", response="
          + response
          + ", exception="
          + exception
          + ", retryCount="
          + retryCount
          + ", deadline="
          + deadline
          + ']';
    }
  }
}
