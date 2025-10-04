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

import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.testing.RecordingHttpClient;
import com.github.mizosoft.methanol.testing.TestException;
import java.io.IOException;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(1)
class RetryerTest {
  private RecordingHttpClient recordingClient;
  private Methanol.WithClientBuilder clientBuilder;

  @BeforeEach
  void setUp() {
    recordingClient = new RecordingHttpClient();
    clientBuilder = Methanol.newBuilder(recordingClient);
  }

  @Test
  void retryOnException() {
    var client =
        clientBuilder
            .interceptor(
                Methanol.Retryer.newBuilder()
                    .always(retry -> retry.atMost(2).onException(TestException.class))
                    .build()
                    .toInterceptor())
            .build();
    var responseFuture =
        client.sendAsync(MutableRequest.GET("https://example.com"), BodyHandlers.discarding());

    recordingClient.awaitCall().completeExceptionally(new TestException());
    recordingClient.awaitCall().completeExceptionally(new TestException());
    recordingClient.awaitCall().completeExceptionally(new TestException());

    assertThat(responseFuture)
        .isCompletedExceptionally()
        .failsWithin(Duration.ZERO)
        .withThrowableOfType(ExecutionException.class)
        .withCauseExactlyInstanceOf(TestException.class);
  }

  @Test
  void retryOnExceptionEndingWithDifferentException() {
    var client =
        clientBuilder
            .interceptor(
                Methanol.Retryer.newBuilder()
                    .always(retry -> retry.atMost(2).onException(TestException.class))
                    .build()
                    .toInterceptor())
            .build();
    var responseFuture =
        client.sendAsync(MutableRequest.GET("https://example.com"), BodyHandlers.discarding());

    recordingClient.awaitCall().completeExceptionally(new TestException());
    recordingClient.awaitCall().completeExceptionally(new IOException()); // No retry.

    assertThat(responseFuture)
        .isCompletedExceptionally()
        .failsWithin(Duration.ZERO)
        .withThrowableOfType(ExecutionException.class)
        .withCauseExactlyInstanceOf(IOException.class);
  }

  @Test
  void retryOnExceptionEndingWithSuccess() {
    var client =
        clientBuilder
            .interceptor(
                Methanol.Retryer.newBuilder()
                    .always(retry -> retry.atMost(2).onException(TestException.class))
                    .build()
                    .toInterceptor())
            .build();
    var responseFuture =
        client.sendAsync(MutableRequest.GET("https://example.com"), BodyHandlers.discarding());

    recordingClient.awaitCall().completeExceptionally(new TestException());
    recordingClient.awaitCall().complete();

    assertThat(responseFuture).isCompleted();
  }

  @Test
  void retryOnExceptionPredicate() {
    var client =
        clientBuilder
            .interceptor(
                Methanol.Retryer.newBuilder()
                    .always(
                        retry ->
                            retry
                                .atMost(2)
                                .onException(
                                    e -> e instanceof TestException || e instanceof IOException))
                    .build()
                    .toInterceptor())
            .build();
    var responseFuture =
        client.sendAsync(MutableRequest.GET("https://example.com"), BodyHandlers.discarding());

    recordingClient.awaitCall().completeExceptionally(new TestException());
    recordingClient.awaitCall().completeExceptionally(new IOException());
    recordingClient.awaitCall().completeExceptionally(new TestException());

    assertThat(responseFuture)
        .isCompletedExceptionally()
        .failsWithin(Duration.ZERO)
        .withThrowableOfType(ExecutionException.class)
        .withCauseExactlyInstanceOf(TestException.class);
  }

  @Test
  void retryOnExceptionPredicateEndingWithDifferentException() {
    var client =
        clientBuilder
            .interceptor(
                Methanol.Retryer.newBuilder()
                    .always(
                        retry ->
                            retry
                                .atMost(2)
                                .onException(
                                    e -> e instanceof TestException || e instanceof IOException))
                    .build()
                    .toInterceptor())
            .build();
    var responseFuture =
        client.sendAsync(MutableRequest.GET("https://example.com"), BodyHandlers.discarding());

    recordingClient.awaitCall().completeExceptionally(new TestException());
    recordingClient.awaitCall().completeExceptionally(new IllegalStateException());

    assertThat(responseFuture)
        .isCompletedExceptionally()
        .failsWithin(Duration.ZERO)
        .withThrowableOfType(ExecutionException.class)
        .withCauseExactlyInstanceOf(IllegalStateException.class);
  }

