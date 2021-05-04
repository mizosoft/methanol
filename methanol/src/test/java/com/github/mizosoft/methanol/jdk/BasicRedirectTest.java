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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.mizosoft.methanol.Methanol;
import com.github.mizosoft.methanol.internal.cache.RedirectingInterceptor;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorConfig;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.MockWebServerExtension;
import com.github.mizosoft.methanol.testutils.TestUtils;
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
import okhttp3.Protocol;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@ExtendWith({MockWebServerExtension.class, ExecutorExtension.class})
@TestInstance(Lifecycle.PER_CLASS)
class BasicRedirectTest {
  SSLContext sslContext;

  MockWebServer httpTestServer; // HTTP/1.1    [ 4 servers ]
  MockWebServer httpsTestServer; // HTTPS/1.1
  MockWebServer http2TestServer; // HTTP/2 ( h2c )
  MockWebServer https2TestServer; // HTTP/2 ( h2  )

  String httpURI;
  String httpURIToMoreSecure; // redirects HTTP to HTTPS
  String httpsURI;
  String httpsURIToLessSecure; // redirects HTTPS to HTTP
  String http2URI;
  String http2URIToMoreSecure; // redirects HTTP to HTTPS
  String https2URI;
  String https2URIToLessSecure; // redirects HTTPS to HTTP

  static final String MESSAGE = "Is fearr Gaeilge briste, na Bearla cliste";
  static final int ITERATIONS = 3;

  Object[][] positive() {
    var uris = new TestUriSupplierFactory(this);
    return new Object[][] {
      {uris.uriString("httpURI"), Redirect.ALWAYS},
      {uris.uriString("httpsURI"), Redirect.ALWAYS},
      {uris.uriString("http2URI"), Redirect.ALWAYS},
      {uris.uriString("https2URI"), Redirect.ALWAYS},
      {uris.uriString("httpURIToMoreSecure"), Redirect.ALWAYS},
      {uris.uriString("http2URIToMoreSecure"), Redirect.ALWAYS},
      {uris.uriString("httpsURIToLessSecure"), Redirect.ALWAYS},
      {uris.uriString("https2URIToLessSecure"), Redirect.ALWAYS},
      {uris.uriString("httpURI"), Redirect.NORMAL},
      {uris.uriString("httpsURI"), Redirect.NORMAL},
      {uris.uriString("http2URI"), Redirect.NORMAL},
      {uris.uriString("https2URI"), Redirect.NORMAL},
      {uris.uriString("httpURIToMoreSecure"), Redirect.NORMAL},
      {uris.uriString("http2URIToMoreSecure"), Redirect.NORMAL},
    };
  }

  @ParameterizedTest
  @MethodSource("positive")
  @ExecutorConfig(ExecutorType.FIXED_POOL)
  void test(Supplier<String> uriString, Redirect redirectPolicy, Executor handlerExecutor)
      throws Exception {
    //    out.printf("%n---- starting positive (%s, %s) ----%n", uriString, redirectPolicy);
    HttpClient client =
        Methanol.newBuilder()
            .followRedirects(Redirect.NEVER)
            .backendInterceptor(new RedirectingInterceptor(redirectPolicy, handlerExecutor))
            .autoAcceptEncoding(false) // Don't add Accept-Encoding as it messes with the tests
            .sslContext(sslContext)
            .build();

    URI uri = URI.create(uriString.get());
    HttpRequest request = HttpRequest.newBuilder(uri).build();
    //    out.println("Initial request: " + request.uri());

    for (int i = 0; i < ITERATIONS; i++) {
      //      out.println("iteration: " + i);
      HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

      //      out.println("  Got response: " + response);
      //      out.println("  Got body Path: " + response.body());
      //      out.println("  Got response.request: " + response.request());

      assertEquals(200, response.statusCode());
      assertEquals(MESSAGE, response.body());
      // asserts redirected URI in response.request().uri()
      assertTrue(response.uri().getPath().endsWith("message"));
      assertPreviousRedirectResponses(request, response);
    }
  }

