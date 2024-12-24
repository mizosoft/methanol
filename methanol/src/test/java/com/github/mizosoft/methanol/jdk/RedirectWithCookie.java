/*
 * Copyright (c) 2024 Moataz Hussein
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

/*
 * @test
 * @summary Test for cookie handling when redirecting
 * @modules java.base/sun.net.www.http
 *          java.net.http/jdk.internal.net.http.common
 *          java.net.http/jdk.internal.net.http.frame
 *          java.net.http/jdk.internal.net.http.hpack
 *          java.logging
 *          jdk.httpserver
 * @library /test/lib http2/server
 * @build Http2TestServer
 * @build jdk.test.lib.net.SimpleSSLContext
 * @run testng/othervm
 *       -Djdk.httpclient.HttpClient.log=trace,headers,requests
 *       RedirectWithCookie
 */

package com.github.mizosoft.methanol.jdk;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.Methanol;
import com.github.mizosoft.methanol.internal.cache.RedirectingInterceptor;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.MockWebServerExtension;
import com.github.mizosoft.methanol.testing.TestUtils;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@ExtendWith({MockWebServerExtension.class, ExecutorExtension.class})
@TestInstance(Lifecycle.PER_CLASS)
class RedirectWithCookie {
  SSLContext sslContext;
  MockWebServer httpTestServer; // HTTP/1.1    [ 4 servers ]
  MockWebServer httpsTestServer; // HTTPS/1.1
  MockWebServer http2TestServer; // HTTP/2 ( h2c )
  MockWebServer https2TestServer; // HTTP/2 ( h2  )
  String httpURI;
  String httpsURI;
  String http2URI;
  String https2URI;

  static final String MESSAGE = "BasicRedirectTest message body";
  static final int ITERATIONS = 3;

  Object[][] positive() {
    var uris = new TestUriSupplierFactory(this);
    return new Object[][] {
      {uris.uriString("httpURI")},
      {uris.uriString("httpsURI")},
      {uris.uriString("http2URI")},
      {uris.uriString("https2URI")}
    };
  }