  @Test
  void retryOnExceptionPredicateEndingWithSuccess() {
    var client =
        clientBuilder
            .interceptor(
                Methanol.Retryer.newBuilder()
                    .always(
                        retry ->
                            retry
                                .atMost(2)
                                .onException(
                                    e -> e instanceof TestException || e instanceof IOException))
                    .build()
                    .toInterceptor())
            .build();
    var responseFuture =
        client.sendAsync(MutableRequest.GET("https://example.com"), BodyHandlers.discarding());

    recordingClient.awaitCall().completeExceptionally(new TestException());
    recordingClient.awaitCall().complete();

    assertThat(responseFuture).isCompleted();
  }

  @Test
  void retryOnStatus() {
    var client =
        clientBuilder
            .interceptor(
                Methanol.Retryer.newBuilder()
                    .always(retry -> retry.atMost(2).onStatus(500))
                    .build()
                    .toInterceptor())
            .build();
    var responseFuture =
        client.sendAsync(MutableRequest.GET("https://example.com"), BodyHandlers.discarding());

    recordingClient.awaitCall().complete(builder -> builder.statusCode(500));
    recordingClient.awaitCall().complete(builder -> builder.statusCode(500));
    recordingClient.awaitCall().complete(builder -> builder.statusCode(500));

    assertThat(responseFuture)
        .isCompleted()
        .succeedsWithin(Duration.ZERO)
        .matches(r -> r.statusCode() == 500);
  }

  @Test
  void retryOnStatusEndingWithDifferentStatus() {
    var client =
        clientBuilder
            .interceptor(
                Methanol.Retryer.newBuilder()
                    .always(retry -> retry.atMost(2).onStatus(500))
                    .build()
                    .toInterceptor())
            .build();
    var responseFuture =
        client.sendAsync(MutableRequest.GET("https://example.com"), BodyHandlers.discarding());

    recordingClient.awaitCall().complete(builder -> builder.statusCode(500));
    recordingClient.awaitCall().complete(builder -> builder.statusCode(200));

    assertThat(responseFuture)
        .isCompleted()
        .succeedsWithin(Duration.ZERO)
        .matches(r -> r.statusCode() == 200);
  }

  @Test
  void retryOnStatusEndingWithException() {
    var client =
        clientBuilder
            .interceptor(
                Methanol.Retryer.newBuilder()
                    .always(retry -> retry.atMost(2).onStatus(500))
                    .build()
                    .toInterceptor())
            .build();
    var responseFuture =
        client.sendAsync(MutableRequest.GET("https://example.com"), BodyHandlers.discarding());

    recordingClient.awaitCall().complete(builder -> builder.statusCode(500));
    recordingClient.awaitCall().completeExceptionally(new TestException());

    assertThat(responseFuture)
        .isCompletedExceptionally()
        .failsWithin(Duration.ZERO)
        .withThrowableOfType(ExecutionException.class)
        .withCauseExactlyInstanceOf(TestException.class);
  }

  @Test
  void retryOnStatusPredicate() {
    var client =
        clientBuilder
            .interceptor(
                Methanol.Retryer.newBuilder()
                    .always(retry -> retry.atMost(2).onStatus(HttpStatus::isServerError))
                    .build()
                    .toInterceptor())
            .build();
    var responseFuture =
        client.sendAsync(MutableRequest.GET("https://example.com"), BodyHandlers.discarding());

    recordingClient.awaitCall().complete(builder -> builder.statusCode(500));
    recordingClient.awaitCall().complete(builder -> builder.statusCode(501));
    recordingClient.awaitCall().complete(builder -> builder.statusCode(502));

    assertThat(responseFuture)
        .isCompleted()
        .succeedsWithin(Duration.ZERO)
        .matches(r -> r.statusCode() == 502);
  }

  @Test
  void retryOnStatusPredicateEndingWithDifferentStatus() {
    var client =
        clientBuilder
            .interceptor(
                Methanol.Retryer.newBuilder()
                    .always(retry -> retry.atMost(2).onStatus(HttpStatus::isServerError))
                    .build()
                    .toInterceptor())
            .build();
    var responseFuture =
        client.sendAsync(MutableRequest.GET("https://example.com"), BodyHandlers.discarding());

    recordingClient.awaitCall().complete(builder -> builder.statusCode(500));
    recordingClient.awaitCall().complete(builder -> builder.statusCode(404));

    assertThat(responseFuture)
        .isCompleted()
        .succeedsWithin(Duration.ZERO)
        .matches(r -> r.statusCode() == 404);
  }

