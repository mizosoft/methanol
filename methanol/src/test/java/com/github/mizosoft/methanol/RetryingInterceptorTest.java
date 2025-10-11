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
import static org.assertj.core.api.Assertions.from;

import com.github.mizosoft.methanol.internal.function.Unchecked;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorSpec;
import com.github.mizosoft.methanol.testing.MockClock;
import com.github.mizosoft.methanol.testing.MockDelayer;
import com.github.mizosoft.methanol.testing.RecordingHttpClient;
import com.github.mizosoft.methanol.testing.TestException;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(1)
@ExtendWith(ExecutorExtension.class)
class RetryingInterceptorTest {
  private Executor executor;

  @BeforeEach
  @ExecutorSpec(ExecutorExtension.ExecutorType.CACHED_POOL)
  void setUp(Executor executor) {
    this.executor = executor;
  }

  CompletableFuture<HttpResponse<Void>> send(Methanol client, HttpRequest request, boolean async) {
    return async
        ? client.sendAsync(request, BodyHandlers.discarding())
        : Unchecked.supplyAsync(() -> client.send(request, BodyHandlers.discarding()), executor);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void retryOnException(boolean async) {
    var recordingClient = new RecordingHttpClient();
    var client =
        Methanol.newBuilder(recordingClient)
            .interceptor(
                RetryingInterceptor.newBuilder()
                    .maxRetries(2)
                    .onException(TestException.class)
                    .build())
            .build();
    var responseFuture = send(client, MutableRequest.GET("https://example.com"), async);

    recordingClient.awaitCall().completeExceptionally(new TestException());
    recordingClient.awaitCall().completeExceptionally(new TestException());
    recordingClient.awaitCall().completeExceptionally(new TestException());

    assertThat(responseFuture)
        .failsWithin(Duration.ofSeconds(1))
        .withThrowableOfType(ExecutionException.class)
        .withCauseExactlyInstanceOf(TestException.class);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void retryOnExceptionEndingWithDifferentException(boolean async) {
    var recordingClient = new RecordingHttpClient();
    var client =
        Methanol.newBuilder(recordingClient)
            .interceptor(
                RetryingInterceptor.newBuilder()
                    .maxRetries(2)
                    .onException(TestException.class)
                    .build())
            .build();
    var responseFuture = send(client, MutableRequest.GET("https://example.com"), async);

    recordingClient.awaitCall().completeExceptionally(new TestException());
    recordingClient.awaitCall().completeExceptionally(new IOException()); // No retry.

    assertThat(responseFuture)
        .failsWithin(Duration.ofSeconds(1))
        .withThrowableOfType(ExecutionException.class)
        .withCauseExactlyInstanceOf(IOException.class);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void retryOnExceptionEndingWithSuccess(boolean async) {
    var recordingClient = new RecordingHttpClient();
    var client =
        Methanol.newBuilder(recordingClient)
            .interceptor(
                RetryingInterceptor.newBuilder()
                    .maxRetries(2)
                    .onException(TestException.class)
                    .build())
            .build();
    var responseFuture = send(client, MutableRequest.GET("https://example.com"), async);

    recordingClient.awaitCall().completeExceptionally(new TestException());
    recordingClient.awaitCall().complete();

    assertThat(responseFuture).succeedsWithin(Duration.ofSeconds(1));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void retryOnExceptionPredicate(boolean async) {
    var recordingClient = new RecordingHttpClient();
    var client =
        Methanol.newBuilder(recordingClient)
            .interceptor(
                RetryingInterceptor.newBuilder()
                    .maxRetries(2)
                    .onException(e -> e instanceof TestException || e instanceof IOException)
                    .build())
            .build();
    var responseFuture = send(client, MutableRequest.GET("https://example.com"), async);

    recordingClient.awaitCall().completeExceptionally(new TestException());
    recordingClient.awaitCall().completeExceptionally(new IOException());
    recordingClient.awaitCall().completeExceptionally(new TestException());

    assertThat(responseFuture)
        .failsWithin(Duration.ofSeconds(1))
        .withThrowableOfType(ExecutionException.class)
        .withCauseExactlyInstanceOf(TestException.class);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void retryOnExceptionPredicateEndingWithDifferentException(boolean async) {
    var recordingClient = new RecordingHttpClient();
    var client =
        Methanol.newBuilder(recordingClient)
            .interceptor(
                RetryingInterceptor.newBuilder()
                    .maxRetries(2)
                    .onException(e -> e instanceof TestException || e instanceof IOException)
                    .build())
            .build();
    var responseFuture = send(client, MutableRequest.GET("https://example.com"), async);

    recordingClient.awaitCall().completeExceptionally(new TestException());
    recordingClient.awaitCall().completeExceptionally(new IllegalStateException());

    assertThat(responseFuture)
        .failsWithin(Duration.ofSeconds(1))
        .withThrowableOfType(ExecutionException.class)
        .withCauseExactlyInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void retryOnExceptionPredicateEndingWithSuccess(boolean async) {
    var recordingClient = new RecordingHttpClient();
    var client =
        Methanol.newBuilder(recordingClient)
            .interceptor(
                RetryingInterceptor.newBuilder()
                    .maxRetries(2)
                    .onException(e -> e instanceof TestException || e instanceof IOException)
                    .build())
            .build();
    var responseFuture = send(client, MutableRequest.GET("https://example.com"), async);

    recordingClient.awaitCall().completeExceptionally(new TestException());
    recordingClient.awaitCall().complete();

    assertThat(responseFuture).succeedsWithin(Duration.ofSeconds(1));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void retryOnStatus(boolean async) {
    var recordingClient = new RecordingHttpClient();
    var client =
        Methanol.newBuilder(recordingClient)
            .interceptor(RetryingInterceptor.newBuilder().maxRetries(2).onStatus(500).build())
            .build();
    var responseFuture = send(client, MutableRequest.GET("https://example.com"), async);

    recordingClient.awaitCall().complete(builder -> builder.statusCode(500));
    recordingClient.awaitCall().complete(builder -> builder.statusCode(500));
    recordingClient.awaitCall().complete(builder -> builder.statusCode(500));

    assertThat(responseFuture)
        .succeedsWithin(Duration.ofSeconds(1))
        .matches(r -> r.statusCode() == 500);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void retryOnStatusEndingWithDifferentStatus(boolean async) {
    var recordingClient = new RecordingHttpClient();
    var client =
        Methanol.newBuilder(recordingClient)
            .interceptor(RetryingInterceptor.newBuilder().maxRetries(2).onStatus(500).build())
            .build();
    var responseFuture = send(client, MutableRequest.GET("https://example.com"), async);

    recordingClient.awaitCall().complete(builder -> builder.statusCode(500));
    recordingClient.awaitCall().complete(builder -> builder.statusCode(200));

    assertThat(responseFuture)
        .succeedsWithin(Duration.ofSeconds(1))
        .matches(r -> r.statusCode() == 200);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void retryOnStatusEndingWithException(boolean async) {
    var recordingClient = new RecordingHttpClient();
    var client =
        Methanol.newBuilder(recordingClient)
            .interceptor(RetryingInterceptor.newBuilder().maxRetries(2).onStatus(500).build())
            .build();
    var responseFuture = send(client, MutableRequest.GET("https://example.com"), async);

    recordingClient.awaitCall().complete(builder -> builder.statusCode(500));
    recordingClient.awaitCall().completeExceptionally(new TestException());

    assertThat(responseFuture)
        .failsWithin(Duration.ofSeconds(1))
        .withThrowableOfType(ExecutionException.class)
        .withCauseExactlyInstanceOf(TestException.class);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void retryOnStatusPredicate(boolean async) {
    var recordingClient = new RecordingHttpClient();
    var client =
        Methanol.newBuilder(recordingClient)
            .interceptor(
                RetryingInterceptor.newBuilder()
                    .maxRetries(2)
                    .onStatus(HttpStatus::isServerError)
                    .build())
            .build();
    var responseFuture = send(client, MutableRequest.GET("https://example.com"), async);

    recordingClient.awaitCall().complete(builder -> builder.statusCode(500));
    recordingClient.awaitCall().complete(builder -> builder.statusCode(501));
    recordingClient.awaitCall().complete(builder -> builder.statusCode(502));

    assertThat(responseFuture)
        .succeedsWithin(Duration.ofSeconds(1))
        .matches(r -> r.statusCode() == 502);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void retryOnStatusPredicateEndingWithDifferentStatus(boolean async) {
    var recordingClient = new RecordingHttpClient();
    var client =
        Methanol.newBuilder(recordingClient)
            .interceptor(
                RetryingInterceptor.newBuilder()
                    .maxRetries(2)
                    .onStatus(HttpStatus::isServerError)
                    .build())
            .build();
    var responseFuture = send(client, MutableRequest.GET("https://example.com"), async);

    recordingClient.awaitCall().complete(builder -> builder.statusCode(500));
    recordingClient.awaitCall().complete(builder -> builder.statusCode(404));

    assertThat(responseFuture)
        .succeedsWithin(Duration.ofSeconds(1))
        .matches(r -> r.statusCode() == 404);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void retryOnStatusPredicateEndingWithException(boolean async) {
    var recordingClient = new RecordingHttpClient();
    var client =
        Methanol.newBuilder(recordingClient)
            .interceptor(
                RetryingInterceptor.newBuilder()
                    .maxRetries(2)
                    .onStatus(HttpStatus::isServerError)
                    .build())
            .build();
    var responseFuture = send(client, MutableRequest.GET("https://example.com"), async);

    recordingClient.awaitCall().complete(builder -> builder.statusCode(500));
    recordingClient.awaitCall().completeExceptionally(new TestException());

    assertThat(responseFuture)
        .failsWithin(Duration.ofSeconds(1))
        .withThrowableOfType(ExecutionException.class)
        .withCauseExactlyInstanceOf(TestException.class);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void retryOnResponsePredicate(boolean async) {
    var recordingClient = new RecordingHttpClient();
    var client =
        Methanol.newBuilder(recordingClient)
            .interceptor(
                RetryingInterceptor.newBuilder()
                    .maxRetries(2)
                    .onResponse(r -> r.headers().map().containsKey("X-Retry"))
                    .build())
            .build();
    var responseFuture = send(client, MutableRequest.GET("https://example.com"), async);

    recordingClient.awaitCall().complete(builder -> builder.header("X-Retry", "true"));
    recordingClient.awaitCall().complete(builder -> builder.header("X-Retry", "true"));
    recordingClient.awaitCall().complete(builder -> builder.header("X-Retry", "true"));

    assertThat(responseFuture)
        .succeedsWithin(Duration.ofSeconds(1))
        .matches(r -> r.headers().map().containsKey("X-Retry"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void retryOnResponsePredicateEndingWithDifferentResponse(boolean async) {
    var recordingClient = new RecordingHttpClient();
    var client =
        Methanol.newBuilder(recordingClient)
            .interceptor(
                RetryingInterceptor.newBuilder()
                    .maxRetries(2)
                    .onResponse(r -> r.headers().map().containsKey("X-Retry"))
                    .build())
            .build();

    var responseFuture = send(client, MutableRequest.GET("https://example.com"), async);

    recordingClient.awaitCall().complete(builder -> builder.header("X-Retry", "true"));
    recordingClient.awaitCall().complete();

    assertThat(responseFuture)
        .succeedsWithin(Duration.ofSeconds(1))
        .matches(r -> !r.headers().map().containsKey("X-Retry"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void retryOnResponsePredicateEndingWithException(boolean async) {
    var recordingClient = new RecordingHttpClient();
    var client =
        Methanol.newBuilder(recordingClient)
            .interceptor(
                RetryingInterceptor.newBuilder()
                    .maxRetries(2)
                    .onResponse(r -> r.headers().map().containsKey("X-Retry"))
                    .build())
            .build();
    var responseFuture = send(client, MutableRequest.GET("https://example.com"), async);

    recordingClient.awaitCall().complete(builder -> builder.header("X-Retry", "true"));
    recordingClient.awaitCall().completeExceptionally(new TestException());

    assertThat(responseFuture)
        .failsWithin(Duration.ofSeconds(1))
        .withThrowableOfType(ExecutionException.class)
        .withCauseExactlyInstanceOf(TestException.class);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void retryWithRequestModification(boolean async) {
    var recordingClient = new RecordingHttpClient();
    var client =
        Methanol.newBuilder(recordingClient)
            .interceptor(
                RetryingInterceptor.newBuilder()
                    .maxRetries(6)
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
                                .header("X-Retry-Type", "onResponse"))
                    .build())
            .build();

    var responseFuture = send(client, MutableRequest.GET("https://example.com"), async);

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
        .succeedsWithin(Duration.ofSeconds(1))
        .matches(HttpStatus::isSuccessful);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void retryWithRequestSelector(boolean async) {
    var recordingClient = new RecordingHttpClient();
    var client =
        Methanol.newBuilder(recordingClient)
            .interceptor(
                RetryingInterceptor.newBuilder()
                    .maxRetries(2)
                    .onException(TestException.class)
                    .build(request -> request.headers().map().containsKey("X-Retry")))
            .build();

    // This request is not retried.
    var responseFuture = send(client, MutableRequest.GET("https://example.com"), async);
    recordingClient.awaitCall().completeExceptionally(new TestException());
    assertThat(responseFuture)
        .failsWithin(Duration.ofSeconds(1))
        .withThrowableOfType(ExecutionException.class)
        .withCauseExactlyInstanceOf(TestException.class);

    // This request is not retried.
    var secondResponseFuture =
        send(client, MutableRequest.GET("https://example.com").header("X-Retry", "true"), async);
    recordingClient.awaitCall().completeExceptionally(new TestException());
    recordingClient.awaitCall().completeExceptionally(new TestException());
    recordingClient.awaitCall().completeExceptionally(new TestException());
    assertThat(secondResponseFuture)
        .failsWithin(Duration.ofSeconds(1))
        .withThrowableOfType(ExecutionException.class)
        .withCauseExactlyInstanceOf(TestException.class);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void retryConditionOrder(boolean async) {
    final class Entry {
      final Consumer<RetryingInterceptor.Builder> spec;
      final String expectedType;

      Entry(Consumer<RetryingInterceptor.Builder> spec, String expectedType) {
        this.spec = spec;
        this.expectedType = expectedType;
      }
    }

    for (var entry :
        List.of(
            new Entry(
                retry ->
                    retry
                        .maxRetries(1)
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
                        .maxRetries(1)
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
      var recordingClient = new RecordingHttpClient();
      var clientBuilder = Methanol.newBuilder(recordingClient);
      var retryingInterceptorBuilder = RetryingInterceptor.newBuilder();
      entry.spec.accept(retryingInterceptorBuilder);
      var client = clientBuilder.interceptor(retryingInterceptorBuilder.build()).build();

      send(client, MutableRequest.GET("https://example.com"), async);

      recordingClient.awaitCall().complete(builder -> builder.statusCode(500));

      var call = recordingClient.awaitCall();
      verifyThat(call.request()).containsHeader("X-Retry-Type", entry.expectedType);
      call.complete();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void modifyRequestOnFirstCall(boolean async) {
    var recordingClient = new RecordingHttpClient();
    var client =
        Methanol.newBuilder(recordingClient)
            .interceptor(
                RetryingInterceptor.newBuilder()
                    .maxRetries(1)
                    .beginWith(
                        request ->
                            MutableRequest.copyOf(request).header("X-First-Call", "true").build())
                    .build())
            .build();

    send(client, MutableRequest.GET("https://example.com"), async);
    var call = recordingClient.awaitCall();
    verifyThat(call.request()).containsHeader("X-First-Call", "true");
    call.complete();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void retryWithBackoff(boolean async) throws InterruptedException {
    var clock = new MockClock();
    var delayer = new MockDelayer(clock);
    var recordingClient = new RecordingHttpClient();
    var client =
        Methanol.newBuilder(recordingClient)
            .interceptor(
                RetryingInterceptor.newBuilder()
                    .maxRetries(3)
                    .onStatus(500)
                    .backoff(
                        RetryingInterceptor.BackoffStrategy.linear(
                            Duration.ofSeconds(1), Duration.ofSeconds(10)))
                    .delayer(delayer)
                    .build())
            .build();
    var responseFuture = send(client, MutableRequest.GET("https://example.com"), async);

    // First retry.
    recordingClient.awaitCall().complete(builder -> builder.statusCode(500));
    assertThat(delayer.awaitingPeekLatestFuture().delay()).isEqualTo(Duration.ofSeconds(1));

    clock.advance(Duration.ofSeconds(1));

    // Second retry.
    recordingClient.awaitCall().complete(builder -> builder.statusCode(500));
    assertThat(delayer.awaitingPeekLatestFuture().delay()).isEqualTo(Duration.ofSeconds(2));

    clock.advance(Duration.ofSeconds(2));

    // Third retry.
    recordingClient.awaitCall().complete(builder -> builder.statusCode(500));
    assertThat(delayer.awaitingPeekLatestFuture().delay()).isEqualTo(Duration.ofSeconds(3));

    clock.advance(Duration.ofSeconds(3));

    recordingClient.awaitCall().complete(builder -> builder.statusCode(500));
    assertThat(responseFuture)
        .succeedsWithin(Duration.ofSeconds(1))
        .returns(500, from(HttpResponse::statusCode));
  }
}
