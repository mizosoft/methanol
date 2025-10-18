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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

import com.github.mizosoft.methanol.testing.MockWebServerExtension;
import com.github.mizosoft.methanol.testing.TestUtils;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockWebServerExtension.class)
class RetryClientTest {
  private MockWebServer server;
  private URI serverUri;

  @BeforeEach
  void setUp(MockWebServer server) {
    this.server = server;
    this.serverUri = server.url("/").uri();
  }

  @Test
  void retryOnException() throws Exception {
    var client =
        Methanol.newBuilder()
            .interceptor(
                RetryInterceptor.newBuilder().maxRetries(1).onException(IOException.class).build())
            .build();

    server.enqueue(
        new MockResponse.Builder()
            .body(new okio.Buffer().write(new byte[128]))
            .addHeader("Content-Encoding", "gzip")
            .build());
    server.enqueue(
        new MockResponse.Builder()
            .body(new okio.Buffer().write(TestUtils.gzip("Pikachu")))
            .addHeader("Content-Encoding", "gzip")
            .build());

    var response = client.send(MutableRequest.GET(serverUri), BodyHandlers.ofString());
    verifyThat(response).hasBody("Pikachu");
  }

  @Test
  void retryOnExceptionWithRequestModification() throws Exception {
    var client =
        Methanol.newBuilder()
            .interceptor(
                RetryInterceptor.newBuilder()
                    .maxRetries(1)
                    .onException(
                        Set.of(IOException.class),
                        context ->
                            MutableRequest.copyOf(context.request()).header("X-Retry", "true"))
                    .build())
            .build();

    server.enqueue(
        new MockResponse.Builder()
            .body(new okio.Buffer().write(new byte[128]))
            .addHeader("Content-Encoding", "gzip")
            .build());
    server.enqueue(
        new MockResponse.Builder()
            .body(new okio.Buffer().write(TestUtils.gzip("Pikachu")))
            .addHeader("Content-Encoding", "gzip")
            .build());

    var response = client.send(MutableRequest.GET(serverUri), BodyHandlers.ofString());
    verifyThat(response).hasBody("Pikachu");

    server.takeRequest(); // Skip first request.
    assertThat(server.takeRequest().getHeaders().get("X-Retry")).isEqualTo("true");
  }

  @Test
  void retryOnExceptionPredicate() throws Exception {
    var client =
        Methanol.newBuilder()
            .interceptor(
                RetryInterceptor.newBuilder().maxRetries(1).onException(IOException.class).build())
            .build();

    server.enqueue(
        new MockResponse.Builder()
            .body(new okio.Buffer().write(new byte[128]))
            .addHeader("Content-Encoding", "gzip")
            .build());
    server.enqueue(
        new MockResponse.Builder()
            .body(new okio.Buffer().write(TestUtils.gzip("Pikachu")))
            .addHeader("Content-Encoding", "gzip")
            .build());

    var response = client.send(MutableRequest.GET(serverUri), BodyHandlers.ofString());
    verifyThat(response).hasBody("Pikachu");
  }

  @Test
  void retryOnExceptionPredicateWithRequestModification() throws Exception {
    var client =
        Methanol.newBuilder()
            .interceptor(
                RetryInterceptor.newBuilder()
                    .maxRetries(1)
                    .onException(
                        Set.of(IOException.class),
                        context ->
                            MutableRequest.copyOf(context.request()).header("X-Retry", "true"))
                    .build())
            .build();

    server.enqueue(
        new MockResponse.Builder()
            .body(new okio.Buffer().write(new byte[128]))
            .addHeader("Content-Encoding", "gzip")
            .build());
    server.enqueue(
        new MockResponse.Builder()
            .body(new okio.Buffer().write(TestUtils.gzip("Pikachu")))
            .addHeader("Content-Encoding", "gzip")
            .build());

    var response = client.send(MutableRequest.GET(serverUri), BodyHandlers.ofString());
    verifyThat(response).hasBody("Pikachu");

    server.takeRequest(); // Skip first request.
    assertThat(server.takeRequest().getHeaders().get("X-Retry")).isEqualTo("true");
  }

