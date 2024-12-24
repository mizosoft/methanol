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

package com.github.mizosoft.methanol.jdk;

import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.github.mizosoft.methanol.Methanol;
import com.github.mizosoft.methanol.MutableRequest;
import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.RedirectingInterceptor;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorSpec;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.MockWebServerExtension;
import com.github.mizosoft.methanol.testing.TestUtils;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import mockwebserver3.SocketPolicy;
import okhttp3.Headers;
import okhttp3.Protocol;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@ExtendWith({MockWebServerExtension.class, ExecutorExtension.class})
@TestInstance(Lifecycle.PER_CLASS)
class HttpRedirectTest {
  static final String GET_RESPONSE_BODY = "Lorem ipsum dolor sit amet";
  static final String REQUEST_BODY = "Here it goes";
  static final SSLContext context;

  static {
    try {
      context = TestUtils.localhostSslContext();
    } catch (Exception x) {
      throw new ExceptionInInitializerError(x);
    }
  }

  final AtomicLong requestCounter = new AtomicLong();
  MockWebServer http1Server;
  MockWebServer http2Server;
  MockWebServer https1Server;
  MockWebServer https2Server;
  MockWebServer proxy;

  URI http1URI;
  URI https1URI;
  URI http2URI;
  URI https2URI;
  InetSocketAddress proxyAddress;
  ProxySelector proxySelector;
  HttpClient client;

  ExecutorService clientexec;

  private HttpClient newHttpClient(ProxySelector ps) {
    HttpClient.Builder builder =
        Methanol.newBuilder()
            .sslContext(context)
            .executor(clientexec)
            .followRedirects(Redirect.NEVER)
            .interceptor(new RedirectingInterceptor(Redirect.ALWAYS, clientexec))
            .proxy(ps)
            .autoAcceptEncoding(false);
    return builder.build();
  }

  private Object[][] testURIs() {
    var uriFactory = new TestUriSupplierFactory(this);
    List<Supplier<URI>> uris =
        List.of(
            uriFactory.uri("http1URI", "direct/orig/"),
            uriFactory.uri("https1URI", "direct/orig/"),
            uriFactory.uri("https1URI", "proxy/orig/"),
            uriFactory.uri("http2URI", "direct/orig/"),
            uriFactory.uri("https2URI", "direct/orig/"),
            uriFactory.uri("https2URI", "proxy/orig/"));
    List<Map.Entry<Integer, String>> redirects =
        List.of(
            Map.entry(301, "GET"),
            Map.entry(308, "POST"),
            Map.entry(302, "GET"),
            Map.entry(303, "GET"),
            Map.entry(307, "POST"),
            Map.entry(300, "DO_NOT_FOLLOW"),
            Map.entry(304, "DO_NOT_FOLLOW"),
            Map.entry(305, "DO_NOT_FOLLOW"),
            Map.entry(306, "DO_NOT_FOLLOW"),
            Map.entry(309, "DO_NOT_FOLLOW"),
            Map.entry(new Random().nextInt(90) + 310, "DO_NOT_FOLLOW"));
    Object[][] tests = new Object[redirects.size() * uris.size()][3];
    int count = 0;
    for (Supplier<URI> u : uris) {
      for (Map.Entry<Integer, String> redirect : redirects) {
        int code = redirect.getKey();
        String m = redirect.getValue();
        tests[count][0] =
            new Supplier<URI>() {
              @Override
              public URI get() {
                return u.get().resolve(code + "/");
              }

              @Override
              public String toString() {
                return u.toString();
              }
            };
        tests[count][1] = code;
        tests[count][2] = m;
        count++;
      }
    }
    return tests;
  }

