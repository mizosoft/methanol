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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.mizosoft.methanol.Methanol.Interceptor;
import com.github.mizosoft.methanol.MethanolTest.RecordingClient;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

class InterceptorTest {

  @Test
  void preDecorationInterceptor() throws Exception {
    var response = new HttpResponseStub<Void>();
    var interceptor = new RecordingInterceptor(response);
    var baseClient = new RecordingClient();
    var client = Methanol.newBuilder(baseClient)
        .interceptor(interceptor)
        .build();
    var request = MutableRequest.GET("")
        .header("Accept", "text/html");
    var handler = BodyHandlers.discarding();
    var receivedResponse = client.send(request, handler);

    assertEquals(1, interceptor.calls);
    assertSame(request, interceptor.request);
    assertSame(handler, interceptor.handler);

    assertSame(response, receivedResponse);
    assertNull(baseClient.request); // no request is forwarded
  }

  @Test
  void preDecorationInterceptorAsync() {
    var response = new HttpResponseStub<Void>();
    var interceptor = new RecordingInterceptor(response);
    var baseClient = new RecordingClient();
    var client = Methanol.newBuilder(baseClient)
        .interceptor(interceptor)
        .build();
    var request = MutableRequest.GET("https://localhost")
        .header("Accept", "text/html");
    var handler = BodyHandlers.discarding();
    var pushHandler = discardingPushHandler();
    var receivedResponse = client.sendAsync(request, handler, pushHandler).join();

    assertEquals(1, interceptor.calls);
    assertSame(request, interceptor.request);
    assertSame(handler, interceptor.handler);
    assertSame(pushHandler, interceptor.pushHandler);

    assertSame(response, receivedResponse);
    assertNull(baseClient.request); // no request is forwarded
  }

  @Test
  void postDecorationInterceptor() throws Exception {
    var response = new HttpResponseStub<Void>();
    var interceptor = new RecordingInterceptor(response);
    var baseClient = new RecordingClient();
    var client = Methanol.newBuilder(baseClient)
        .baseUri("https://localhost")
        .postDecorationInterceptor(interceptor)
        .build();
    var request = MutableRequest.GET("/secret")
        .header("Accept", "text/html");
    var handler = BodyHandlers.discarding();
    var receivedResponse = client.send(request, handler);

    assertEquals(1, interceptor.calls);

    assertNotSame(request, interceptor.request);
    assertNotSame(handler, interceptor.handler);
    assertNull(interceptor.pushHandler);

    // sees applied decorations
    assertEquals("https://localhost/secret", interceptor.request.uri().toString());
    assertTrue(interceptor.request.headers().map().containsKey("Accept-Encoding"));

    assertSame(response, receivedResponse);
    assertNull(baseClient.request); // no request is forwarded
  }

  @Test
  void postDecorationInterceptorAsync() {
    var response = new HttpResponseStub<Void>();
    var interceptor = new RecordingInterceptor(response);
    var baseClient = new RecordingClient();
    var client = Methanol.newBuilder(baseClient)
        .baseUri("https://localhost")
        .postDecorationInterceptor(interceptor)
        .build();
    var request = MutableRequest.GET("/secret")
        .header("Accept", "text/html");
    var handler = BodyHandlers.discarding();
    var pushHandler = PushPromiseHandler.of(
        req -> BodyHandlers.discarding(), new ConcurrentHashMap<>());
    var receivedResponse = client.sendAsync(request, handler, pushHandler).join();

    assertEquals(1, interceptor.calls);

    assertNotSame(request, interceptor.request);
    assertNotSame(handler, interceptor.handler);
    assertNotNull(interceptor.pushHandler);
    assertNotSame(pushHandler, interceptor.pushHandler);

    // sees applied decorations
    assertEquals("https://localhost/secret", interceptor.request.uri().toString());
    assertTrue(interceptor.request.headers().map().containsKey("Accept-Encoding"));

    assertSame(response, receivedResponse);
    assertNull(baseClient.request); // no request is forwarded
  }

  @Test
  void fallThroughInterceptor() throws Exception {
    var baseClient = new RecordingClient();
    var preDecoration = new RecordingInterceptor();
    var postDecoration = new RecordingInterceptor();
    var client = Methanol.newBuilder(baseClient)
        .interceptor(preDecoration)
        .postDecorationInterceptor(postDecoration)
        .build();
    var request = MutableRequest.GET("https://localhost");

    client.send(request, BodyHandlers.discarding());

    assertNotSame(baseClient.request, preDecoration.request);
    assertSame(baseClient.request, postDecoration.request);
    assertNotSame(baseClient.handler, preDecoration.handler);
    assertSame(baseClient.handler, postDecoration.handler);
  }

