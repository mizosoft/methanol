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

package com.github.mizosoft.methanol.jdk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.mizosoft.methanol.Methanol;
import com.github.mizosoft.methanol.internal.cache.RedirectingInterceptor;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorSpec;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.MockWebServerExtension;
import com.github.mizosoft.methanol.testing.TestUtils;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.Headers;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@ExtendWith({MockWebServerExtension.class, ExecutorExtension.class})
@TestInstance(Lifecycle.PER_CLASS)
class RedirectMethodChange {
  SSLContext sslContext;
  HttpClient client;

  MockWebServer httpTestServer; // HTTP/1.1    [ 4 servers ]
  MockWebServer httpsTestServer; // HTTPS/1.1
  MockWebServer http2TestServer; // HTTP/2 ( h2c )
  MockWebServer https2TestServer; // HTTP/2 ( h2  )
  String httpURI;
  String httpsURI;
  String http2URI;
  String https2URI;

  static final String RESPONSE = "Hello world";
  static final String POST_BODY = "This is the POST body 123909090909090";

  private static HttpRequest.BodyPublisher getRequestBodyFor(String method) {
    switch (method) {
      case "GET":
      case "DELETE":
      case "HEAD":
        return BodyPublishers.noBody();
      case "POST":
      case "PUT":
        return BodyPublishers.ofString(POST_BODY);
      default:
        throw new AssertionError("Unknown method:" + method);
    }
  }

  Object[][] variants() {
    var uris = new TestUriSupplierFactory(this);
    return new Object[][] {
      {uris.uriString("httpURI"), "GET", 301, "GET"},
      {uris.uriString("httpURI"), "GET", 302, "GET"},
      {uris.uriString("httpURI"), "GET", 303, "GET"},
      {uris.uriString("httpURI"), "GET", 307, "GET"},
      {uris.uriString("httpURI"), "GET", 308, "GET"},
      {uris.uriString("httpURI"), "POST", 301, "GET"},
      {uris.uriString("httpURI"), "POST", 302, "GET"},
      {uris.uriString("httpURI"), "POST", 303, "GET"},
      {uris.uriString("httpURI"), "POST", 307, "POST"},
      {uris.uriString("httpURI"), "POST", 308, "POST"},
      {uris.uriString("httpURI"), "PUT", 301, "PUT"},
      {uris.uriString("httpURI"), "PUT", 302, "PUT"},
      {uris.uriString("httpURI"), "PUT", 303, "GET"},
      {uris.uriString("httpURI"), "PUT", 307, "PUT"},
      {uris.uriString("httpURI"), "PUT", 308, "PUT"},
      {uris.uriString("httpsURI"), "GET", 301, "GET"},
      {uris.uriString("httpsURI"), "GET", 302, "GET"},
      {uris.uriString("httpsURI"), "GET", 303, "GET"},
      {uris.uriString("httpsURI"), "GET", 307, "GET"},
      {uris.uriString("httpsURI"), "GET", 308, "GET"},
      {uris.uriString("httpsURI"), "POST", 301, "GET"},
      {uris.uriString("httpsURI"), "POST", 302, "GET"},
      {uris.uriString("httpsURI"), "POST", 303, "GET"},
      {uris.uriString("httpsURI"), "POST", 307, "POST"},
      {uris.uriString("httpsURI"), "POST", 308, "POST"},
      {uris.uriString("httpsURI"), "PUT", 301, "PUT"},
      {uris.uriString("httpsURI"), "PUT", 302, "PUT"},
      {uris.uriString("httpsURI"), "PUT", 303, "GET"},
      {uris.uriString("httpsURI"), "PUT", 307, "PUT"},
      {uris.uriString("httpsURI"), "PUT", 308, "PUT"},
      {uris.uriString("http2URI"), "GET", 301, "GET"},
      {uris.uriString("http2URI"), "GET", 302, "GET"},
      {uris.uriString("http2URI"), "GET", 303, "GET"},
      {uris.uriString("http2URI"), "GET", 307, "GET"},
      {uris.uriString("http2URI"), "GET", 308, "GET"},
      {uris.uriString("http2URI"), "POST", 301, "GET"},
      {uris.uriString("http2URI"), "POST", 302, "GET"},
      {uris.uriString("http2URI"), "POST", 303, "GET"},
      {uris.uriString("http2URI"), "POST", 307, "POST"},
      {uris.uriString("http2URI"), "POST", 308, "POST"},
      {uris.uriString("http2URI"), "PUT", 301, "PUT"},
      {uris.uriString("http2URI"), "PUT", 302, "PUT"},
      {uris.uriString("http2URI"), "PUT", 303, "GET"},
      {uris.uriString("http2URI"), "PUT", 307, "PUT"},
      {uris.uriString("http2URI"), "PUT", 308, "PUT"},
      {uris.uriString("https2URI"), "GET", 301, "GET"},
      {uris.uriString("https2URI"), "GET", 302, "GET"},
      {uris.uriString("https2URI"), "GET", 303, "GET"},
      {uris.uriString("https2URI"), "GET", 307, "GET"},
      {uris.uriString("https2URI"), "GET", 308, "GET"},
      {uris.uriString("https2URI"), "POST", 301, "GET"},
      {uris.uriString("https2URI"), "POST", 302, "GET"},
      {uris.uriString("https2URI"), "POST", 303, "GET"},
      {uris.uriString("https2URI"), "POST", 307, "POST"},
      {uris.uriString("https2URI"), "POST", 308, "POST"},
      {uris.uriString("https2URI"), "PUT", 301, "PUT"},
      {uris.uriString("https2URI"), "PUT", 302, "PUT"},
      {uris.uriString("https2URI"), "PUT", 303, "GET"},
      {uris.uriString("https2URI"), "PUT", 307, "PUT"},
      {uris.uriString("https2URI"), "PUT", 308, "PUT"},
    };
  }