  @Test
  void retryOnStatus() throws Exception {
    var client =
        Methanol.newBuilder()
            .interceptor(RetryInterceptor.newBuilder().maxRetries(1).onStatus(500).build())
            .build();

    server.enqueue(new MockResponse.Builder().code(500).build());
    server.enqueue(new MockResponse.Builder().code(500).body("Pikachu").build());

    var response = client.send(MutableRequest.GET(serverUri), BodyHandlers.ofString());
    verifyThat(response).hasBody("Pikachu");
  }

  @Test
  void retryOnStatusWithRequestModification() throws Exception {
    var client =
        Methanol.newBuilder()
            .interceptor(
                RetryInterceptor.newBuilder()
                    .maxRetries(1)
                    .onStatus(
                        Set.of(500),
                        context ->
                            MutableRequest.copyOf(context.request()).header("X-Retry", "true"))
                    .build())
            .build();

    server.enqueue(new MockResponse.Builder().code(500).build());
    server.enqueue(new MockResponse.Builder().code(500).body("Pikachu").build());

    var response = client.send(MutableRequest.GET(serverUri), BodyHandlers.ofString());
    verifyThat(response).hasBody("Pikachu");

    server.takeRequest(); // Skip first request.
    assertThat(server.takeRequest().getHeaders().get("X-Retry")).isEqualTo("true");
  }

  @Test
  void retryOnStatusPredicate() throws Exception {
    var client =
        Methanol.newBuilder()
            .interceptor(RetryInterceptor.newBuilder().maxRetries(1).onStatus(500).build())
            .build();

    server.enqueue(new MockResponse.Builder().code(500).build());
    server.enqueue(new MockResponse.Builder().code(500).body("Pikachu").build());

    var response = client.send(MutableRequest.GET(serverUri), BodyHandlers.ofString());
    verifyThat(response).hasBody("Pikachu");
  }

  @Test
  void retryOnStatusPredicateWithRequestModification() throws Exception {
    var client =
        Methanol.newBuilder()
            .interceptor(
                RetryInterceptor.newBuilder()
                    .maxRetries(1)
                    .onStatus(
                        Set.of(500),
                        context ->
                            MutableRequest.copyOf(context.request()).header("X-Retry", "true"))
                    .build())
            .build();

    server.enqueue(new MockResponse.Builder().code(500).build());
    server.enqueue(new MockResponse.Builder().code(500).body("Pikachu").build());

    var response = client.send(MutableRequest.GET(serverUri), BodyHandlers.ofString());
    verifyThat(response).hasBody("Pikachu");

    server.takeRequest(); // Skip first request.
    assertThat(server.takeRequest().getHeaders().get("X-Retry")).isEqualTo("true");
  }

  @Test
  void onContext() throws Exception {
    var client =
        Methanol.newBuilder()
            .interceptor(
                RetryInterceptor.newBuilder()
                    .maxRetries(1)
                    .on(ctx -> ctx.response().map(HttpStatus::isServerError).orElse(false))
                    .build())
            .build();

    server.enqueue(new MockResponse.Builder().code(500).build());
    server.enqueue(new MockResponse.Builder().code(500).body("Pikachu").build());

    var response = client.send(MutableRequest.GET(serverUri), BodyHandlers.ofString());
    verifyThat(response).hasBody("Pikachu");
  }

  @Test
  void onContextWithRequestModification() throws Exception {
    var client =
        Methanol.newBuilder()
            .interceptor(
                RetryInterceptor.newBuilder()
                    .maxRetries(1)
                    .on(
                        ctx -> ctx.response().map(HttpStatus::isServerError).orElse(false),
                        context ->
                            MutableRequest.copyOf(context.request()).header("X-Retry", "true"))
                    .build())
            .build();

    server.enqueue(new MockResponse.Builder().code(500).build());
    server.enqueue(new MockResponse.Builder().code(500).body("Pikachu").build());

    var response = client.send(MutableRequest.GET(serverUri), BodyHandlers.ofString());
    verifyThat(response).hasBody("Pikachu");

    server.takeRequest(); // Skip first request.
    assertThat(server.takeRequest().getHeaders().get("X-Retry")).isEqualTo("true");
  }

