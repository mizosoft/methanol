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

import static com.github.mizosoft.methanol.MutableRequest.GET;
import static com.github.mizosoft.methanol.MutableRequest.POST;
import static com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType.SCHEDULER;
import static com.github.mizosoft.methanol.testutils.TestUtils.deflate;
import static com.github.mizosoft.methanol.testutils.TestUtils.gzip;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.from;

import com.github.mizosoft.methanol.Methanol.Interceptor;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorConfig;
import com.github.mizosoft.methanol.testing.MockWebServerExtension;
import com.github.mizosoft.methanol.testing.MockWebServerExtension.UseHttps;
import com.github.mizosoft.methanol.testutils.TestSubscriber;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.PushPromise;
import okhttp3.Headers;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@Timeout(value = 2, unit = TimeUnit.MINUTES)
@ExtendWith({ExecutorExtension.class, MockWebServerExtension.class})
class MethanolClientTest {
  static {
    Assertions.setMaxStackTraceElementsDisplayed(100);
  }

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
    server.enqueue(new MockResponse()
        .setBody(new okio.Buffer().write(gzip("unzip me!")))
        .addHeader("Content-Encoding", "gzip"));

    var client = clientBuilder
        .userAgent("Will Smith")
        .baseUri(serverUri.resolve("root/"))
        .defaultHeader("Accept", "text/plain")
        .build();

    var response = client.send(GET("relative?q=value"), BodyHandlers.ofString());
    assertThat(response.body()).isEqualTo("unzip me!");

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
    server.enqueue(new MockResponse()
        .setBody(new okio.Buffer().write(gzip("unzip me!")))
        .addHeader("Content-Encoding", "gzip"));

    var client = clientBuilder
        .userAgent("Will Smith")
        .baseUri(serverUri.resolve("root/"))
        .defaultHeader("Accept", "text/plain")
        .build();

    assertThat(client.sendAsync(GET("relative?q=value"), BodyHandlers.ofString()))
        .succeedsWithin(Duration.ofSeconds(20))
        .returns("unzip me!", from(HttpResponse::body));

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
  void asyncGetWithCompressedPush() {
    int pushCount = 3;
    var mockResponse = new MockResponse()
        .setBody(new okio.Buffer().write(deflate("Pikachu")))
        .addHeader("Content-Encoding", "deflate");
    for (int i = 0; i < pushCount; i++) {
      var pushResponse =
          i % 2 == 0
              ? new MockResponse()
                  .setBody(new okio.Buffer().write(gzip("pika pika!")))
                  .addHeader("Content-Encoding", "gzip")
              : new MockResponse().setBody("pika pika!");
      mockResponse.withPush(
          new PushPromise("GET", "/push" + i, Headers.of(":scheme", "https"), pushResponse));
    }

    server.enqueue(mockResponse);

    var client = clientBuilder.version(Version.HTTP_2).build();
    var pushes = new ConcurrentHashMap<HttpRequest, CompletableFuture<HttpResponse<String>>>();
    var responseFuture = client.sendAsync(
        GET(serverUri),
        BodyHandlers.ofString(),
        PushPromiseHandler.of(__ -> BodyHandlers.ofString(), pushes));
    assertThat(responseFuture)
        .succeedsWithin(Duration.ofSeconds(20))
        .returns("Pikachu", from(HttpResponse::body));

    pushes.forEachValue(
        Long.MAX_VALUE,
        push ->
            assertThat(push)
                .succeedsWithin(Duration.ofSeconds(40))
                .returns("pika pika!", from(HttpResponse::body)));
  }

  @Test
  void getWithoutAutoAcceptEncoding() throws Exception {
    var gzippedBytes = gzip("Pikachu");
    server.enqueue(new MockResponse()
        .setBody(new okio.Buffer().write(gzippedBytes))
        .addHeader("Content-Encoding", "gzip"));

    var client = clientBuilder.autoAcceptEncoding(false).build();
    var response = client.send(GET(serverUri), BodyHandlers.ofByteArray());
    assertThat(response.body()).isEqualTo(gzippedBytes);

    assertThat(server.takeRequest().getHeader("Accept-Encoding")).isNull();
  }

  @Test
  void postMimeBody() throws Exception {
    server.enqueue(new MockResponse());

    var client = clientBuilder.build();
    var body = FormBodyPublisher.newBuilder().query("q", "hello").build();
    client.send(POST(serverUri, body), BodyHandlers.ofString());

    assertThat(server.takeRequest().getHeader("Content-Type"))
        .isEqualTo(body.mediaType().toString());
  }

  @Test
  void requestTimeout() {
    var client = clientBuilder.requestTimeout(Duration.ofMillis(50)).build();

    assertThatExceptionOfType(HttpTimeoutException.class)
        .isThrownBy(() -> client.send(GET(serverUri), BodyHandlers.ofString()));

    assertThat(client.sendAsync(GET(serverUri), BodyHandlers.ofString()))
        .failsWithin(Duration.ofSeconds(40))
        .withThrowableOfType(ExecutionException.class)
        .withCauseInstanceOf(HttpTimeoutException.class);
  }

