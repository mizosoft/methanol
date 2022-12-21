/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.github.mizosoft.methanol.jdk;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.testng.Assert.assertEquals;

import com.github.mizosoft.methanol.Methanol;
import com.github.mizosoft.methanol.internal.cache.RedirectingInterceptor;
import com.github.mizosoft.methanol.testing.TestUtils;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension.ExecutorConfig;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.junit.MockWebServerExtension;
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
  @ExecutorConfig(ExecutorType.FIXED_POOL)
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

    httpsTestServer.useHttps(sslContext.getSocketFactory(), false);
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

    https2TestServer.useHttps(sslContext.getSocketFactory(), false);
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

    boolean readAndCheckBody(RecordedRequest e, MockResponse mockResponse) {
      String method = e.getMethod();
      String requestBody = e.getBody().readString(US_ASCII);
      if ((method.equals("POST") || method.equals("PUT")) && !requestBody.equals(POST_BODY)) {
        Throwable ex =
            new RuntimeException(
                "Unexpected request body for " + method + ": [" + requestBody + "]");
        ex.printStackTrace();
        mockResponse.setResponseCode(503);
        return false;
      }
      return true;
    }

    @NotNull
    @Override
    public MockResponse dispatch(@NotNull RecordedRequest he) {
      boolean newtest = he.getRequestUrl().encodedPath().endsWith("/test/rmt");
      if ((newtest && inTest) || (!newtest && !inTest)) {
        Throwable ex =
            new RuntimeException(
                "Unexpected newtest:"
                    + newtest
                    + ", inTest:"
                    + inTest
                    + ", for "
                    + he.getRequestUrl());
        ex.printStackTrace();
        return new MockResponse().setResponseCode(500);
      }

      var mockResponse = new MockResponse();
      if (newtest) {
        Headers hdrs = he.getHeaders();
        String value = hdrs.get("X-Redirect-Code");
        int redirectCode = Integer.parseInt(value);
        expectedMethod = hdrs.get("X-Expect-Method");
        if (!readAndCheckBody(he, mockResponse)) {
          return mockResponse;
        }
        mockResponse.addHeader("Location", targetURL);
        mockResponse.setResponseCode(redirectCode);
        inTest = true;
      } else {
        // should be the redirect
        if (!he.getRequestUrl().encodedPath().endsWith("/redirect/rmt")) {
          Throwable ex =
              new RuntimeException("Unexpected redirected request, got:" + he.getRequestUrl());
          ex.printStackTrace();
          mockResponse.setResponseCode(501);
        } else if (!he.getMethod().equals(expectedMethod)) {
          Throwable ex =
              new RuntimeException("Expected: " + expectedMethod + " Got: " + he.getMethod());
          ex.printStackTrace();
          mockResponse.setResponseCode(504);
        } else {
          if (!readAndCheckBody(he, mockResponse)) {
            return mockResponse;
          }
          mockResponse.setResponseCode(200);
          mockResponse.setBody(RESPONSE);
        }
        inTest = false;
      }
      return mockResponse;
    }
  }
}