  @ParameterizedTest
  @MethodSource("positive")
  void test(Supplier<String> uriString, Executor handlerExecutor) throws Exception {
    // out.printf("%n---- starting (%s) ----%n", uriString);
    HttpClient client =
        Methanol.newBuilder()
            .followRedirects(Redirect.NEVER)
            .cookieHandler(new CookieManager())
            .sslContext(sslContext)
            .interceptor(new RedirectingInterceptor(Redirect.ALWAYS, handlerExecutor))
            .autoAcceptEncoding(false)
            .build();
    assert client.cookieHandler().isPresent();

    URI uri = URI.create(uriString.get());
    HttpRequest request = HttpRequest.newBuilder(uri).build();
    // out.println("Initial request: " + request.uri());

    for (int i = 0; i < ITERATIONS; i++) {
      // out.println("iteration: " + i);
      HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

      // out.println("  Got response: " + response);
      // out.println("  Got body Path: " + response.body());

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).isEqualTo(MESSAGE);
      // asserts redirected URI in response.request().uri()
      assertThat(response.uri().getPath()).endsWith("message");
      assertPreviousRedirectResponses(request, response);
    }
  }

  static void assertPreviousRedirectResponses(
      HttpRequest initialRequest, HttpResponse<?> finalResponse) {
    // there must be at least one previous response
    finalResponse
        .previousResponse()
        .orElseThrow(() -> new RuntimeException("no previous response"));

    HttpResponse<?> response = finalResponse;
    do {
      URI uri = response.uri();
      response = response.previousResponse().get();
      assertThat(response.statusCode())
          .withFailMessage("Expected 300 <= code <= 309, got: %s", response.statusCode())
          .isStrictlyBetween(300, 309);

      assertThat(response.body()).withFailMessage("Unexpected body: %s", response.body()).isNull();
      String locationHeader =
          response
              .headers()
              .firstValue("Location")
              .orElseThrow(() -> new RuntimeException("no previous Location"));
      assertThat(uri.toString())
          .as("URI: %s, Location: %s", uri, locationHeader)
          .endsWith(locationHeader);

    } while (response.previousResponse().isPresent());

    // initial
    assertThat(initialRequest)
        .withFailMessage(
            "Expected initial request [%s] to equal last prev req [%s]",
            initialRequest, response.request())
        .isEqualTo(response.request());
  }

  // -- Infrastructure

  @BeforeEach
  public void setup(
      MockWebServer httpTestServer,
      MockWebServer httpsTestServer,
      MockWebServer http2TestServer,
      MockWebServer https2TestServer)
      throws Exception {
    sslContext = TestUtils.localhostSslContext();

    this.httpTestServer = httpTestServer;
    var httpTestServerDispatcher = new ScopedDispatcher();
    httpTestServerDispatcher.put("/http1/cookie/", new CookieRedirectDispatcher());
    httpTestServer.setDispatcher(httpTestServerDispatcher);
    httpURI = httpTestServer.url("/http1/cookie/redirect").toString();
    httpsTestServer.useHttps(sslContext.getSocketFactory(), false);
    this.httpsTestServer = httpsTestServer;
    var httpsTestServerDispatcher = new ScopedDispatcher();
    httpsTestServerDispatcher.put("/https1/cookie/", new CookieRedirectDispatcher());
    httpsTestServer.setDispatcher(httpsTestServerDispatcher);
    httpsURI = httpsTestServer.url("/https1/cookie/redirect").toString();

    this.http2TestServer = http2TestServer;
    var http2TestServerDispatcher = new ScopedDispatcher();
    http2TestServerDispatcher.put("/http2/cookie/", new CookieRedirectDispatcher());
    http2TestServer.setDispatcher(http2TestServerDispatcher);
    http2URI = http2TestServer.url("/http2/cookie/redirect").toString();
    https2TestServer.useHttps(sslContext.getSocketFactory(), false);
    this.https2TestServer = https2TestServer;
    var https2TestServerDispatcher = new ScopedDispatcher();
    https2TestServerDispatcher.put("/https2/cookie/", new CookieRedirectDispatcher());
    https2TestServer.setDispatcher(https2TestServerDispatcher);
    https2URI = https2TestServer.url("/https2/cookie/redirect").toString();

    httpTestServer.start();
    httpsTestServer.start();
    http2TestServer.start();
    https2TestServer.start();
  }

  static class CookieRedirectDispatcher extends Dispatcher {

    @NotNull
    @Override
    public MockResponse dispatch(@NotNull RecordedRequest recordedRequest) {
      // System.out.println("CookieRedirectDispatcher for: " +
      // recordedRequest.getRequestUrl());

      // redirecting
      if (recordedRequest.getRequestUrl().encodedPath().endsWith("redirect")) {
        String url = recordedRequest.getRequestUrl().resolve("message").toString();
        return new MockResponse()
            .addHeader("Location", url)
            .addHeader("Set-Cookie", "CUSTOMER=WILE_E_COYOTE")
            .setResponseCode(302);
      }

      // not redirecting
      List<String> cookie = recordedRequest.getHeaders().values("Cookie");

      if (cookie == null || cookie.size() == 0) {
        String msg = "No cookie header present";
        (new RuntimeException(msg)).printStackTrace();
        return new MockResponse().setResponseCode(500).setBody(msg);
      } else if (!cookie.get(0).equals("CUSTOMER=WILE_E_COYOTE")) {
        String msg = "Incorrect cookie header value:[" + cookie.get(0) + "]";
        (new RuntimeException(msg)).printStackTrace();
        return new MockResponse().setResponseCode(500).setBody(msg);
      } else {
        assert cookie.get(0).equals("CUSTOMER=WILE_E_COYOTE");
        byte[] bytes = MESSAGE.getBytes(UTF_8);
        return new MockResponse().setBody(new okio.Buffer().write(bytes));
      }
    }
  }
}