  @Test
  void exhaustRetries() {
    var client =
        Methanol.newBuilder()
            .interceptor(
                RetryInterceptor.newBuilder()
                    .maxRetries(1)
                    .on(
                        ctx -> ctx.response().map(HttpStatus::isServerError).orElse(false),
                        context ->
                            MutableRequest.copyOf(context.request()).header("X-Retry", "true"))
                    .throwOnExhaustion()
                    .build())
            .build();

    server.enqueue(new MockResponse.Builder().code(500).build());
    server.enqueue(new MockResponse.Builder().code(500).body("Pikachu").build());

    assertThatThrownBy(() -> client.send(MutableRequest.GET(serverUri), BodyHandlers.ofString()))
        .isInstanceOf(HttpRetriesExhaustedException.class);
  }

  private interface RetryEvent {}

  private static final class FirstAttempt implements RetryEvent {
    final HttpRequest request;

    FirstAttempt(HttpRequest request) {
      this.request = request;
    }
  }

  private static final class Retry implements RetryEvent {
    final RetryInterceptor.Context<?> context;
    final HttpRequest nextRequest;
    final Duration delay;

    Retry(RetryInterceptor.Context<?> context, HttpRequest nextRequest, Duration delay) {
      this.context = context;
      this.nextRequest = nextRequest;
      this.delay = delay;
    }
  }

  private static final class Timeout implements RetryEvent {
    final RetryInterceptor.Context<?> context;

    Timeout(RetryInterceptor.Context<?> context) {
      this.context = context;
    }
  }

  private static final class Complete implements RetryEvent {
    final RetryInterceptor.Context<?> context;

    Complete(RetryInterceptor.Context<?> context) {
      this.context = context;
    }
  }

  private static final class Exhaustion implements RetryEvent {
    final RetryInterceptor.Context<?> context;

    Exhaustion(RetryInterceptor.Context<?> context) {
      this.context = context;
    }
  }

  @Test
  void retryTimeout() {
    var client =
        Methanol.newBuilder()
            .interceptor(
                RetryInterceptor.newBuilder()
                    .maxRetries(1)
                    .on(
                        ctx -> ctx.response().map(HttpStatus::isServerError).orElse(false),
                        context ->
                            MutableRequest.copyOf(context.request()).header("X-Retry", "true"))
                    .throwOnExhaustion()
                    .timeout(Duration.ofNanos(1))
                    .build())
            .build();

    server.enqueue(new MockResponse.Builder().code(500).build());
    server.enqueue(new MockResponse.Builder().code(500).build());

    assertThatThrownBy(() -> client.send(MutableRequest.GET(serverUri), BodyHandlers.ofString()))
        .isInstanceOf(HttpRetryTimeoutException.class);
  }

  static RetryInterceptor.Listener listenerFor(List<RetryEvent> events) {
    return new RetryInterceptor.Listener() {
      @Override
      public void onFirstAttempt(HttpRequest request) {
        events.add(new FirstAttempt(request));
      }

      @Override
      public void onRetry(
          RetryInterceptor.Context<?> context, HttpRequest nextRequest, Duration delay) {
        events.add(new Retry(context, nextRequest, delay));
      }

      @Override
      public void onComplete(RetryInterceptor.Context<?> context) {
        events.add(new Complete(context));
      }

      @Override
      public void onTimeout(RetryInterceptor.Context<?> context) {
        events.add(new Timeout(context));
      }

      @Override
      public void onExhaustion(RetryInterceptor.Context<?> context) {
        events.add(new Exhaustion(context));
      }
    };
  }