  private static void assertPreviousRedirectResponses(
      HttpRequest initialRequest, HttpResponse<?> finalResponse) {
    // there must be at least one previous response
    finalResponse
        .previousResponse()
        .orElseThrow(() -> new RuntimeException("no previous response"));

    HttpResponse<?> response = finalResponse;
    do {
      URI uri = response.uri();
      response = response.previousResponse().get();
      assertTrue(
          300 <= response.statusCode() && response.statusCode() <= 309,
          "Expected 300 <= code <= 309, got:" + response.statusCode());
      assertNull(response.body(), "Unexpected body: " + response.body());
      String locationHeader =
          response
              .headers()
              .firstValue("Location")
              .orElseThrow(() -> new RuntimeException("no previous Location"));
      assertTrue(
          uri.toString().endsWith(locationHeader), "URI: " + uri + ", Location: " + locationHeader);

    } while (response.previousResponse().isPresent());

    // initial
    assertEquals(
        initialRequest,
        response.request(),
        String.format(
            "Expected initial request [%s] to equal last prev req [%s]",
            initialRequest, response.request()));
  }

  // --  negatives

  Object[][] negative() {
    var uris = new TestUriSupplierFactory(this);
    return new Object[][] {
      {uris.uriString("httpURI"), Redirect.NEVER},
      {uris.uriString("httpsURI"), Redirect.NEVER},
      {uris.uriString("http2URI"), Redirect.NEVER},
      {uris.uriString("https2URI"), Redirect.NEVER},
      {uris.uriString("httpURIToMoreSecure"), Redirect.NEVER},
      {uris.uriString("http2URIToMoreSecure"), Redirect.NEVER},
      {uris.uriString("httpsURIToLessSecure"), Redirect.NEVER},
      {uris.uriString("https2URIToLessSecure"), Redirect.NEVER},
      {uris.uriString("httpsURIToLessSecure"), Redirect.NORMAL},
      {uris.uriString("https2URIToLessSecure"), Redirect.NORMAL},
    };
  }

  @ParameterizedTest
  @MethodSource("negative")
  @ExecutorConfig(ExecutorType.FIXED_POOL)
  void testNegatives(Supplier<String> uriString, Redirect redirectPolicy, Executor handlerExecutor)
      throws Exception {
    //    out.printf("%n---- starting negative (%s, %s) ----%n", uriString, redirectPolicy);
    HttpClient client =
        Methanol.newBuilder()
            .followRedirects(Redirect.NEVER)
            .backendInterceptor(new RedirectingInterceptor(redirectPolicy, handlerExecutor))
            .autoAcceptEncoding(false) // Don't add Accept-Encoding as it messes with the tests
            .sslContext(sslContext)
            .build();

    URI uri = URI.create(uriString.get());
    HttpRequest request = HttpRequest.newBuilder(uri).build();
    //    out.println("Initial request: " + request.uri());

    for (int i = 0; i < ITERATIONS; i++) {
      //      out.println("iteration: " + i);
      HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

      //      out.println("  Got response: " + response);
      //      out.println("  Got body Path: " + response.body());
      //      out.println("  Got response.request: " + response.request());

      assertEquals(302, response.statusCode());
      assertEquals("XY", response.body());
      // asserts original URI in response.request().uri()
      assertEquals(uri, response.uri());
      assertFalse(response.previousResponse().isPresent());
    }
  }

  // -- Infrastructure

  @BeforeEach
  void setUp(
      MockWebServer httpTestServer,
      MockWebServer httpsTestServer,
      MockWebServer http2TestServer,
      MockWebServer https2TestServer) {
    sslContext = TestUtils.localhostSslContext();

    // Prevent upgrading protocol for non-HTTP/2 servers
    httpTestServer.setProtocols(List.of(Protocol.HTTP_1_1));
    httpsTestServer.setProtocols(List.of(Protocol.HTTP_1_1));

    this.httpTestServer = httpTestServer;
    var httpTestServerDispatcher = new ScopedDispatcher();
    httpTestServerDispatcher.put("/http1/same/", new BasicHttpRedirectDispatcher());
    httpTestServer.setDispatcher(httpTestServerDispatcher);
    httpURI = httpTestServer.url("/http1/same/redirect").toString();
    httpsTestServer.useHttps(sslContext.getSocketFactory(), false);
    this.httpsTestServer = httpsTestServer;
    var httpsTestServerDispatcher = new ScopedDispatcher();
    httpsTestServerDispatcher.put("/https1/same/", new BasicHttpRedirectDispatcher());
    httpsTestServer.setDispatcher(httpsTestServerDispatcher);
    httpsURI = httpsTestServer.url("/https1/same/redirect").toString();

    this.http2TestServer = http2TestServer;
    var http2TestServerDispatcher = new ScopedDispatcher();
    http2TestServerDispatcher.put("/http2/same/", new BasicHttpRedirectDispatcher());
    http2TestServer.setDispatcher(http2TestServerDispatcher);
    http2URI = http2TestServer.url("/http2/same/redirect").toString();
    https2TestServer.useHttps(sslContext.getSocketFactory(), false);
    this.https2TestServer = https2TestServer;
    var https2TestServerDispatcher = new ScopedDispatcher();
    https2TestServerDispatcher.put("/https2/same/", new BasicHttpRedirectDispatcher());
    https2TestServer.setDispatcher(https2TestServerDispatcher);
    https2URI = https2TestServer.url("/https2/same/redirect").toString();

    // HTTP to HTTPS redirect handler
    httpTestServerDispatcher.put("/http1/toSecure/", new ToSecureHttpRedirectDispatcher(httpsURI));
    httpURIToMoreSecure = httpTestServer.url("/http1/toSecure/redirect").toString();
    // HTTP2 to HTTP2S redirect handler
    http2TestServerDispatcher.put(
        "/http2/toSecure/", new ToSecureHttpRedirectDispatcher(https2URI));
    http2URIToMoreSecure = http2TestServer.url("/http2/toSecure/redirect").toString();

    // HTTPS to HTTP redirect handler
    httpsTestServerDispatcher.put(
        "/https1/toLessSecure/", new ToLessSecureRedirectDispatcher(httpURI, true));
    httpsURIToLessSecure = httpsTestServer.url("/https1/toLessSecure/redirect").toString();
    // HTTPS2 to HTTP2 redirect handler
    https2TestServerDispatcher.put(
        "/https2/toLessSecure/", new ToLessSecureRedirectDispatcher(http2URI, false));
    https2URIToLessSecure = https2TestServer.url("/https2/toLessSecure/redirect").toString();
  }

