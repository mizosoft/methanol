/*
 * Copyright (c) 2024 Moataz Abdelnasser
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

import static com.github.mizosoft.methanol.MutableRequest.GET;
import static com.github.mizosoft.methanol.MutableRequest.POST;
import static com.github.mizosoft.methanol.testing.TestUtils.deflate;
import static com.github.mizosoft.methanol.testing.TestUtils.gzip;
import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.from;

import com.github.mizosoft.methanol.Methanol.Interceptor;
import com.github.mizosoft.methanol.internal.extensions.ForwardingBodySubscriber;
import com.github.mizosoft.methanol.testing.CharSequenceEncoder;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorSpec;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.MockWebServerExtension;
import com.github.mizosoft.methanol.testing.MockWebServerExtension.UseHttps;
import com.github.mizosoft.methanol.testing.StringDecoder;
import com.github.mizosoft.methanol.testing.TestSubscriber;
import com.github.mizosoft.methanol.testing.TestSubscriberExtension;
import com.github.mizosoft.methanol.testing.TestUtils;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.PushPromise;
import mockwebserver3.RecordedRequest;
import okhttp3.Headers;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({ExecutorExtension.class, MockWebServerExtension.class, TestSubscriberExtension.class})
class MethanolClientTest {
  private MockWebServer server;
  private Methanol.Builder clientBuilder;
  private URI serverUri;

  @BeforeEach
  void setUp(MockWebServer server, Methanol.Builder clientBuilder) {
    this.server = server;
    this.clientBuilder = clientBuilder.version(Version.HTTP_1_1);
    this.serverUri = server.url("/").uri();
  }

  @Test
  void syncGet() throws Exception {
    var client =
        clientBuilder
            .userAgent("Will Smith")
            .baseUri(serverUri.resolve("root/"))
            .defaultHeader("Accept", "text/plain")
            .build();

    server.enqueue(
        new MockResponse()
            .setBody(new okio.Buffer().write(gzip("Unzip me!")))
            .setHeader("Content-Encoding", "gzip"));
    verifyThat(client.send(GET("relative?q=value"), BodyHandlers.ofString()))
        .hasCode(200)
        .hasBody("Unzip me!")
        .doesNotContainHeader("Content-Encoding")
        .doesNotContainHeader("Content-Length");

    var sentRequest = server.takeRequest();
    assertThat(sentRequest.getHeader("User-Agent")).isEqualTo("Will Smith");
    assertThat(sentRequest.getHeader("Accept-Encoding")).isEqualTo(acceptEncodingValue());
    assertThat(sentRequest.getHeader("Accept")).isEqualTo("text/plain");

    var requestUrl = sentRequest.getRequestUrl();
    assertThat(requestUrl.pathSegments()).containsExactly("root", "relative");
    assertThat(requestUrl.queryParameter("q")).isEqualTo("value");
  }

  @Test
  void asyncGet() throws Exception {
    var client =
        clientBuilder
            .userAgent("Will Smith")
            .baseUri(serverUri.resolve("root/"))
            .defaultHeader("Accept", "text/plain")
            .build();

    server.enqueue(
        new MockResponse()
            .setBody(new okio.Buffer().write(gzip("Unzip me!")))
            .setHeader("Content-Encoding", "gzip"));
    verifyThat(client.sendAsync(GET("relative?q=value"), BodyHandlers.ofString()).get())
        .hasCode(200)
        .hasBody("Unzip me!")
        .doesNotContainHeader("Content-Encoding")
        .doesNotContainHeader("Content-Length");

    var sentRequest = server.takeRequest();
    assertThat(sentRequest.getHeader("User-Agent")).isEqualTo("Will Smith");
    assertThat(sentRequest.getHeader("Accept-Encoding")).isEqualTo(acceptEncodingValue());
    assertThat(sentRequest.getHeader("Accept")).isEqualTo("text/plain");

    var requestUrl = sentRequest.getRequestUrl();
    assertThat(requestUrl.pathSegments()).containsExactly("root", "relative");
    assertThat(requestUrl.queryParameter("q")).isEqualTo("value");
  }

  @Test
  @UseHttps
  void asyncGetWithCompressedPush() throws Exception {
    var client = clientBuilder.version(Version.HTTP_2).build();

    var decompressedPaths = Set.of("/push0", "/push2");
    server.enqueue(
        new MockResponse()
            .setBody(new okio.Buffer().write(deflate("Pikachu")))
            .setHeader("Content-Encoding", "deflate")
            .withPush(
                new PushPromise(
                    "GET",
                    "/push0",
                    Headers.of(":scheme", "https"),
                    new MockResponse()
                        .setBody(new okio.Buffer().write(gzip("Pika Pika!")))
                        .setHeader("Content-Encoding", "gzip")))
            .withPush(
                new PushPromise(
                    "GET",
                    "/push1",
                    Headers.of(":scheme", "https"),
                    new MockResponse().setBody("Pika Pika!")))
            .withPush(
                new PushPromise(
                    "GET",
                    "/push2",
                    Headers.of(":scheme", "https"),
                    new MockResponse()
                        .setBody(new okio.Buffer().write(gzip("Pika Pika!")))
                        .setHeader("Content-Encoding", "gzip"))));

    var pushFutures = new ConcurrentHashMap<HttpRequest, CompletableFuture<HttpResponse<String>>>();
    verifyThat(
            client
                .sendAsync(
                    GET(serverUri),
                    BodyHandlers.ofString(),
                    PushPromiseHandler.of(__ -> BodyHandlers.ofString(), pushFutures))
                .get())
        .hasCode(200)
        .hasBody("Pikachu")
        .doesNotContainHeader("Content-Encoding")
        .doesNotContainHeader("Content-Length");

    for (var future : pushFutures.values()) {
      var response = future.get();
      if (decompressedPaths.contains(response.uri().getPath())) {
        verifyThat(response)
            .hasCode(200)
            .hasBody("Pika Pika!")
            .doesNotContainHeader("Content-Encoding")
            .doesNotContainHeader("Content-Length");
      } else {
        verifyThat(response)
            .hasCode(200)
            .hasBody("Pika Pika!")
            .containsHeader("Content-Length", "Pika Pika!".length());
      }
    }
  }

  @Test
  void getWithoutAutoAcceptEncoding() throws Exception {
    var client = clientBuilder.autoAcceptEncoding(false).build();

    var gzippedBytes = gzip("Pikachu");
    server.enqueue(
        new MockResponse()
            .setBody(new okio.Buffer().write(gzippedBytes))
            .setHeader("Content-Encoding", "gzip"));
    verifyThat(client.send(GET(serverUri), BodyHandlers.ofByteArray()))
        .hasBody(gzippedBytes)
        .containsHeader("Content-Encoding", "gzip")
        .containsHeader("Content-Length", gzippedBytes.length);

    assertThat(server.takeRequest().getHeader("Accept-Encoding")).isNull();
  }

  @Test
  void postMimeBody() throws Exception {
    var client = clientBuilder.build();

    server.enqueue(new MockResponse());

    var requestBody = FormBodyPublisher.newBuilder().query("q", "hello").build();
    client.send(POST(serverUri, requestBody), BodyHandlers.ofString());

    assertThat(server.takeRequest().getHeader("Content-Type"))
        .isEqualTo(requestBody.mediaType().toString());
  }

  @Test
  void requestTimeout() {
    var client = clientBuilder.requestTimeout(Duration.ofMillis(50)).build();

    assertThatExceptionOfType(HttpTimeoutException.class)
        .isThrownBy(() -> client.send(GET(serverUri), BodyHandlers.ofString()));

    assertThat(client.sendAsync(GET(serverUri), BodyHandlers.ofString()))
        .failsWithin(Duration.ofSeconds(TestUtils.TIMEOUT_SECONDS))
        .withThrowableOfType(ExecutionException.class)
        .withCauseInstanceOf(HttpTimeoutException.class);
  }

  @Test
  void readTimeout() {
    var client = clientBuilder.readTimeout(Duration.ofMillis(50)).build();

    server.enqueue(
        new MockResponse().setBody("Pikachu").throttleBody(1, 500, TimeUnit.MILLISECONDS));
    assertThatExceptionOfType(HttpTimeoutException.class)
        .isThrownBy(() -> client.send(GET(serverUri), BodyHandlers.ofString()));

    server.enqueue(
        new MockResponse().setBody("Pikachu").throttleBody(1, 500, TimeUnit.MILLISECONDS));
    assertThat(client.sendAsync(GET(serverUri), BodyHandlers.ofString()))
        .failsWithin(Duration.ofSeconds(TestUtils.TIMEOUT_SECONDS))
        .withThrowableOfType(ExecutionException.class)
        .withCauseInstanceOf(HttpReadTimeoutException.class);
  }

  @Test
  @ExecutorSpec(ExecutorType.SCHEDULER)
  void readTimeoutWithCustomScheduler(ScheduledExecutorService scheduler) {
    var client = clientBuilder.readTimeout(Duration.ofMillis(50), scheduler).build();

    server.enqueue(
        new MockResponse().setBody("Pikachu").throttleBody(1, 500, TimeUnit.MILLISECONDS));
    assertThatExceptionOfType(HttpTimeoutException.class)
        .isThrownBy(() -> client.send(GET(serverUri), BodyHandlers.ofString()));

    server.enqueue(
        new MockResponse().setBody("Pikachu").throttleBody(1, 500, TimeUnit.MILLISECONDS));
    assertThat(client.sendAsync(GET(serverUri), BodyHandlers.ofString()))
        .failsWithin(Duration.ofSeconds(TestUtils.TIMEOUT_SECONDS))
        .withThrowableOfType(ExecutionException.class)
        .withCauseInstanceOf(HttpReadTimeoutException.class);
  }

  @Test
  void exchange(TestSubscriber<HttpResponse<String>> subscriber) {
    var client = clientBuilder.build();
    var publisher = client.exchange(GET(serverUri), BodyHandlers.ofString());
    server.enqueue(
        new MockResponse()
            .setBody(new okio.Buffer().write(gzip("Pikachu")))
            .setHeader("Content-Encoding", "gzip"));

    publisher.subscribe(subscriber);
    subscriber.awaitCompletion();
    assertThat(subscriber.nextCount()).isOne();
    verifyThat(subscriber.pollNext())
        .hasCode(200)
        .hasBody("Pikachu")
        .doesNotContainHeader("Content-Encoding")
        .doesNotContainHeader("Content-Length");
  }

  @Test
  @UseHttps
  void exchangeWithPush(TestSubscriber<HttpResponse<String>> subscriber) {
    var client = clientBuilder.version(Version.HTTP_2).build();

    // Accept all push promises but the first.
    var rejectedFirstPush = new AtomicBoolean();
    var publisher =
        client.exchange(
            GET(serverUri),
            BodyHandlers.ofString(),
            push -> rejectedFirstPush.compareAndSet(false, true) ? null : BodyHandlers.ofString());

    int pushCount = 3;
    var decompressedPaths = Set.of("/push0", "/push2");
    server.enqueue(
        new MockResponse()
            .setBody(new okio.Buffer().write(deflate("Pikachu")))
            .setHeader("Content-Encoding", "deflate")
            .withPush(
                new PushPromise(
                    "GET",
                    "/push0",
                    Headers.of(":scheme", "https"),
                    new MockResponse()
                        .setBody(new okio.Buffer().write(gzip("Pika Pika!")))
                        .setHeader("Content-Encoding", "gzip")))
            .withPush(
                new PushPromise(
                    "GET",
                    "/push1",
                    Headers.of(":scheme", "https"),
                    new MockResponse().setBody("Pika Pika!")))
            .withPush(
                new PushPromise(
                    "GET",
                    "/push2",
                    Headers.of(":scheme", "https"),
                    new MockResponse()
                        .setBody(new okio.Buffer().write(gzip("Pika Pika!")))
                        .setHeader("Content-Encoding", "gzip"))));
    publisher.subscribe(subscriber);
    subscriber.awaitCompletion();
    assertThat(subscriber.nextCount())
        .isEqualTo(1 + (pushCount - 1)); // Main response + all accepted push promises.

    for (var response : subscriber.pollAll()) {
      var path = response.request().uri().getPath();
      if (path.startsWith("/push")) {
        assertThat(path).isNotEqualTo("/push0"); // First push promise isn't accepted.
        if (decompressedPaths.contains(path)) {
          verifyThat(response)
              .hasCode(200)
              .hasBody("Pika Pika!")
              .doesNotContainHeader("Content-Encoding")
              .doesNotContainHeader("Content-Length");
        } else {
          verifyThat(response)
              .hasCode(200)
              .hasBody("Pika Pika!")
              .containsHeader("Content-Length", "Pika Pika!".length());
        }
      } else {
        verifyThat(response)
            .hasCode(200)
            .hasBody("Pikachu")
            .doesNotContainHeader("Content-Encoding")
            .doesNotContainHeader("Content-Length");
      }
    }
  }

  @Test
  void syncRetryingWithInterceptors() throws Exception {
    int maxRetries = 3;

    var client = clientBuilder.interceptor(new RetryingInterceptor(maxRetries)).build();

    for (int i = 0; i < maxRetries; i++) {
      server.enqueue(new MockResponse().setResponseCode(HTTP_UNAVAILABLE));
    }
    server.enqueue(new MockResponse().setBody("I'm back!"));

    verifyThat(client.send(GET(serverUri), BodyHandlers.ofString()))
        .hasCode(200)
        .hasBody("I'm back!");
  }

  @Test
  void asyncRetryingWithInterceptors() throws Exception {
    int maxRetries = 3;
    for (int i = 0; i < maxRetries; i++) {
      server.enqueue(new MockResponse().setResponseCode(HTTP_UNAVAILABLE));
    }
    server.enqueue(new MockResponse().setBody("I'm back!"));

    var client = clientBuilder.interceptor(new RetryingInterceptor(maxRetries)).build();
    verifyThat(client.sendAsync(GET(serverUri), BodyHandlers.ofString()).get())
        .hasCode(200)
        .hasBody("I'm back!");
  }

  @Test
  void headOfCompressedResponse() throws Exception {
    var client = clientBuilder.build();

    var gzippedBody = gzip("Pikachu");
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Encoding", "gzip")
            .setHeader("Content-Length", gzippedBody.length));
    verifyThat(
            client.send(
                MutableRequest.create(serverUri).method("HEAD", BodyPublishers.noBody()),
                BodyHandlers.ofString()))
        .hasCode(200)
        .hasBody("")
        .containsHeader("Content-Encoding", "gzip")
        .containsHeader("Content-Length", gzippedBody.length);

    server.enqueue(
        new MockResponse()
            .setHeader("Content-Encoding", "gzip")
            .setHeader("Content-Length", gzippedBody.length)
            .setBody(new okio.Buffer().write(gzippedBody)));
    verifyThat(client.send(GET(serverUri), BodyHandlers.ofString()))
        .hasCode(200)
        .hasBody("Pikachu")
        .doesNotContainHeader("Content-Encoding")
        .doesNotContainHeader("Content-Length");
  }

  @Test
  void postStringPayload() throws Exception {
    var client =
        clientBuilder
            .adapterCodec(AdapterCodec.newBuilder().encoder(new CharSequenceEncoder()).build())
            .build();

    server.enqueue(new MockResponse());
    client.send(POST(serverUri, "Pikachu", MediaType.TEXT_PLAIN), BodyHandlers.ofString());

    assertThat(server.takeRequest())
        .returns(
            "Pikachu",
            from(recordedRequest -> recordedRequest.getBody().readString(StandardCharsets.UTF_8)))
        .returns("text/plain", from(recordedRequest -> recordedRequest.getHeader("Content-Type")));
  }

  @Test
  void getStringPayload() throws Exception {
    var client =
        clientBuilder
            .adapterCodec(AdapterCodec.newBuilder().decoder(new StringDecoder()).build())
            .build();

    server.enqueue(
        new MockResponse()
            .setBody(new okio.Buffer().writeString("Pikachu", StandardCharsets.UTF_8))
            .addHeader("Content-Type", "text/plain"));
    verifyThat(client.send(GET(serverUri), String.class)).hasBody("Pikachu");

    server.enqueue(
        new MockResponse()
            .setBody(new okio.Buffer().writeString("Pikachu", StandardCharsets.UTF_8))
            .addHeader("Content-Type", "text/plain"));
    verifyThat(client.send(GET(serverUri), TypeRef.of(String.class))).hasBody("Pikachu");

    server.enqueue(
        new MockResponse()
            .setBody(new okio.Buffer().writeString("Pikachu", StandardCharsets.UTF_8))
            .addHeader("Content-Type", "text/plain"));
    verifyThat(client.sendAsync(GET(serverUri), String.class).get()).hasBody("Pikachu");

    server.enqueue(
        new MockResponse()
            .setBody(new okio.Buffer().writeString("Pikachu", StandardCharsets.UTF_8))
            .addHeader("Content-Type", "text/plain"));
    verifyThat(client.sendAsync(GET(serverUri), TypeRef.of(String.class)).get()).hasBody("Pikachu");
  }

  @Test
  void stringPingPong() throws Exception {
    var client =
        clientBuilder
            .adapterCodec(
                AdapterCodec.newBuilder()
                    .encoder(new CharSequenceEncoder())
                    .decoder(new StringDecoder())
                    .build())
            .build();

    server.setDispatcher(
        new Dispatcher() {
          @NotNull
          @Override
          public MockResponse dispatch(@NotNull RecordedRequest recordedRequest) {
            var response = new MockResponse().setBody(recordedRequest.getBody());
            var contentType = recordedRequest.getHeader("Content-Type");
            if (contentType != null) {
              response.setHeader("Content-Type", contentType);
            }
            return response;
          }
        });

    verifyThat(client.send(POST(serverUri, "Pikachu", MediaType.TEXT_PLAIN), String.class))
        .hasBody("Pikachu");

    assertThat(server.takeRequest())
        .returns(
            "Pikachu",
            from(recordedRequest -> recordedRequest.getBody().readString(StandardCharsets.UTF_8)))
        .returns("text/plain", from(recordedRequest -> recordedRequest.getHeader("Content-Type")));
  }

  @Test
  void deferredResponsePayload() throws Exception {
    var client = clientBuilder.adapterCodec(AdapterCodec.newBuilder().basic().build()).build();

    server.enqueue(new MockResponse().setBody("Pikachu"));
    try (var body = client.send(GET(serverUri), ResponsePayload.class).body()) {
      assertThat(body.to(String.class)).isEqualTo("Pikachu");
    }

    server.enqueue(new MockResponse().setBody("Pikachu"));
    try (var body = client.send(GET(serverUri), ResponsePayload.class).body()) {
      assertThat(body.toAsync(String.class))
          .succeedsWithin(Duration.ofSeconds(TestUtils.TIMEOUT_SECONDS))
          .isEqualTo("Pikachu");
    }

    server.enqueue(new MockResponse().setBody("Pikachu"));
    try (var body = client.send(GET(serverUri), ResponsePayload.class).body()) {
      assertThat(body.to(new TypeRef<String>() {})).isEqualTo("Pikachu");
    }

    server.enqueue(new MockResponse().setBody("Pikachu"));
    try (var body = client.send(GET(serverUri), ResponsePayload.class).body()) {
      assertThat(body.toAsync(new TypeRef<String>() {}))
          .succeedsWithin(Duration.ofSeconds(TestUtils.TIMEOUT_SECONDS))
          .isEqualTo("Pikachu");
    }

    server.enqueue(new MockResponse().setBody("Pikachu"));
    try (var body = client.send(GET(serverUri), ResponsePayload.class).body()) {
      assertThat(body.handleWith(BodyHandlers.ofString())).isEqualTo("Pikachu");
    }

    server.enqueue(new MockResponse().setBody("Pikachu"));
    try (var body = client.send(GET(serverUri), ResponsePayload.class).body()) {
      assertThat(body.handleWithAsync(BodyHandlers.ofString()))
          .succeedsWithin(Duration.ofSeconds(TestUtils.TIMEOUT_SECONDS))
          .isEqualTo("Pikachu");
    }
  }

  @Test
  void deferredResponsePayloadWithSendAsync() throws Exception {
    var client = clientBuilder.adapterCodec(AdapterCodec.newBuilder().basic().build()).build();

    server.enqueue(new MockResponse().setBody("Pikachu"));
    try (var body =
        client
            .sendAsync(GET(serverUri), ResponsePayload.class)
            .get(TestUtils.TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .body()) {
      assertThat(body.to(String.class)).isEqualTo("Pikachu");
    }

    server.enqueue(new MockResponse().setBody("Pikachu"));
    try (var body =
        client
            .sendAsync(GET(serverUri), ResponsePayload.class)
            .get(TestUtils.TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .body()) {
      assertThat(body.toAsync(String.class))
          .succeedsWithin(Duration.ofSeconds(TestUtils.TIMEOUT_SECONDS))
          .isEqualTo("Pikachu");
    }

    server.enqueue(new MockResponse().setBody("Pikachu"));
    try (var body =
        client
            .sendAsync(GET(serverUri), ResponsePayload.class)
            .get(TestUtils.TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .body()) {
      assertThat(body.to(new TypeRef<String>() {})).isEqualTo("Pikachu");
    }

    server.enqueue(new MockResponse().setBody("Pikachu"));
    try (var body =
        client
            .sendAsync(GET(serverUri), ResponsePayload.class)
            .get(TestUtils.TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .body()) {
      assertThat(body.toAsync(new TypeRef<String>() {}))
          .succeedsWithin(Duration.ofSeconds(TestUtils.TIMEOUT_SECONDS))
          .isEqualTo("Pikachu");
    }

    server.enqueue(new MockResponse().setBody("Pikachu"));
    try (var body =
        client
            .sendAsync(GET(serverUri), ResponsePayload.class)
            .get(TestUtils.TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .body()) {
      assertThat(body.handleWith(BodyHandlers.ofString())).isEqualTo("Pikachu");
    }

    server.enqueue(new MockResponse().setBody("Pikachu"));
    try (var body =
        client
            .sendAsync(GET(serverUri), ResponsePayload.class)
            .get(TestUtils.TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .body()) {
      assertThat(body.handleWithAsync(BodyHandlers.ofString()))
          .succeedsWithin(Duration.ofSeconds(TestUtils.TIMEOUT_SECONDS))
          .isEqualTo("Pikachu");
    }
  }

  @Test
  void deferredBodyIsConsumedOnClosure() throws Exception {
    // CompletableFuture<Void> that completes when the body is completed.
    var bodyCompletion = new CompletableFuture<>();
    var client =
        clientBuilder
            .adapterCodec(AdapterCodec.newBuilder().basic().build())
            .backendInterceptor(
                new Interceptor() {
                  @Override
                  public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
                      throws IOException, InterruptedException {
                    return chain
                        .with(
                            bodyHandler ->
                                responseInfo ->
                                    new ForwardingBodySubscriber<>(
                                        bodyHandler.apply(responseInfo)) {
                                      @Override
                                      public void onError(Throwable throwable) {
                                        bodyCompletion.completeExceptionally(throwable);
                                      }

                                      @Override
                                      public void onComplete() {
                                        bodyCompletion.complete(null);
                                      }
                                    })
                        .forward(request);
                  }

                  @Override
                  public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
                      HttpRequest request, Chain<T> chain) {
                    throw new UnsupportedOperationException();
                  }
                })
            .build();

    server.enqueue(new MockResponse().setBody("Pikachu"));
    var response = client.send(GET(serverUri), ResponsePayload.class);
    response.body().close();
    assertThat(bodyCompletion).succeedsWithin(Duration.ofSeconds(TestUtils.TIMEOUT_SECONDS));
  }

  @Test
  void deferredBodyIsConsumedOnClosureWithSendAsync() throws Exception {
    // CompletableFuture<Void> that completes when the body is completed.
    var bodyCompletion = new CompletableFuture<>();
    var client =
        clientBuilder
            .adapterCodec(AdapterCodec.newBuilder().basic().build())
            .backendInterceptor(
                new Interceptor() {
                  @Override
                  public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain) {
                    throw new UnsupportedOperationException();
                  }

                  @Override
                  public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
                      HttpRequest request, Chain<T> chain) {
                    return chain
                        .with(
                            bodyHandler ->
                                responseInfo ->
                                    new ForwardingBodySubscriber<>(
                                        bodyHandler.apply(responseInfo)) {
                                      @Override
                                      public void onError(Throwable throwable) {
                                        bodyCompletion.completeExceptionally(throwable);
                                      }

                                      @Override
                                      public void onComplete() {
                                        bodyCompletion.complete(null);
                                      }
                                    })
                        .forwardAsync(request);
                  }
                })
            .build();

    server.enqueue(new MockResponse().setBody("Pikachu"));
    var response =
        client
            .sendAsync(GET(serverUri), ResponsePayload.class)
            .get(TestUtils.TIMEOUT_SECONDS, TimeUnit.SECONDS);
    response.body().close();
    assertThat(bodyCompletion).succeedsWithin(Duration.ofSeconds(TestUtils.TIMEOUT_SECONDS));
  }

  private static String acceptEncodingValue() {
    return String.join(", ", BodyDecoder.Factory.installedBindings().keySet());
  }

  private static final class RetryingInterceptor implements Interceptor {
    private final int maxRetryCount;

    RetryingInterceptor(int maxRetryCount) {
      this.maxRetryCount = maxRetryCount;
    }

    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
        throws IOException, InterruptedException {
      var response = chain.forward(request);
      for (int retries = 0;
          response.statusCode() == HTTP_UNAVAILABLE && retries < maxRetryCount;
          retries++) {
        response = chain.forward(request);
      }
      return response;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
        HttpRequest request, Chain<T> chain) {
      var responseFuture = chain.forwardAsync(request);
      for (int i = 0; i < maxRetryCount; i++) {
        final int j = i;
        responseFuture =
            responseFuture.thenCompose(
                res -> handleRetry(res, () -> chain.forwardAsync(request), j));
      }
      return responseFuture;
    }

    private <R> CompletableFuture<HttpResponse<R>> handleRetry(
        HttpResponse<R> response,
        Supplier<CompletableFuture<HttpResponse<R>>> callOnRetry,
        int retryCount) {
      return response.statusCode() == HTTP_UNAVAILABLE && retryCount < maxRetryCount
          ? callOnRetry.get()
          : CompletableFuture.completedFuture(response);
    }
  }
}