  @Test
  void retryListenerCompletion() throws Exception {
    var events = new ArrayList<RetryEvent>();
    var client =
        Methanol.newBuilder()
            .interceptor(
                RetryInterceptor.newBuilder()
                    .maxRetries(1)
                    .onStatus(
                        Set.of(500),
                        context ->
                            MutableRequest.copyOf(context.request()).header("X-Retry", "true"))
                    .listener(listenerFor(events))
                    .build())
            .build();

    server.enqueue(new MockResponse.Builder().code(500).build());
    server.enqueue(new MockResponse.Builder().code(500).build());

    client.send(MutableRequest.GET(serverUri), BodyHandlers.ofString());

    assertThat(events)
        .first()
        .asInstanceOf(type(FirstAttempt.class))
        .satisfies(firstAttempt -> verifyThat(firstAttempt.request).hasUri(serverUri));
    assertThat(events)
        .element(1)
        .asInstanceOf(type(Retry.class))
        .satisfies(
            retry -> {
              verifyThat(retry.context.request()).hasUri(serverUri);
              verifyThat(retry.nextRequest)
                  .hasUri(serverUri)
                  .containsHeaderExactly("X-Retry", "true");
              assertThat(retry.delay).isEqualTo(Duration.ZERO);
            });
    assertThat(events)
        .element(2)
        .asInstanceOf(type(Complete.class))
        .satisfies(complete -> verifyThat(complete.context.request()).hasUri(serverUri));
  }

  @Test
  void retryListenerExhaustion() {
    var events = new ArrayList<RetryEvent>();
    var client =
        Methanol.newBuilder()
            .interceptor(
                RetryInterceptor.newBuilder()
                    .maxRetries(1)
                    .onStatus(
                        Set.of(500),
                        context ->
                            MutableRequest.copyOf(context.request()).header("X-Retry", "true"))
                    .listener(listenerFor(events))
                    .throwOnExhaustion()
                    .build())
            .build();

    server.enqueue(new MockResponse.Builder().code(500).build());
    server.enqueue(new MockResponse.Builder().code(500).build());

    assertThatThrownBy(() -> client.send(MutableRequest.GET(serverUri), BodyHandlers.ofString()))
        .isInstanceOf(HttpRetriesExhaustedException.class);

    assertThat(events)
        .first()
        .asInstanceOf(type(FirstAttempt.class))
        .satisfies(firstAttempt -> verifyThat(firstAttempt.request).hasUri(serverUri));
    assertThat(events)
        .element(1)
        .asInstanceOf(type(Retry.class))
        .satisfies(
            retry -> {
              verifyThat(retry.context.request()).hasUri(serverUri);
              verifyThat(retry.nextRequest)
                  .hasUri(serverUri)
                  .containsHeaderExactly("X-Retry", "true");
              assertThat(retry.delay).isEqualTo(Duration.ZERO);
            });
    assertThat(events)
        .element(2)
        .asInstanceOf(type(Exhaustion.class))
        .satisfies(complete -> verifyThat(complete.context.request()).hasUri(serverUri));
  }

  @Test
  void retryListenerTimeout() {
    var events = new ArrayList<RetryEvent>();
    var client =
        Methanol.newBuilder()
            .interceptor(
                RetryInterceptor.newBuilder()
                    .maxRetries(1)
                    .onStatus(
                        Set.of(500),
                        context ->
                            MutableRequest.copyOf(context.request()).header("X-Retry", "true"))
                    .listener(listenerFor(events))
                    .timeout(Duration.ofSeconds(TestUtils.TIMEOUT_SECONDS))
                    .backoff(RetryInterceptor.BackoffStrategy.fixed(Duration.ofSeconds(1000)))
                    .build())
            .build();

    server.enqueue(new MockResponse.Builder().code(500).build());
    server.enqueue(new MockResponse.Builder().code(500).build());

    assertThatThrownBy(() -> client.send(MutableRequest.GET(serverUri), BodyHandlers.ofString()))
        .isInstanceOf(HttpRetryTimeoutException.class);

    assertThat(events)
        .first()
        .asInstanceOf(type(FirstAttempt.class))
        .satisfies(firstAttempt -> verifyThat(firstAttempt.request).hasUri(serverUri));
    assertThat(events)
        .element(1)
        .asInstanceOf(type(Retry.class))
        .satisfies(
            retry -> {
              verifyThat(retry.context.request()).hasUri(serverUri);
              verifyThat(retry.nextRequest)
                  .hasUri(serverUri)
                  .containsHeaderExactly("X-Retry", "true");
              assertThat(retry.delay).isEqualTo(Duration.ofSeconds(1000));
            });
    assertThat(events)
        .element(2)
        .asInstanceOf(type(Timeout.class))
        .satisfies(complete -> verifyThat(complete.context.request()).hasUri(serverUri));
  }
}