  // Redirects to same protocol
  private static class BasicHttpRedirectDispatcher extends Dispatcher {
    BasicHttpRedirectDispatcher() {}

    @NotNull
    @Override
    public MockResponse dispatch(@NotNull RecordedRequest t) {
      //      System.out.println("BasicHttpRedirectHandler for: " + t.getRequestURI());

      var mockResponse = new MockResponse();
      if (t.getRequestUrl().encodedPath().endsWith("redirect")) {
        String url = t.getRequestUrl().resolve("message").toString();
        mockResponse.setHeader("Location", url);
        mockResponse.setResponseCode(302);
        // stuffing some response body
        mockResponse.setBody("XY");
      } else {
        mockResponse.setBody(MESSAGE);
      }
      return mockResponse;
    }
  }

  // Redirects to a, possibly, more secure protocol, (HTTP to HTTPS)
  private static class ToSecureHttpRedirectDispatcher extends Dispatcher {
    private final String targetURL;

    ToSecureHttpRedirectDispatcher(String targetURL) {
      this.targetURL = targetURL;
    }

    @NotNull
    @Override
    public MockResponse dispatch(@NotNull RecordedRequest t) {
      //      System.out.println("ToSecureHttpRedirectHandler for: " + t.getRequestURI());

      MockResponse response = new MockResponse();
      if (t.getRequestUrl().encodedPath().endsWith("redirect")) {
        response.setHeader("Location", targetURL);
        //        System.out.println("ToSecureHttpRedirectHandler redirecting to: " + targetURL);
        response.setResponseCode(302);
        response.setBody("XY");
      } else {
        Throwable ex = new RuntimeException("Unexpected request");
        ex.printStackTrace();
        response.setResponseCode(500);
      }
      return response;
    }
  }

  // Redirects to a, possibly, less secure protocol (HTTPS to HTTP)
  private static class ToLessSecureRedirectDispatcher extends Dispatcher {
    private final String targetURL;
    private final boolean chunked;

    ToLessSecureRedirectDispatcher(String targetURL, boolean chunked) {
      this.targetURL = targetURL;
      this.chunked = chunked;
    }

    @NotNull
    @Override
    public MockResponse dispatch(@NotNull RecordedRequest t) {
      //      System.out.println("ToLessSecureRedirectHandler for: " + t.getRequestURI());

      MockResponse response = new MockResponse();
      if (t.getRequestUrl().encodedPath().endsWith("redirect")) {
        response.setHeader("Location", targetURL);
        //        System.out.println("ToLessSecureRedirectHandler redirecting to: " + targetURL);
        response.setResponseCode(302);
        if (chunked) {
          response.setChunkedBody("XY", 1); // chunked/variable
        } else {
          response.setBody("XY");
        }
      } else {
        Throwable ex = new RuntimeException("Unexpected request");
        ex.printStackTrace();
        response.setResponseCode(500);
      }
      return response;
    }
  }
}