  @BeforeEach
  @ExecutorSpec(ExecutorType.CACHED_POOL)
  public void setUp(
      ExecutorService clientexec,
      MockWebServer http1Server,
      MockWebServer https1Server,
      MockWebServer http2Server,
      MockWebServer https2Server,
      MockWebServer proxy)
      throws Exception {
    this.clientexec = clientexec;

    // Prevent upgrading protocol for non-HTTP/2 servers
    http1Server.setProtocols(List.of(Protocol.HTTP_1_1));
    https1Server.setProtocols(List.of(Protocol.HTTP_1_1));

    // HTTP/1.1
    this.http1Server = http1Server;
    var http1ServerDispatcher = new ScopedDispatcher();
    http1ServerDispatcher.put(
        "/HttpRedirectTest/http1/", new HttpTestRedirectDispatcher("http", http1Server));
    http1Server.setDispatcher(http1ServerDispatcher);
    http1Server.start();
    http1URI = http1Server.url("/HttpRedirectTest/http1/").uri();

    // HTTPS/1.1
    https1Server.useHttps(context.getSocketFactory(), false);
    this.https1Server = https1Server;
    var https1ServerDispatcher = new ScopedDispatcher();
    https1ServerDispatcher.put(
        "/HttpRedirectTest/https1/", new HttpTestRedirectDispatcher("https", https1Server));
    https1Server.setDispatcher(https1ServerDispatcher);
    https1Server.start();
    https1URI = https1Server.url("/HttpRedirectTest/https1/").uri();

    // HTTP/2.0
    this.http2Server = http2Server;
    var http2ServerDispatcher = new ScopedDispatcher();
    http2ServerDispatcher.put(
        "/HttpRedirectTest/http2/", new HttpTestRedirectDispatcher("http", http2Server));
    http2Server.setDispatcher(http2ServerDispatcher);
    http2Server.start();
    http2URI = http2Server.url("/HttpRedirectTest/http2/").uri();

    // HTTPS/2.0
    this.https2Server = https2Server;
    https2Server.useHttps(context.getSocketFactory(), false);
    var https2ServerDispatcher = new ScopedDispatcher();
    https2ServerDispatcher.put(
        "/HttpRedirectTest/https2/", new HttpTestRedirectDispatcher("https", https2Server));
    https2Server.setDispatcher(https2ServerDispatcher);
    https2Server.start();
    https2URI = https2Server.url("/HttpRedirectTest/https2/").uri();

    this.proxy = proxy;
    proxy.useHttps(context.getSocketFactory(), true);
    proxy.setDispatcher(new TunnellingProxyDispatcher(clientexec));
    proxyAddress = (InetSocketAddress) proxy.toProxyAddress().address();
    proxySelector = new HttpProxySelector(proxyAddress);
    client = newHttpClient(proxySelector);
    //    System.out.println("Setup: done");
  }

  private void testNonIdempotent(URI u, HttpRequest request, int code, String method)
      throws Exception {
    //    System.out.println("Testing with " + u);
    CompletableFuture<HttpResponse<String>> respCf =
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> resp = respCf.get();
    if (method.equals("DO_NOT_FOLLOW")) {
      assertEquals(code, resp.statusCode(), u + ": status code");
    } else {
      assertEquals(200, resp.statusCode(), u + ": status code");
    }
    if (method.equals("POST")) {
      assertEquals(REQUEST_BODY, resp.body(), u + ": body");
    } else if (code == 304) {
      assertEquals("", resp.body(), u + ": body");
    } else if (method.equals("DO_NOT_FOLLOW")) {
      assertNotEquals(GET_RESPONSE_BODY, resp.body(), u + ": body");
      assertNotEquals(REQUEST_BODY, resp.body(), u + ": body");
    } else {
      assertEquals(GET_RESPONSE_BODY, resp.body(), u + ": body");
    }
  }

  public void testIdempotent(URI u, HttpRequest request, int code, String method) throws Exception {
    CompletableFuture<HttpResponse<String>> respCf =
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> resp = respCf.get();
    if (method.equals("DO_NOT_FOLLOW")) {
      assertEquals(code, resp.statusCode(), u + ": status code");
    } else {
      assertEquals(200, resp.statusCode(), u + ": status code");
    }
    if (method.equals("POST")) {
      assertEquals(REQUEST_BODY, resp.body(), u + ": body");
    } else if (code == 304) {
      assertEquals("", resp.body(), u + ": body");
    } else if (method.equals("DO_NOT_FOLLOW")) {
      assertNotEquals(GET_RESPONSE_BODY, resp.body(), u + ": body");
      assertNotEquals(REQUEST_BODY, resp.body(), u + ": body");
    } else if (code == 303) {
      assertEquals(GET_RESPONSE_BODY, resp.body(), u + ": body");
    } else {
      assertEquals(REQUEST_BODY, resp.body(), u + ": body");
    }
  }

  Object[][] uris() {
    return testURIs();
  }

  @ParameterizedTest
  @MethodSource("uris")
  void testPOST(Supplier<URI> uri, int code, String method) throws Exception {
    URI u = uri.get().resolve("foo?n=" + requestCounter.incrementAndGet());
    HttpRequest request =
        HttpRequest.newBuilder(u).POST(HttpRequest.BodyPublishers.ofString(REQUEST_BODY)).build();
    // POST is not considered idempotent.
    testNonIdempotent(u, request, code, method);
  }

