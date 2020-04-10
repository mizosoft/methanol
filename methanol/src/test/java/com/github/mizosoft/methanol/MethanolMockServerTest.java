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
import static com.github.mizosoft.methanol.testutils.TestUtils.localhostSslContext;
import static java.net.http.HttpResponse.BodyHandlers.ofByteArray;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.mizosoft.methanol.testutils.ServiceLoggerHelper;
import com.github.mizosoft.methanol.testutils.TestSubscriber;
import com.github.mizosoft.methanol.testutils.TestUtils;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;
import okhttp3.Headers;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.PushPromise;
import okio.Buffer;
import okio.DeflaterSink;
import okio.GzipSink;
import okio.Okio;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MethanolMockServerTest {

  private static ServiceLoggerHelper loggerHelper;

  @BeforeAll
  static void turnOffServiceLogger() {
    // Do not log service loader failures.
    loggerHelper = new ServiceLoggerHelper();
    loggerHelper.turnOff();
  }

  @AfterAll
  static void resetServiceLogger() {
    loggerHelper.reset();
  }

  private MockWebServer server;
  private Executor executor;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
    executor = Executors.newFixedThreadPool(8);
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
    TestUtils.shutdown(executor);
  }

  @Test
  void syncGet() throws Exception {
    server.enqueue(new MockResponse()
        .setBody(gzip("unzip me!"))
        .addHeader("Content-Encoding", "gzip"));

    var client = Methanol.newBuilder()
        .userAgent("Will smith")
        .baseUri(server.url("/root/").uri())
        .defaultHeader("Accept", "text/plain")
        .build();
    var response = client.send(GET("relative?query=value"), ofString());
    assertEquals("unzip me!", response.body());

    var recordedRequest = server.takeRequest();
    assertEquals("Will smith", recordedRequest.getHeader("User-Agent"));
    assertEquals(acceptEncodingValue(), recordedRequest.getHeader("Accept-Encoding"));
    assertEquals("text/plain", recordedRequest.getHeader("Accept"));
    var requestUrl = recordedRequest.getRequestUrl();
    assertEquals(List.of("root", "relative"), requestUrl.pathSegments());
    assertEquals("value", requestUrl.queryParameter("query"));
  }

  @Test
  void asyncGet() throws Exception {
    server.enqueue(new MockResponse()
        .setBody(deflate("unzip me!"))
        .addHeader("Content-Encoding", "deflate"));

    var client = Methanol.newBuilder()
        .userAgent("Will smith")
        .baseUri(server.url("/root/").uri())
        .defaultHeader("Accept", "text/plain")
        .build();
    var response = client.sendAsync(GET("relative?query=value"), ofString()).join();
    assertEquals("unzip me!", response.body());

    var recordedRequest = server.takeRequest();
    assertEquals("Will smith", recordedRequest.getHeader("User-Agent"));
    assertEquals(acceptEncodingValue(), recordedRequest.getHeader("Accept-Encoding"));
    assertEquals("text/plain", recordedRequest.getHeader("Accept"));
    var requestUrl = recordedRequest.getRequestUrl();
    assertEquals(List.of("root", "relative"), requestUrl.pathSegments());
    assertEquals("value", requestUrl.queryParameter("query"));
  }

  @Test
  void asyncGetWithCompressedPush() throws Exception {
    var pushCount = 3;
    var mockResponse = new MockResponse()
        .setBody(deflate("Recardo is coming!"))
        .addHeader("Content-Encoding", "deflate");
    for (int i = 0; i < pushCount; i++) {
      var pushResponse = i % 2 == 0
          ? new MockResponse().setBody(gzip("RUN!!")).addHeader("Content-Encoding", "gzip")
          : new MockResponse().setBody("RUN!!");
      mockResponse.withPush(
          new PushPromise(
              "GET", "/push" + i, Headers.of(":scheme", "https"), pushResponse));
    }
    server.enqueue(mockResponse);

    var client = useHttps(Methanol.newBuilder()).build();
    var pushes = new ConcurrentHashMap<HttpRequest, CompletableFuture<HttpResponse<String>>>();
    var response = client.sendAsync(
        GET(server.url("/").uri()),
        ofString(),
        (req1, push, acc) -> pushes.put(push, acc.apply(ofString())))
        .join();
    assertEquals("Recardo is coming!", response.body());
    assertEquals(pushCount, pushes.size());
    pushes.forEachValue(Long.MAX_VALUE, cf -> {
      var res = cf.join();
      assertEquals("RUN!!", res.body());
    });
  }

  @Test
  void get_noAutoAcceptEncoding() throws Exception {
    server.enqueue(new MockResponse()
        .setBody(gzip("dare to unzip me! xD"))
        .addHeader("Content-Encoding", "gzip"));

    var client = Methanol.newBuilder()
        .autoAcceptEncoding(false)
        .build();
    var response = client.send(GET(server.url("/").uri()), ofByteArray());
    assertArrayEquals(gzip("dare to unzip me! xD").readByteArray(), response.body());

    var recordedRequest = server.takeRequest();
    assertNull(recordedRequest.getHeader("Accept-Encoding"));
  }

  @Test
  void post_mimeBody() throws Exception {
    server.enqueue(new MockResponse());

    var client = Methanol.newBuilder().build();
    var body = FormBodyPublisher.newBuilder()
        .query("q", ";_;")
        .build();
    client.send(POST(server.url("/").uri(), body), ofString());
    var recordedRequest = server.takeRequest();
    assertEquals(body.mediaType().toString(), recordedRequest.getHeader("Content-Type"));
  }

  @Test
  void defaultRequestTimeout() {
    var client = Methanol.newBuilder()
        .requestTimeout(Duration.ofMillis(100))
        .baseUri(server.url("/").uri())
        .build();
    assertThrows(HttpTimeoutException.class, () -> client.send(GET(""), ofString()));
  }

  @Test
  void exchange() {
    server.enqueue(new MockResponse()
        .setBody(gzip("unzip me!"))
        .addHeader("Content-Encoding", "gzip"));

    var client = Methanol.newBuilder().baseUri(server.url("/").uri()).build();
    var publisher = client.exchange(GET(""), ofString());
    var subscriber = new TestSubscriber<HttpResponse<String>>();
    subscriber.request = 20L;
    publisher.subscribe(subscriber);
    subscriber.awaitComplete();
    assertEquals(1, subscriber.nexts);
    assertEquals(1, subscriber.completes);
    assertEquals("unzip me!", subscriber.items.getLast().body());
  }

  @Test
  void exchangeWithPush() throws IOException {
    var pushCount = 3;
    var mockResponse = new MockResponse()
        .setBody(deflate("Recardo is coming!"))
        .addHeader("Content-Encoding", "deflate");
    for (int i = 0; i < pushCount; i++) {
      var pushResponse = i % 2 == 0
          ? new MockResponse().setBody(gzip("RUN!!")).addHeader("Content-Encoding", "gzip")
          : new MockResponse().setBody("RUN!!");
      mockResponse.withPush(
          new PushPromise(
              "GET", "/push" + i, Headers.of(":scheme", "https"), pushResponse));
    }
    server.enqueue(mockResponse);

    var client = useHttps(Methanol.newBuilder())
        .executor(executor)
        .baseUri(server.url("/").uri())
        .build();
    var rejectFirstPush = new AtomicBoolean();
    var publisher = client.exchange(
        GET(""),
        ofString(),
        req -> rejectFirstPush.compareAndSet(false, true) ? null : ofString()); // accept all but first
    var subscriber = new TestSubscriber<HttpResponse<String>>();
    publisher.subscribe(subscriber);
    subscriber.awaitComplete();
    assertEquals(1 /* main */ + (pushCount - 1) /* all pushes but first */, subscriber.nexts);
    for (var res : subscriber.items) {
      var path = res.request().uri().getPath();
      if (path.startsWith("/push")) {
        assertNotEquals("/push0", path); // first push not accepted
        assertEquals("RUN!!", res.body());
      } else {
        assertEquals("Recardo is coming!", res.body());
      }
    }
  }

  private Methanol.Builder useHttps(Methanol.Builder builder) throws IOException {
    var sslContext = localhostSslContext();
    server.useHttps(sslContext.getSocketFactory(), false);
    return builder.sslContext(sslContext);
  }

  private static Buffer gzip(String body) {
    var buffer = new Buffer();
    try (var gzSink = Okio.buffer(new GzipSink(buffer))) {
      gzSink.writeUtf8(body);
    } catch (IOException ioe) {
      throw new AssertionError(ioe);
    }
    return buffer;
  }

  private static Buffer deflate(String body) {
    var buffer = new Buffer();
    try (var gzSink = Okio.buffer(new DeflaterSink(buffer, new Deflater()))) {
      gzSink.writeUtf8(body);
    } catch (IOException ioe) {
      throw new AssertionError(ioe);
    }
    return buffer;
  }

  private static String acceptEncodingValue() {
    return String.join(", ", BodyDecoder.Factory.installedBindings().keySet());
  }
}
