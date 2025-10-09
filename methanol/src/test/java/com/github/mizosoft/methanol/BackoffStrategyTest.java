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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.mizosoft.methanol.RetryingInterceptor.BackoffStrategy;
import com.github.mizosoft.methanol.internal.cache.HttpDates;
import com.github.mizosoft.methanol.testing.MockClock;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffStrategyTest {
  private static final HttpRequest request = MutableRequest.GET("https://example.com");
  private static final HttpResponse<?> response = ResponseBuilder.create().request(request).build();

  @Test
  void none() {
    assertThat(
            BackoffStrategy.none()
                .backoff(RetryingInterceptor.Context.of(request, response, null, 0)))
        .isEqualTo(Duration.ZERO);
    assertThat(
            BackoffStrategy.none()
                .backoff(RetryingInterceptor.Context.of(request, response, null, 1)))
        .isEqualTo(Duration.ZERO);
  }

  @Test
  void fixed() {
    var delay = Duration.ofSeconds(1);
    assertThat(
            BackoffStrategy.fixed(delay)
                .backoff(RetryingInterceptor.Context.of(request, response, null, 0)))
        .isEqualTo(delay);
    assertThat(
            BackoffStrategy.fixed(delay)
                .backoff(RetryingInterceptor.Context.of(request, response, null, 1)))
        .isEqualTo(delay);
  }

  @Test
  void fixedWithInvalidDelay() {
    assertThatThrownBy(() -> BackoffStrategy.fixed(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BackoffStrategy.fixed(Duration.ofMillis(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void linear() {
    var base = Duration.ofSeconds(1);
    var cap = Duration.ofSeconds(10);
    var strategy = BackoffStrategy.linear(base, cap);
    assertThat(strategy.backoff(RetryingInterceptor.Context.of(request, response, null, 0)))
        .isEqualTo(Duration.ofSeconds(1));
    assertThat(strategy.backoff(RetryingInterceptor.Context.of(request, response, null, 1)))
        .isEqualTo(Duration.ofSeconds(2));
    assertThat(strategy.backoff(RetryingInterceptor.Context.of(request, response, null, 2)))
        .isEqualTo(Duration.ofSeconds(3));
    assertThat(strategy.backoff(RetryingInterceptor.Context.of(request, response, null, 9)))
        .isEqualTo(cap);
    assertThat(strategy.backoff(RetryingInterceptor.Context.of(request, response, null, 20)))
        .isEqualTo(cap);
  }

  @Test
  void linearWithInvalidBase() {
    assertThatThrownBy(() -> BackoffStrategy.linear(Duration.ZERO, Duration.ofSeconds(10)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BackoffStrategy.linear(Duration.ofMillis(-1), Duration.ofSeconds(10)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exponential() {
    var base = Duration.ofSeconds(1);
    var cap = Duration.ofSeconds(16);
    var strategy = BackoffStrategy.exponential(base, cap);
    assertThat(strategy.backoff(RetryingInterceptor.Context.of(request, response, null, 0)))
        .isEqualTo(Duration.ofSeconds(1));
    assertThat(strategy.backoff(RetryingInterceptor.Context.of(request, response, null, 1)))
        .isEqualTo(Duration.ofSeconds(2));
    assertThat(strategy.backoff(RetryingInterceptor.Context.of(request, response, null, 2)))
        .isEqualTo(Duration.ofSeconds(4));
    assertThat(strategy.backoff(RetryingInterceptor.Context.of(request, response, null, 3)))
        .isEqualTo(Duration.ofSeconds(8));
    assertThat(strategy.backoff(RetryingInterceptor.Context.of(request, response, null, 4)))
        .isEqualTo(cap);
    assertThat(strategy.backoff(RetryingInterceptor.Context.of(request, response, null, 10)))
        .isEqualTo(cap);
  }

  @Test
  void exponentialWithInvalidParameters() {
    assertThatThrownBy(() -> BackoffStrategy.exponential(Duration.ZERO, Duration.ofSeconds(10)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> BackoffStrategy.exponential(Duration.ofMillis(-1), Duration.ofSeconds(10)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BackoffStrategy.exponential(Duration.ofSeconds(10), Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> BackoffStrategy.exponential(Duration.ofSeconds(10), Duration.ofMillis(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> BackoffStrategy.exponential(Duration.ofSeconds(10), Duration.ofSeconds(5)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void withJitter() {
    var base = Duration.ofSeconds(2);
    var strategy = BackoffStrategy.fixed(base).withJitter();
    var context = RetryingInterceptor.Context.of(request, response, null, 0);
    for (int i = 0; i < 100; i++) {
      var delay = strategy.backoff(context);
      assertThat(delay).isBetween(Duration.ZERO, base);
    }
  }

  @Test
  void withPartialJitter() {
    var base = Duration.ofSeconds(2);
    var jitterFactor = 0.5;
    var strategy = BackoffStrategy.fixed(base).withJitter(jitterFactor);
    var context = RetryingInterceptor.Context.of(request, response, null, 0);
    var expectedMin = Duration.ofSeconds(1);
    for (int i = 0; i < 100; i++) {
      var delay = strategy.backoff(context);
      assertThat(delay).isBetween(expectedMin, base);
    }
  }

  @Test
  void withJitterInvalidFactor() {
    var strategy = BackoffStrategy.fixed(Duration.ofSeconds(1));
    assertThatThrownBy(() -> strategy.withJitter(-0.1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> strategy.withJitter(1.1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void retryAfterWithoutHeader() {
    var strategy = BackoffStrategy.retryAfterOr(BackoffStrategy.fixed(Duration.ofSeconds(5)));
    var responseWithoutRetryAfter = ResponseBuilder.create().request(request).build();
    var context = RetryingInterceptor.Context.of(request, responseWithoutRetryAfter, null, 0);
    assertThat(strategy.backoff(context)).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void retryAfterWithDeltaSeconds() {
    var strategy = BackoffStrategy.retryAfterOr(BackoffStrategy.fixed(Duration.ofSeconds(5)));
    var responseWithRetryAfter =
        ResponseBuilder.create().request(request).header("Retry-After", "10").build();
    var context = RetryingInterceptor.Context.of(request, responseWithRetryAfter, null, 0);
    assertThat(strategy.backoff(context)).isEqualTo(Duration.ofSeconds(10));
  }

  @Test
  void retryAfterWithHttpDate() {
    var clock = new MockClock();
    var strategy =
        RetryingInterceptor.retryAfterOrBackoffStrategy(
            BackoffStrategy.fixed(Duration.ofSeconds(1)), clock);
    var responseWithRetryAfter =
        ResponseBuilder.from(response)
            .header(
                "Retry-After",
                HttpDates.formatHttpDate(
                    HttpDates.toUtcDateTime(clock.instant().plus(Duration.ofSeconds(10)))))
            .build();
    var context = RetryingInterceptor.Context.of(request, responseWithRetryAfter, null, 0);
    var delay = strategy.backoff(context);
    assertThat(delay).isEqualTo(Duration.ofSeconds(10));
  }

  @Test
  void retryAfterWithInvalidHeader() {
    var strategy = BackoffStrategy.retryAfterOr(BackoffStrategy.fixed(Duration.ofSeconds(1)));
    var responseWithInvalidRetryAfter =
        ResponseBuilder.create().request(request).header("Retry-After", "invalid-value").build();
    var context = RetryingInterceptor.Context.of(request, responseWithInvalidRetryAfter, null, 0);
    assertThat(strategy.backoff(context)).isEqualTo(Duration.ofSeconds(1));
  }

  @Test
  void retryAfterWithExceptionContext() {
    var strategy = BackoffStrategy.retryAfterOr(BackoffStrategy.fixed(Duration.ofSeconds(1)));
    var context = RetryingInterceptor.Context.of(request, null, new RuntimeException(), 0);
    assertThat(strategy.backoff(context)).isEqualTo(Duration.ofSeconds(1));
  }

  @Test
  void combinedStrategies() {
    var strategy =
        BackoffStrategy.exponential(Duration.ofSeconds(1), Duration.ofSeconds(16)).withJitter(0.25);
    for (int retryCount = 0; retryCount < 5; retryCount++) {
      var context = RetryingInterceptor.Context.of(request, response, null, retryCount);
      var delay = strategy.backoff(context);
      var expectedMax = Duration.ofSeconds(1L << retryCount);
      var expectedMin = Duration.ofMillis((long) (expectedMax.toMillis() * 0.75));
      assertThat(delay).isBetween(expectedMin, expectedMax);
    }
  }

  @Test
  void linearStrategyEdgeCases() {
    var strategy = BackoffStrategy.linear(Duration.ofMillis(100), Duration.ofMillis(500));
    assertThat(strategy.backoff(RetryingInterceptor.Context.of(request, response, null, 0)))
        .isEqualTo(Duration.ofMillis(100));

    assertThat(
            strategy.backoff(
                RetryingInterceptor.Context.of(request, response, null, Integer.MAX_VALUE)))
        .isEqualTo(Duration.ofMillis(500));
  }

  @Test
  void exponentialStrategyOverflow() {
    var strategy = BackoffStrategy.exponential(Duration.ofSeconds(1), Duration.ofDays(1));
    assertThat(strategy.backoff(RetryingInterceptor.Context.of(request, response, null, 100)))
        .isEqualTo(Duration.ofDays(1));
  }
}