  @ParameterizedTest
  @MethodSource("uris")
  void testPUT(Supplier<URI> uri, int code, String method) throws Exception {
    URI u = uri.get().resolve("foo?n=" + requestCounter.incrementAndGet());
    //    System.out.println("Testing with " + u);
    HttpRequest request =
        HttpRequest.newBuilder(u).PUT(HttpRequest.BodyPublishers.ofString(REQUEST_BODY)).build();
    // PUT is considered idempotent.
    testIdempotent(u, request, code, method);
  }

  @ParameterizedTest
  @MethodSource("uris")
  void testFoo(Supplier<URI> uri, int code, String method) throws Exception {
    URI u = uri.get().resolve("foo?n=" + requestCounter.incrementAndGet());
    //    System.out.println("Testing with " + u);
    HttpRequest request =
        HttpRequest.newBuilder(u)
            .method("FOO", HttpRequest.BodyPublishers.ofString(REQUEST_BODY))
            .build();
    // FOO is considered idempotent.
    testIdempotent(u, request, code, method);
  }

  @ParameterizedTest
  @MethodSource("uris")
  @Disabled("MockWebServer complains about GET requests with bodies")
  void testGet(Supplier<URI> uri, int code, String method) throws Exception {
    URI u = uri.get().resolve("foo?n=" + requestCounter.incrementAndGet());
    //    System.out.println("Testing with " + u);
    HttpRequest request =
        HttpRequest.newBuilder(u)
            .method("GET", HttpRequest.BodyPublishers.ofString(REQUEST_BODY))
            .build();
    CompletableFuture<HttpResponse<String>> respCf =
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> resp = respCf.get();
    // body will be preserved except for 304 and 303: this is a GET.
    if (method.equals("DO_NOT_FOLLOW")) {
      assertEquals(code, resp.statusCode(), u + ": status code");
    } else {
      assertEquals(200, resp.statusCode(), u + ": status code");
    }
    if (code == 304) {
      assertEquals("", resp.body(), u + ": body");
    } else if (method.equals("DO_NOT_FOLLOW")) {
      assertNotEquals(GET_RESPONSE_BODY, resp.body(), u + ": body");
      assertNotEquals(REQUEST_BODY, resp.body(), u + ": body");
    } else if (code == 303) {
      assertEquals(GET_RESPONSE_BODY, resp.body(), u + ": body");
    } else {
      assertEquals(REQUEST_BODY, resp.body(), u + ": body");
    }
  }

  private static class HttpProxySelector extends ProxySelector {
    private static final List<Proxy> NO_PROXY = List.of(Proxy.NO_PROXY);
    private final List<Proxy> proxyList;

    HttpProxySelector(InetSocketAddress proxyAddress) {
      proxyList = List.of(new Proxy(Proxy.Type.HTTP, proxyAddress));
    }

    @Override
    public List<Proxy> select(URI uri) {
      // our proxy only supports tunneling
      if (uri.getScheme().equalsIgnoreCase("https")) {
        if (uri.getPath().contains("/proxy/")) {
          return proxyList;
        }
      }
      return NO_PROXY;
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
      System.err.println("Connection to proxy failed: " + ioe);
      System.err.println("Proxy: " + sa);
      System.err.println("\tURI: " + uri);
      ioe.printStackTrace();
    }
  }

  private static class HttpTestRedirectDispatcher extends Dispatcher {
    final String scheme;
    final MockWebServer server;

    HttpTestRedirectDispatcher(String scheme, MockWebServer server) {
      this.scheme = scheme;
      this.server = server;
    }

