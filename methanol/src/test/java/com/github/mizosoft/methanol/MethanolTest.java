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

import static com.github.mizosoft.methanol.MoreBodyPublishers.ofMediaType;
import static com.github.mizosoft.methanol.MutableRequest.GET;
import static com.github.mizosoft.methanol.MutableRequest.POST;
import static com.github.mizosoft.methanol.testutils.TestUtils.headers;
import static java.net.http.HttpRequest.BodyPublishers.noBody;
import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static java.net.http.HttpResponse.BodyHandlers.discarding;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.testutils.ServiceLoggerHelper;
import java.net.Authenticator;
import java.net.CookieManager;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MethanolTest {

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

  @Test
  void defaultExtraFields() {
    var client = Methanol.create();
    assertTrue(client.baseUri().isEmpty());
    assertTrue(client.userAgent().isEmpty());
    assertTrue(client.requestTimeout().isEmpty());
    assertEquals(headers(/* empty */), client.defaultHeaders());
    assertTrue(client.autoAcceptEncoding());
  }

  @Test
  void setExtraFields() {
    var client = Methanol.newBuilder()
        .userAgent("Mr Potato")
        .baseUri(URI.create("https://localhost"))
        .requestTimeout(Duration.ofSeconds(69))
        .defaultHeader("Accept", "text/html")
        .autoAcceptEncoding(true)
        .build();
    assertEquals(Optional.of("Mr Potato"), client.userAgent());
    assertEquals(Optional.of(URI.create("https://localhost")), client.baseUri());
    assertEquals(Optional.of(Duration.ofSeconds(69)), client.requestTimeout());
    assertEquals(
        headers("Accept", "text/html", "User-Agent", "Mr Potato"),
        client.defaultHeaders());
    assertTrue(client.autoAcceptEncoding());
  }

  @Test
  void setDelegateFields() throws Exception {
    var cookieHandler = new CookieManager();
    var connectTimeout = Duration.ofSeconds(69);
    var sslContext = SSLContext.getDefault();
    var executor = FlowSupport.SYNC_EXECUTOR;
    var redirect = Redirect.ALWAYS;
    var version = Version.HTTP_1_1;
    var proxy = ProxySelector.of(InetSocketAddress.createUnresolved("localhost", 80));
    var authenticator = new Authenticator() {};
    var client = Methanol.newBuilder()
        .cookieHandler(cookieHandler)
        .connectTimeout(connectTimeout)
        .sslContext(sslContext)
        .executor(executor)
        .followRedirects(redirect)
        .version(version)
        .proxy(proxy)
        .authenticator(authenticator)
        .build();
    assertEquals(Optional.of(cookieHandler), client.cookieHandler());
    assertEquals(Optional.of(connectTimeout), client.connectTimeout());
    assertEquals(sslContext, client.sslContext());
    assertEquals(Optional.of(executor), client.executor());
    assertEquals(redirect, client.followRedirects());
    assertEquals(version, client.version());
    assertEquals(Optional.of(proxy), client.proxy());
    assertEquals(Optional.of(authenticator), client.authenticator());
  }

  @Test
  void buildWithExplicitDelegate() throws NoSuchAlgorithmException {
    var delegate = HttpClient.newBuilder()
        .cookieHandler(new CookieManager())
        .connectTimeout(Duration.ofSeconds(69))
        .sslContext(SSLContext.getDefault())
        .executor(FlowSupport.SYNC_EXECUTOR)
        .followRedirects(Redirect.ALWAYS)
        .version(Version.HTTP_1_1)
        .proxy(ProxySelector.of(InetSocketAddress.createUnresolved("localhost", 80)))
        .authenticator(new Authenticator() {})
        .build();
    var client = Methanol.newBuilder(delegate).build();
    assertEquals(delegate, client.underlyingClient());
    assertEquals(delegate.cookieHandler(), client.cookieHandler());
    assertEquals(delegate.connectTimeout(), client.connectTimeout());
    assertSame(delegate.sslContext(), client.sslContext());
    assertEquals(delegate.executor(), client.executor());
    assertEquals(delegate.followRedirects(), client.followRedirects());
    assertEquals(delegate.version(), client.version());
    assertEquals(delegate.proxy(), client.proxy());
    assertEquals(delegate.authenticator(), client.authenticator());
  }

  @Test
  void applyConsumer() {
    var client = Methanol.newBuilder()
        .autoAcceptEncoding(false)
        .apply(b -> b.defaultHeader("Accept", "text/html"))
        .build();
    assertEquals(headers("Accept", "text/html"), client.defaultHeaders());
  }

  @Test
  void addUserAgentAsDefaultHeader() {
    var client = Methanol.newBuilder().defaultHeader("User-Agent", "Mr Potato").build();
    assertEquals(Optional.of("Mr Potato"), client.userAgent());
  }

  @Test
  void requestDecoration_defaultHeaders() throws Exception {
    var delegate = new RecordingClient();
    var client = Methanol.newBuilder(delegate)
        .autoAcceptEncoding(false)
        .defaultHeaders("Accept", "text/html", "Cookie", "password=123")
        .build();
    var request = GET("https://localhost").header("Foo", "bar");
    client.send(request, discarding());
    assertEquals(
        headers(
            "Accept", "text/html",
            "Cookie", "password=123",
            "Foo", "bar"),
        delegate.request.headers());
  }

  @Test
  void requestDecoration_userAgent() throws Exception {
    var delegate = new RecordingClient();
    var client = Methanol.newBuilder(delegate)
        .autoAcceptEncoding(false)
        .userAgent("Mr Potato")
        .build();
    var request = GET("https://localhost");
    client.send(request, discarding());
    assertEquals(headers("User-Agent", "Mr Potato"), delegate.request.headers());
  }

  @Test
  void requestDecoration_baseUri() throws Exception {
    var delegate = new RecordingClient();
    var client = Methanol.newBuilder(delegate).baseUri("https://localhost/secrets/").build();
    client.send(GET(""), discarding()); // empty relative path
    assertEquals(client.baseUri().orElseThrow(), delegate.request.uri());
    client.send(GET("/memes?q=bruh"), discarding()); // root path
    assertEquals(URI.create("https://localhost/memes?q=bruh"), delegate.request.uri());
    client.send(GET("memes?q=bruh"), discarding()); // relative path
    assertEquals(URI.create("https://localhost/secrets/memes?q=bruh"), delegate.request.uri());
  }

  @Test
  void requestDecorations_autoAcceptEncoding() throws Exception {
    var delegate = new RecordingClient();
    var client = Methanol.newBuilder(delegate).autoAcceptEncoding(true).build();
    client.send(GET("https://localhost"), discarding());
    assertEquals(headers("Accept-Encoding", acceptEncodingValue()), delegate.request.headers());
  }

  @Test
  void requestDecorations_mimeBodyPublisher() throws Exception {
    var delegate = new RecordingClient();
    var client = Methanol.newBuilder(delegate).autoAcceptEncoding(false).build();
    var mimeBody = ofMediaType(noBody(), MediaType.of("text", "plain"));
    client.send(POST("https://localhost", mimeBody), discarding());
    assertEquals(headers("Content-Type", "text/plain"), delegate.request.headers());
  }

  @Test
  void requestDecoration_noOverwrites_exceptWithContentType() throws Exception {
    var delegate = new RecordingClient();
    var client = Methanol.newBuilder(delegate)
        .defaultHeaders("Accept", "text/html", "Content-Type", "image/png")
        .autoAcceptEncoding(true)
        .userAgent("Mr Potato")
        .requestTimeout(Duration.ofSeconds(69))
        .build();
    var mimeBody = ofMediaType(noBody(), MediaType.of("application", "json"));
    var request = MutableRequest.create("https://localhost")
        .headers(
            "Accept", "application/json",
            "Accept-Encoding", "myzip",
            "User-Agent", "Joe Mama",
            "Content-Type", "text/plain")
        .POST(mimeBody)
        .timeout(Duration.ofSeconds(420))
        .build();
    client.send(request, discarding());
    assertEquals(
        headers(
            "Accept", "application/json", // not overwritten by default header
            "Accept-Encoding", "myzip", // not overwritten by autoCompression
            "User-Agent", "Joe Mama", // not overwritten by userAgent
            "Content-Type", mimeBody.mediaType().toString()), // overwritten by MimeBodyPublisher
        delegate.request.headers());
    assertEquals(request.timeout(), delegate.request.timeout()); // not overwritten by requestTimeout
  }

  @Test
  void requestDecoration_requestTimeout() throws Exception {
    var timeout = Duration.ofSeconds(69);
    var delegate = new RecordingClient();
    var client = Methanol.newBuilder(delegate).requestTimeout(timeout).build();
    client.send(GET("https://localhost"), discarding());
    assertEquals(Optional.of(timeout), delegate.request.timeout());
  }

  @Test
  void requestDecoration_fromMutableRequest() throws Exception {
    var delegate = new RecordingClient();
    var client = Methanol.newBuilder(delegate)
        .baseUri("https://localhost/")
        .defaultHeader("Accept", "text/html")
        .autoAcceptEncoding(true)
        .userAgent("Mr Potato")
        .build();
    var mutableRequest = POST("/secrets", ofString("Santa is real"))
        .header("Content-Type", "text/plain")
        .timeout(Duration.ofSeconds(69))
        .version(Version.HTTP_1_1)
        .expectContinue(true);
    var snapshot = mutableRequest.build();
    client.send(mutableRequest, discarding());
    var sentRequest = delegate.request;
    // original request properties are copied
    assertTrue(
        sentRequest.headers().map().entrySet().containsAll(snapshot.headers().map().entrySet()));
    assertEquals(snapshot.bodyPublisher(), sentRequest.bodyPublisher());
    assertEquals(snapshot.timeout(), sentRequest.timeout());
    assertEquals(snapshot.version(), sentRequest.version());
    assertEquals(snapshot.expectContinue(), sentRequest.expectContinue());
    // main request not mutated
    assertDeepEquals(snapshot, mutableRequest);
  }

  @Test
  void requestDecoration_fromImmutable() throws Exception {
    var delegate = new RecordingClient();
    var client = Methanol.newBuilder(delegate)
        .baseUri("https://localhost/")
        .defaultHeader("Accept", "text/html")
        .autoAcceptEncoding(true)
        .userAgent("Mr Potato")
        .build();
    var immutableRequest = POST("/secrets", ofString("Santa is real"))
        .header("Content-Type", "text/plain")
        .timeout(Duration.ofSeconds(69))
        .version(Version.HTTP_1_1)
        .expectContinue(true)
        .build();
    client.send(immutableRequest, discarding());
    var sentRequest = delegate.request;
    // original request properties are copied
    assertTrue(
        sentRequest.headers().map().entrySet().containsAll(
            immutableRequest.headers().map().entrySet()));
    assertEquals(immutableRequest.bodyPublisher(), sentRequest.bodyPublisher());
    assertEquals(immutableRequest.timeout(), sentRequest.timeout());
    assertEquals(immutableRequest.version(), sentRequest.version());
    assertEquals(immutableRequest.expectContinue(), sentRequest.expectContinue());
  }

  @Test
  void illegalBaseUri() {
    var builder = Methanol.newBuilder();
    assertThrows(
        IllegalArgumentException.class,
        () -> builder.baseUri(new URI(null, "localhost", null, null))); // no scheme
    assertThrows(IllegalArgumentException.class,
        () -> builder.baseUri("ws://localhost")); // not http
    assertThrows(IllegalArgumentException.class,
        () -> builder.baseUri(new URI("https", null, "/", ""))); // no host
  }

  /** URI is also checked after being resolved with base URI. */
  @Test
  void illegalResolvedUri() {
    var client = Methanol.newBuilder().build();
    var webSocketRequest = GET("ws://localhost");
    assertThrows(IllegalArgumentException.class, () -> client.send(webSocketRequest, discarding()));
  }

  @Test
  void illegalUserAgent() {
    var builder = Methanol.newBuilder();
    assertThrows(IllegalArgumentException.class, () -> builder.userAgent("b\ruh"));
    assertThrows(IllegalArgumentException.class, () -> builder.userAgent("…"));
  }

  @Test
  void illegalRequestTimeout() {
    var builder = Methanol.newBuilder();
    assertThrows(
        IllegalArgumentException.class,
        () -> builder.requestTimeout(Duration.ofSeconds(0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> builder.requestTimeout(Duration.ofSeconds(-1)));
  }

  @Test
  void illegalDefaultHeaders() {
    var builder = Methanol.newBuilder();
    builder.defaultHeader("foo", "fa\t "); // valid
    assertThrows(IllegalArgumentException.class, () -> builder.defaultHeader("ba\r", "foo"));
    assertThrows(IllegalArgumentException.class, () -> builder.defaultHeaders("Name", "…"));
  }

  private static void assertDeepEquals(HttpRequest x, HttpRequest y) {
    assertEquals(x.method(), y.method());
    assertEquals(x.uri(), y.uri());
    assertEquals(x.headers(), y.headers());
    assertEquals(x.bodyPublisher(), y.bodyPublisher());
    assertEquals(x.timeout(), y.timeout());
    assertEquals(x.version(), y.version());
    assertEquals(x.expectContinue(), y.expectContinue());
  }

  private static String acceptEncodingValue() {
    return String.join(", ", BodyDecoder.Factory.installedBindings().keySet());
  }

  private static final class RecordingClient extends HttpClientStub {

    HttpRequest request;
    BodyHandler<?> handler;

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> responseBodyHandler) {
      this.request = request;
      this.handler = responseBodyHandler;
      return new HttpResponseStub<>();
    }
  }
}