  @Test
  void fallThroughInterceptorAsync() throws Exception {
    var baseClient = new RecordingClient();
    var preDecoration = new RecordingInterceptor();
    var postDecoration = new RecordingInterceptor();
    var client = Methanol.newBuilder(baseClient)
        .interceptor(preDecoration)
        .postDecorationInterceptor(postDecoration)
        .build();
    var request = MutableRequest.GET("https://localhost");

    client.sendAsync(request, BodyHandlers.discarding(),
        PushPromiseHandler.of(req -> BodyHandlers.discarding(), new ConcurrentHashMap<>())).join();

    assertNotSame(baseClient.request, preDecoration.request);
    assertSame(baseClient.request, postDecoration.request);
    assertNotSame(baseClient.handler, preDecoration.handler);
    assertSame(baseClient.handler, postDecoration.handler);
    assertNotSame(baseClient.pushHandler, preDecoration.pushHandler);
    assertSame(baseClient.pushHandler, postDecoration.pushHandler);
  }

  @Test
  void rewritingInterceptor() throws Exception {
    var baseClient = new RecordingClient();
    var client = Methanol.newBuilder(baseClient)
        .interceptor(Interceptor.create(
            req -> MutableRequest.copyOf(req)
                .method("POST", BodyPublishers.noBody())
                .removeHeader("X-Secret")
                .header("Accept", "text/html")))
        .build();
    var request = MutableRequest.GET("https://localhost")
        .header("Accept-Encoding", "identity")
        .header("X-Secret", "nobody loves dynamic typing");

    client.send(request, BodyHandlers.discarding());

    assertEquals("https://localhost", baseClient.request.uri().toString());
    assertEquals("POST", baseClient.request.method());

    var headers = baseClient.request.headers();
    assertEquals(Optional.of("identity"), headers.firstValue("Accept-Encoding"));
    assertFalse(headers.map().containsKey("X-Secret"));
    assertEquals(Optional.of("text/html"), headers.firstValue("Accept"));
  }

  @Test
  void rewritingInterceptorAsync() throws Exception {
    var baseClient = new RecordingClient();
    var client = Methanol.newBuilder(baseClient)
        .interceptor(Interceptor.create(
            req -> MutableRequest.copyOf(req)
                .method("POST", BodyPublishers.noBody())
                .removeHeader("X-Secret")
                .header("Accept", "text/html")))
        .build();
    var request = MutableRequest.GET("https://localhost")
        .header("Accept-Encoding", "identity")
        .header("X-Secret", "nobody loves dynamic typing");

    client.sendAsync(request, BodyHandlers.discarding()).join();

    assertEquals("https://localhost", baseClient.request.uri().toString());
    assertEquals("POST", baseClient.request.method());

    var headers = baseClient.request.headers();
    assertEquals(Optional.of("identity"), headers.firstValue("Accept-Encoding"));
    assertFalse(headers.map().containsKey("X-Secret"));
    assertEquals(Optional.of("text/html"), headers.firstValue("Accept"));
  }

  private static PushPromiseHandler<Void> discardingPushHandler() {
    return (req1, req2, acc) -> {};
  }

  @SuppressWarnings("unchecked")
  private static class RecordingInterceptor implements Interceptor {

    int calls;
    HttpRequest request;
    BodyHandler<?> handler;
    PushPromiseHandler<?> pushHandler;
    final HttpResponse<?> response;

    RecordingInterceptor() {
      this(null);
    }

    RecordingInterceptor(@Nullable HttpResponse<?> response) {
      this.response = response;
    }

    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
        throws InterruptedException, IOException {
      calls++;
      this.request = request;
      this.handler = chain.bodyHandler();
      this.pushHandler = chain.pushPromiseHandler().orElse(null);
      return response == null ? chain.forward(request) : (HttpResponse<T>) response;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
        HttpRequest request, Chain<T> chain) {
      calls++;
      this.request = request;
      this.handler = chain.bodyHandler();
      this.pushHandler = chain.pushPromiseHandler().orElse(null);
      return response == null
          ? chain.forwardAsync(request)
          : CompletableFuture.completedFuture((HttpResponse<T>) response);
    }
  }
}