    @NotNull
    @Override
    public MockResponse dispatch(@NotNull RecordedRequest t) {
      var mockResponse = new MockResponse();
      try {
        byte[] bytes = t.getBody().readByteArray();
        URI u = t.getRequestUrl().uri();
        long responseID = Long.parseLong(u.getQuery().substring(2));
        String path = u.getPath();
        int i = path.lastIndexOf('/');
        String file = path.substring(i + 1);
        String parent = path.substring(0, i);
        int code = 200;
        if (file.equals("foo")) {
          i = parent.lastIndexOf("/");
          code = Integer.parseInt(parent.substring(i + 1));
        }
        String response;
        if (code == 200) {
          if (t.getMethod().equals("GET")) {
            if (bytes.length == 0) {
              response = GET_RESPONSE_BODY;
            } else {
              response = new String(bytes, StandardCharsets.UTF_8);
            }
          } else if (t.getMethod().equals("POST")) {
            response = new String(bytes, StandardCharsets.UTF_8);
          } else {
            response = new String(bytes, StandardCharsets.UTF_8);
          }
        } else if (code < 300 || code > 399) {
          response = "Unexpected code: " + code;
          code = 400;
        } else {
          try {
            URI reloc =
                new URI(
                    scheme,
                    server.url("/").uri().getAuthority(),
                    parent + "/bar",
                    u.getQuery(),
                    null);
            mockResponse.addHeader("Location", reloc.toASCIIString());
            if (code != 304) {
              response = "Code: " + code;
            } else response = null;
          } catch (URISyntaxException x) {
            x.printStackTrace();
            x.printStackTrace(System.out);
            code = 400;
            response = x.toString();
          }
        }

        //        System.out.println("Server " + t.getRequestUrl() + " sending response " +
        // responseID);
        //        System.out.println("code: " + code + " body: " + response);
        mockResponse.setResponseCode(code);
        if (code != 304) {
          bytes = response.getBytes(StandardCharsets.UTF_8);
          mockResponse.setBody(new okio.Buffer().write(bytes));
        } else {
          bytes = new byte[0];
        }

        //        System.out.println("\tresp:" + responseID + ": wrote " + bytes.length + " bytes");
      } catch (Throwable e) {
        e.printStackTrace();
        e.printStackTrace(System.out);
        throw new RuntimeException(e);
      }
      return mockResponse;
    }
  }

  /** A {@code Dispatcher} that simulates a tunnelling HTTPS proxy. */
  private static final class TunnellingProxyDispatcher extends Dispatcher {
    private static final Set<String> DISALLOWED_HEADERS;

    static {
      var set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
      set.addAll(Set.of("connection", "content-length", "date", "host", "upgrade"));
      DISALLOWED_HEADERS = Collections.unmodifiableSet(set);
    }

    private final HttpClient client;
    private String targetHost;
    private int targetPort;

    private TunnellingProxyDispatcher(Executor clientExecutor) {
      client =
          Methanol.newBuilder()
              .followRedirects(Redirect.NEVER)
              .sslContext(context)
              .executor(clientExecutor)
              .autoAcceptEncoding(false)
              .build();
    }

    @NotNull
    @Override
    public MockResponse dispatch(@NotNull RecordedRequest recordedRequest) {
      var requestLine = recordedRequest.getRequestLine().split("\\s");
      if ("CONNECT".equalsIgnoreCase(recordedRequest.getMethod())) {
        var targetHostAndPort = requestLine[1];
        int delimiterIndex = targetHostAndPort.indexOf(':');
        requireArgument(delimiterIndex != -1, "invalid CONNECT: %s", targetHostAndPort);
        targetHost = targetHostAndPort.substring(0, delimiterIndex);
        targetPort = Integer.parseInt(targetHostAndPort.substring(delimiterIndex + 1));
        //        System.out.println("Tunnelling to: " + targetHostAndPort);

        return new MockResponse();
      } else {
        // Forward the request to target without changing its semantics
        requireState(targetHost != null, "tunnelling proxy not connected");
        var targetUri =
            recordedRequest
                .getRequestUrl()
                .newBuilder()
                .host(targetHost)
                .port(targetPort)
                .build()
                .uri();
        var request =
            MutableRequest.create(targetUri)
                .method(
                    recordedRequest.getMethod(),
                    BodyPublishers.ofByteArray(recordedRequest.getBody().readByteArray()))
                .version(
                    "HTTP/1.1".equalsIgnoreCase(requestLine[2]) ? Version.HTTP_1_1 : Version.HTTP_2)
                .headers(
                    HttpHeaders.of(
                        recordedRequest.getHeaders().toMultimap(),
                        (n, v) -> Utils.isValidToken(n) && !DISALLOWED_HEADERS.contains(n)));
        try {
          var response = client.send(request, BodyHandlers.ofByteArray());
          var mockResponse =
              new MockResponse()
                  .setResponseCode(response.statusCode())
                  .setBody(new okio.Buffer().write(response.body()));
          var headers = new Headers.Builder();
          response.headers().map().forEach((n, vs) -> vs.forEach(v -> headers.add(n, v)));
          return mockResponse.setHeaders(headers.build());
        } catch (IOException | InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }

    @NotNull
    @Override
    public MockResponse peek() {
      return new MockResponse().setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END);
    }
  }
}