  @ParameterizedTest
  @MethodSource("variants")
  void test(Supplier<String> uriString, String method, int redirectCode, String expectedMethod)
      throws Exception {
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(uriString.get()))
            .method(method, getRequestBodyFor(method))
            .header("X-Redirect-Code", Integer.toString(redirectCode))
            .header("X-Expect-Method", expectedMethod)
            .build();
    HttpResponse<String> resp = client.send(req, BodyHandlers.ofString());

    //    System.out.println("Response: " + resp + ", body: " + resp.body());
    assertEquals(resp.statusCode(), 200);
    assertEquals(resp.body(), RESPONSE);
  }

  // -- Infrastructure

  @BeforeEach
  @ExecutorSpec(ExecutorType.CACHED_POOL)
  public void setUp(
      Executor handlerExecutor,
      MockWebServer httpTestServer,
      MockWebServer httpsTestServer,
      MockWebServer http2TestServer,
      MockWebServer https2TestServer) {
    sslContext = TestUtils.localhostSslContext();

    client =
        Methanol.newBuilder()
            .followRedirects(Redirect.NEVER)
            .interceptor(new RedirectingInterceptor(Redirect.NORMAL, handlerExecutor))
            .sslContext(sslContext)
            .build();

    this.httpTestServer = httpTestServer;
    var targetURI = httpTestServer.url("/http1/redirect/rmt").toString();
    var httpTestServerDispatcher = new ScopedDispatcher();
    httpTestServerDispatcher.put("/http1/", new RedirMethodChgeHandler(targetURI));
    httpTestServer.setDispatcher(httpTestServerDispatcher);
    httpURI = httpTestServer.url("/http1/test/rmt").toString();

    httpsTestServer.useHttps(sslContext.getSocketFactory());
    this.httpsTestServer = httpsTestServer;
    targetURI = httpsTestServer.url("/https1/redirect/rmt").toString();
    var httpsTestServerDispatcher = new ScopedDispatcher();
    httpsTestServerDispatcher.put("/https1/", new RedirMethodChgeHandler(targetURI));
    httpsTestServer.setDispatcher(httpsTestServerDispatcher);
    httpsURI = httpsTestServer.url("/https1/test/rmt").toString();

    this.http2TestServer = http2TestServer;
    targetURI = http2TestServer.url("/http2/redirect/rmt").toString();
    var http2TestServerDispatcher = new ScopedDispatcher();
    http2TestServerDispatcher.put("/http2/", new RedirMethodChgeHandler(targetURI));
    http2TestServer.setDispatcher(http2TestServerDispatcher);
    http2URI = http2TestServer.url("/http2/test/rmt").toString();

    https2TestServer.useHttps(sslContext.getSocketFactory());
    this.https2TestServer = https2TestServer;
    targetURI = https2TestServer.url("/https2/redirect/rmt").toString();
    var https2TestServerDispatcher = new ScopedDispatcher();
    https2TestServerDispatcher.put("/https2/", new RedirMethodChgeHandler(targetURI));
    https2TestServer.setDispatcher(https2TestServerDispatcher);
    https2URI = https2TestServer.url("/https2/test/rmt").toString();
  }

  /**
   * Stateful handler.
   *
   * <p>Request to "<protocol>/test/rmt" is first, with the following checked headers:
   * X-Redirect-Code: nnn <the redirect code to send back> X-Expect-Method: the method that the
   * client should use for the next request
   *
   * <p>The following request should be to "<protocol>/redirect/rmt" and should use the method
   * indicated previously. If all ok, return a 200 response. Otherwise 50X error.
   */
  private static class RedirMethodChgeHandler extends Dispatcher {
    private boolean inTest;
    private String expectedMethod;

    private final String targetURL;

    RedirMethodChgeHandler(String targetURL) {
      this.targetURL = targetURL;
    }

    boolean readAndCheckBody(RecordedRequest e, MockResponse.Builder builder) {
      String method = e.getMethod();
      String requestBody = e.getBody() != null ? e.getBody().utf8() : "";
      if ((method.equals("POST") || method.equals("PUT")) && !requestBody.equals(POST_BODY)) {
        Throwable ex =
            new RuntimeException(
                "Unexpected request body for " + method + ": [" + requestBody + "]");
        ex.printStackTrace();
        builder.code(503);
        return false;
      }
      return true;
    }

    @NotNull
    @Override
    public MockResponse dispatch(@NotNull RecordedRequest he) {
      boolean newtest = he.getUrl().encodedPath().endsWith("/test/rmt");
      if ((newtest && inTest) || (!newtest && !inTest)) {
        Throwable ex =
            new RuntimeException(
                "Unexpected newtest:" + newtest + ", inTest:" + inTest + ", for " + he.getUrl());
        ex.printStackTrace();
        return new MockResponse.Builder().code(500).build();
      }

      var builder = new MockResponse.Builder();
      if (newtest) {
        Headers hdrs = he.getHeaders();
        String value = hdrs.get("X-Redirect-Code");
        int redirectCode = Integer.parseInt(value);
        expectedMethod = hdrs.get("X-Expect-Method");
        if (!readAndCheckBody(he, builder)) {
          return builder.build();
        }
        builder.addHeader("Location", targetURL);
        builder.code(redirectCode);
        inTest = true;
      } else {
        // should be the redirect
        if (!he.getUrl().encodedPath().endsWith("/redirect/rmt")) {
          Throwable ex = new RuntimeException("Unexpected redirected request, got:" + he.getUrl());
          ex.printStackTrace();
          builder.code(501);
        } else if (!he.getMethod().equals(expectedMethod)) {
          Throwable ex =
              new RuntimeException("Expected: " + expectedMethod + " Got: " + he.getMethod());
          ex.printStackTrace();
          builder.code(504);
        } else {
          if (!readAndCheckBody(he, builder)) {
            return builder.build();
          }
          builder.code(200);
          builder.body(RESPONSE);
        }
        inTest = false;
      }
      return builder.build();
    }
  }
}