  @Test
  void retryOnStatusPredicateEndingWithException() {
    var client =
        clientBuilder
            .interceptor(
                Methanol.Retryer.newBuilder()
                    .always(retry -> retry.atMost(2).onStatus(HttpStatus::isServerError))
                    .build()
                    .toInterceptor())
            .build();
    var responseFuture =
        client.sendAsync(MutableRequest.GET("https://example.com"), BodyHandlers.discarding());

    recordingClient.awaitCall().complete(builder -> builder.statusCode(500));
    recordingClient.awaitCall().completeExceptionally(new TestException());

    assertThat(responseFuture)
        .isCompletedExceptionally()
        .failsWithin(Duration.ZERO)
        .withThrowableOfType(ExecutionException.class)
        .withCauseExactlyInstanceOf(TestException.class);
  }

  @Test
  void retryOnResponsePredicate() {
    var client =
        clientBuilder
            .interceptor(
                Methanol.Retryer.newBuilder()
                    .always(
                        retry ->
                            retry
                                .atMost(2)
                                .onResponse(r -> r.headers().map().containsKey("X-Retry")))
                    .build()
                    .toInterceptor())
            .build();
    var responseFuture =
        client.sendAsync(MutableRequest.GET("https://example.com"), BodyHandlers.discarding());

    recordingClient.awaitCall().complete(builder -> builder.header("X-Retry", "true"));
    recordingClient.awaitCall().complete(builder -> builder.header("X-Retry", "true"));
    recordingClient.awaitCall().complete(builder -> builder.header("X-Retry", "true"));

    assertThat(responseFuture)
        .isCompleted()
        .succeedsWithin(Duration.ZERO)
        .matches(r -> r.headers().map().containsKey("X-Retry"));
  }

  @Test
  void retryOnResponsePredicateEndingWithDifferentResponse() {
    var client =
        clientBuilder
            .interceptor(
                Methanol.Retryer.newBuilder()
                    .always(
                        retry ->
                            retry
                                .atMost(2)
                                .onResponse(r -> r.headers().map().containsKey("X-Retry")))
                    .build()
                    .toInterceptor())
            .build();
    var responseFuture =
        client.sendAsync(MutableRequest.GET("https://example.com"), BodyHandlers.discarding());

    recordingClient.awaitCall().complete(builder -> builder.header("X-Retry", "true"));
    recordingClient.awaitCall().complete();

    assertThat(responseFuture)
        .isCompleted()
        .succeedsWithin(Duration.ZERO)
        .matches(r -> !r.headers().map().containsKey("X-Retry"));
  }

  @Test
  void retryOnResponsePredicateEndingWithException() {
    var client =
        clientBuilder
            .interceptor(
                Methanol.Retryer.newBuilder()
                    .always(
                        retry ->
                            retry
                                .atMost(2)
                                .onResponse(r -> r.headers().map().containsKey("X-Retry")))
                    .build()
                    .toInterceptor())
            .build();
    var responseFuture =
        client.sendAsync(MutableRequest.GET("https://example.com"), BodyHandlers.discarding());

    recordingClient.awaitCall().complete(builder -> builder.header("X-Retry", "true"));
    recordingClient.awaitCall().completeExceptionally(new TestException());

    assertThat(responseFuture)
        .isCompletedExceptionally()
        .failsWithin(Duration.ZERO)
        .withThrowableOfType(ExecutionException.class)
        .withCauseExactlyInstanceOf(TestException.class);
  }