  @Test
  void readTimeout() {
    var client = clientBuilder.readTimeout(Duration.ofMillis(50)).build();

    server.enqueue(new MockResponse()
        .setBody("Pikachu")
        .throttleBody(1, 500, TimeUnit.MILLISECONDS));
    assertThatExceptionOfType(HttpTimeoutException.class)
        .isThrownBy(() -> client.send(GET(serverUri), BodyHandlers.ofString()));

    server.enqueue(new MockResponse()
        .setBody("Pikachu")
        .throttleBody(1, 500, TimeUnit.MILLISECONDS));
    assertThat(client.sendAsync(GET(serverUri), BodyHandlers.ofString()))
        .failsWithin(Duration.ofSeconds(20))
        .withThrowableOfType(ExecutionException.class)
        .withCauseInstanceOf(HttpReadTimeoutException.class);
  }

  @Test
  @ExecutorConfig(SCHEDULER)
  void readTimeoutWithCustomScheduler(ScheduledExecutorService scheduler) {
    var client = clientBuilder.readTimeout(Duration.ofMillis(50), scheduler).build();

    server.enqueue(new MockResponse()
        .setBody("Pikachu")
        .throttleBody(1, 500, TimeUnit.MILLISECONDS));
    assertThatExceptionOfType(HttpTimeoutException.class)
        .isThrownBy(() -> client.send(GET(serverUri), BodyHandlers.ofString()));

    server.enqueue(new MockResponse()
        .setBody("Pikachu")
        .throttleBody(1, 500, TimeUnit.MILLISECONDS));
    assertThat(client.sendAsync(GET(serverUri), BodyHandlers.ofString()))
        .failsWithin(Duration.ofSeconds(20))
        .withThrowableOfType(ExecutionException.class)
        .withCauseInstanceOf(HttpReadTimeoutException.class);
  }

  @Test
  void exchange() {
    server.enqueue(new MockResponse()
        .setBody(new okio.Buffer().write(gzip("Pikachu")))
        .addHeader("Content-Encoding", "gzip"));

    var client = clientBuilder.build();
    var publisher = client.exchange(GET(serverUri), BodyHandlers.ofString());
    var subscriber = new TestSubscriber<HttpResponse<String>>();
    subscriber.request = 20L;
    publisher.subscribe(subscriber);
    subscriber.awaitComplete();

    assertThat(subscriber.lastError).isNull();
    assertThat(subscriber.items)
        .singleElement()
        .returns("Pikachu", from(HttpResponse::body));
  }

  @Test
  @UseHttps
  void exchangeWithPush() {
    var pushCount = 3;
    var mockResponse = new MockResponse()
        .setBody(new okio.Buffer().write(deflate("Pikachu")))
        .addHeader("Content-Encoding", "deflate");
    for (int i = 0; i < pushCount; i++) {
      var pushResponse =
          i % 2 == 0
              ? new MockResponse()
                  .setBody(new okio.Buffer().write(gzip("pika pika!!")))
                  .addHeader("Content-Encoding", "gzip")
              : new MockResponse().setBody("pika pika!!");
      mockResponse.withPush(
          new PushPromise(
              "GET", "/push" + i, Headers.of(":scheme", "https"), pushResponse));
    }

    server.enqueue(mockResponse);

    var client = clientBuilder.version(Version.HTTP_2).build();
    var rejectFirstPush = new AtomicBoolean();
    // Accept all push promises but the first
    var publisher = client.exchange(
        GET(serverUri),
        BodyHandlers.ofString(),
        req -> rejectFirstPush.compareAndSet(false, true) ? null : BodyHandlers.ofString());
    var subscriber = new TestSubscriber<HttpResponse<String>>();
    publisher.subscribe(subscriber);
    subscriber.awaitComplete();
    assertThat(subscriber.items)
        .hasSize(1 + (pushCount - 1)); // Main response + all push promises but the rejected one

    for (var response : subscriber.items) {
      var path = response.request().uri().getPath();
      if (path.startsWith("/push")) {
        assertThat(path).isNotEqualTo("/push0"); // First push promise isn't accepted
        assertThat(response.body()).isEqualTo("pika pika!!");
      } else {
        assertThat(response.body()).isEqualTo("Pikachu");
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

    var response = client.send(GET(serverUri), BodyHandlers.ofString());
    assertThat(response)
        .returns(200, from(HttpResponse::statusCode))
        .returns("I'm back!", from(HttpResponse::body));
  }

  @Test
  void asyncRetryingWithInterceptors() {
    int maxRetries = 3;

    var client = clientBuilder.interceptor(new RetryingInterceptor(maxRetries)).build();

    for (int i = 0; i < maxRetries; i++) {
      server.enqueue(new MockResponse().setResponseCode(HTTP_UNAVAILABLE));
    }
    server.enqueue(new MockResponse().setBody("I'm back!"));

    var responseFuture = client.sendAsync(GET(serverUri), BodyHandlers.ofString());
    assertThat(responseFuture)
        .succeedsWithin(Duration.ofSeconds(20))
        .returns(200, from(HttpResponse::statusCode))
        .returns("I'm back!", from(HttpResponse::body));
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
      HttpResponse<T> response = chain.forward(request);
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
      var responseCf = chain.forwardAsync(request);
      for (int i = 0; i < maxRetryCount; i++) {
        final int _i = i;
        responseCf = responseCf.thenCompose(
            res -> handleRetry(res, () -> chain.forwardAsync(request), _i));
      }
      return responseCf;
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