  @Test
  void retryWithRequestModification() {
    var client =
        clientBuilder
            .interceptor(
                Methanol.Retryer.newBuilder()
                    .always(
                        retry ->
                            retry
                                .atMost(6)
                                .onStatus(
                                    HttpStatus::isServerError,
                                    context ->
                                        MutableRequest.copyOf(context.request())
                                            .header("X-Retry-Type", "onStatusPredicate"))
                                .onStatus(
                                    Set.of(400),
                                    context ->
                                        MutableRequest.copyOf(context.request())
                                            .header("X-Retry-Type", "onStatus"))
                                .onException(
                                    Set.of(TestException.class),
                                    context ->
                                        MutableRequest.copyOf(context.request())
                                            .header("X-Retry-Type", "onException"))
                                .onException(
                                    e -> e instanceof IOException,
                                    context ->
                                        MutableRequest.copyOf(context.request())
                                            .header("X-Retry-Type", "onExceptionPredicate"))
                                .onResponse(
                                    r -> r.headers().map().containsKey("X-Retry"),
                                    context ->
                                        MutableRequest.copyOf(context.request())
                                            .header("X-Retry-Type", "onResponse")))
                    .build()
                    .toInterceptor())
            .build();

    var responseFuture =
        client.sendAsync(MutableRequest.GET("https://example.com"), BodyHandlers.discarding());

    recordingClient.awaitCall().complete(builder -> builder.statusCode(500));
    var call = recordingClient.awaitCall();
    verifyThat(call.request()).containsHeader("X-Retry-Type", "onStatusPredicate");

    call.complete(builder -> builder.statusCode(400));
    call = recordingClient.awaitCall();
    verifyThat(call.request()).containsHeader("X-Retry-Type", "onStatus");

    call.complete(builder -> builder.statusCode(400));
    call = recordingClient.awaitCall();
    verifyThat(call.request()).containsHeader("X-Retry-Type", "onStatus");

    call.completeExceptionally(new TestException());
    call = recordingClient.awaitCall();
    verifyThat(call.request()).containsHeader("X-Retry-Type", "onException");

    call.completeExceptionally(new IOException());
    call = recordingClient.awaitCall();
    verifyThat(call.request()).containsHeader("X-Retry-Type", "onExceptionPredicate");

    call.complete(builder -> builder.header("X-Retry", "true"));
    call = recordingClient.awaitCall();
    verifyThat(call.request()).containsHeader("X-Retry-Type", "onResponse");

    call.complete(builder -> builder.statusCode(200));

    assertThat(responseFuture)
        .isCompleted()
        .succeedsWithin(Duration.ZERO)
        .matches(HttpStatus::isSuccessful);
  }

  @Test
  void retryWithRequestSelector() {
    var client =
        clientBuilder
            .interceptor(
                Methanol.Retryer.newBuilder()
                    .when(
                        request -> request.headers().map().containsKey("X-Retry"),
                        retry -> retry.atMost(2).onException(TestException.class))
                    .build()
                    .toInterceptor())
            .build();

    // This request is not retried.
    var responseFuture =
        client.sendAsync(MutableRequest.GET("https://example.com"), BodyHandlers.discarding());
    recordingClient.awaitCall().completeExceptionally(new TestException());
    assertThat(responseFuture)
        .isCompletedExceptionally()
        .failsWithin(Duration.ZERO)
        .withThrowableOfType(ExecutionException.class)
        .withCauseExactlyInstanceOf(TestException.class);

    // This request is not retried.
    var secondResponseFuture =
        client.sendAsync(
            MutableRequest.GET("https://example.com").header("X-Retry", "true"),
            BodyHandlers.discarding());
    recordingClient.awaitCall().completeExceptionally(new TestException());
    recordingClient.awaitCall().completeExceptionally(new TestException());
    recordingClient.awaitCall().completeExceptionally(new TestException());
    assertThat(secondResponseFuture)
        .isCompletedExceptionally()
        .failsWithin(Duration.ZERO)
        .withThrowableOfType(ExecutionException.class)
        .withCauseExactlyInstanceOf(TestException.class);
  }

  @Test
  void retryConditionOrder() {
    final class Entry {
      final Consumer<Methanol.Retryer.Retry.Builder> spec;
      final String expectedType;

      Entry(Consumer<Methanol.Retryer.Retry.Builder> spec, String expectedType) {
        this.spec = spec;
        this.expectedType = expectedType;
      }
    }

    for (var entry :
        List.of(
            new Entry(
                retry ->
                    retry
                        .atMost(1)
                        .onStatus(
                            Set.of(500),
                            context ->
                                MutableRequest.copyOf(context.request())
                                    .header("X-Retry-Type", "onStatus"))
                        .onResponse(
                            r -> r.statusCode() == 500,
                            context ->
                                MutableRequest.copyOf(context.request())
                                    .header("X-Retry-Type", "onResponse")),
                "onStatus"),
            new Entry(
                retry ->
                    retry
                        .atMost(1)
                        .onResponse(
                            r -> r.statusCode() == 500,
                            context ->
                                MutableRequest.copyOf(context.request())
                                    .header("X-Retry-Type", "onResponse"))
                        .onStatus(
                            Set.of(500),
                            context ->
                                MutableRequest.copyOf(context.request())
                                    .header("X-Retry-Type", "onStatus")),
                "onResponse"))) {
      clientBuilder = Methanol.newBuilder(recordingClient);
      var client =
          clientBuilder
              .interceptor(Methanol.Retryer.newBuilder().always(entry.spec).build().toInterceptor())
              .build();
      client.sendAsync(MutableRequest.GET("https://example.com"), BodyHandlers.discarding());

      recordingClient.awaitCall().complete(builder -> builder.statusCode(500));
      var call = recordingClient.awaitCall();
      verifyThat(call.request()).containsHeader("X-Retry-Type", entry.expectedType);
    }
  }
}
