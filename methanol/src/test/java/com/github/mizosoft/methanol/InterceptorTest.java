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
import static com.github.mizosoft.methanol.testutils.Verification.verifyThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.github.mizosoft.methanol.Methanol.Interceptor;
import com.github.mizosoft.methanol.MethanolTest.RecordingClient;
import java.io.IOException;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

class InterceptorTest {
  @Test
  void interceptor() throws Exception {
    var response = new HttpResponseStub<Void>();
    var interceptor = new RecordingInterceptor(response);
    var backend = new RecordingClient();
    var client = Methanol.newBuilder(backend).interceptor(interceptor).build();
    var request = GET("").header("Accept", "text/html");
    var handler = BodyHandlers.discarding();

    client.send(request, handler);
    assertThat(interceptor.calls).isOne();
    assertThat(interceptor.request).isSameAs(request);
    assertThat(interceptor.bodyHandler).isSameAs(handler);

    // No requests are forwarded to backend
    assertThat(backend.request).isNull();
  }

  @Test
  void interceptorAsync() {
    var response = new HttpResponseStub<Void>();
    var interceptor = new RecordingInterceptor(response);
    var backend = new RecordingClient();
    var client = Methanol.newBuilder(backend).interceptor(interceptor).build();
    var request = GET("").header("Accept", "text/html");
    var handler = BodyHandlers.discarding();

    client.sendAsync(request, handler);
    assertThat(interceptor.calls).isOne();
    assertThat(interceptor.request).isSameAs(request);
    assertThat(interceptor.bodyHandler).isSameAs(handler);

    // No requests are forwarded to backend
    assertThat(backend.request).isNull();
  }

  @Test
  void backendInterceptor() throws Exception {
    var response = new HttpResponseStub<Void>();
    var interceptor = new RecordingInterceptor(response);
    var backend = new RecordingClient();
    var client = Methanol.newBuilder(backend)
        .userAgent("Will Smith")
        .baseUri("https://example.com/")
        .defaultHeaders(
            "Accept", "application/json",
            "X-My-Header", "abc")
        .requestTimeout(Duration.ofSeconds(1))
        .backendInterceptor(interceptor)
        .build();
    var request = GET("relative")
        .header("Accept", "text/html")
        .timeout(Duration.ofSeconds(2))
        .version(Version.HTTP_1_1);

    client.send(request,  BodyHandlers.discarding());
    assertThat(interceptor.calls).isOne();
    verifyThat(interceptor.request)
        .hasUri("https://example.com/relative")
        .hasHeadersExactly(
            "User-Agent", "Will Smith",
            "Accept", "text/html", // Request's Accept header is not replaced
            "X-My-Header", "abc",
            "Accept-Encoding", acceptEncodingValue())
        .hasTimeout(Duration.ofSeconds(2)) // Request's timeout is not replaced
        .hasVersion(Version.HTTP_1_1);

    assertThat(interceptor.pushPromiseHandler).isNull();

    // No requests are forwarded to backend
    assertThat(backend.request).isNull();
  }

  @Test
  void backendInterceptorAsync() {
    var response = new HttpResponseStub<Void>();
    var interceptor = new RecordingInterceptor(response);
    var backend = new RecordingClient();
    var client = Methanol.newBuilder(backend)
        .userAgent("Will Smith")
        .baseUri("https://example.com/")
        .defaultHeaders(
            "Accept", "application/json",
            "X-My-Header", "abc")
        .requestTimeout(Duration.ofSeconds(1))
        .backendInterceptor(interceptor)
        .build();
    var request = GET("relative")
        .header("Accept", "text/html")
        .timeout(Duration.ofSeconds(2))
        .version(Version.HTTP_1_1);
    var handler = BodyHandlers.discarding();

    client.sendAsync(request, handler);
    assertThat(interceptor.calls).isOne();
    verifyThat(interceptor.request)
        .hasUri("https://example.com/relative")
        .hasHeadersExactly(
            "User-Agent", "Will Smith",
            "Accept", "text/html", // Request's Accept header is not replaced
            "X-My-Header", "abc",
            "Accept-Encoding", acceptEncodingValue())
        .hasTimeout(Duration.ofSeconds(2)) // Request's timeout is not replaced
        .hasVersion(Version.HTTP_1_1);

    assertThat(interceptor.pushPromiseHandler).isNull();

    // No requests are forwarded to backend
    assertThat(backend.request).isNull();
  }

  @Test
  void requestMutatingInterceptor() throws Exception {
    var mutatingInterceptor = Interceptor.create(
        req -> MutableRequest.copyOf(req)
            .uri(req.uri().resolve("?q=val"))
            .removeHeader("X-My-Header")
            .header("Accept", "text/html")
            .timeout(Duration.ofSeconds(1)));
    var recordingInterceptor = new RecordingInterceptor(new HttpResponseStub<>());
    var client = Methanol.newBuilder()
        .interceptor(mutatingInterceptor)
        .interceptor(recordingInterceptor)
        .build();
    var request = GET("https:/example.com/").headers("X-My-Header", "abc");

    client.send(request, BodyHandlers.discarding());
    verifyThat(recordingInterceptor.request)
        .hasUri("https:/example.com/?q=val")
        .hasHeadersExactly("Accept", "text/html")
        .hasTimeout(Duration.ofSeconds(1));

    client.sendAsync(request, BodyHandlers.discarding());
    verifyThat(recordingInterceptor.request)
        .hasUri("https:/example.com/?q=val")
        .hasHeadersExactly("Accept", "text/html")
        .hasTimeout(Duration.ofSeconds(1));
  }

  @Test
  void requestMutatingBackendInterceptor() throws Exception {
    var mutatingInterceptor = Interceptor.create(
        req -> MutableRequest.copyOf(req)
            .uri(req.uri().resolve("?q=val"))
            .removeHeader("X-My-Header")
            .header("Accept", "text/html")
            .timeout(Duration.ofSeconds(1)));
    var backend = new RecordingClient();
    var client = Methanol.newBuilder(backend)
        .backendInterceptor(mutatingInterceptor)
        .build();
    var request = GET("https:/example.com/").headers("X-My-Header", "abc");

    client.send(request, BodyHandlers.discarding());
    verifyThat(backend.request)
        .hasUri("https:/example.com/?q=val")
        .hasHeadersExactly(
            "Accept", "text/html",
            "Accept-Encoding", acceptEncodingValue())
        .hasTimeout(Duration.ofSeconds(1));

    client.sendAsync(request, BodyHandlers.discarding());
    verifyThat(backend.request)
        .hasUri("https:/example.com/?q=val")
        .hasHeadersExactly(
            "Accept", "text/html",
            "Accept-Encoding", acceptEncodingValue())
        .hasTimeout(Duration.ofSeconds(1));
  }

  @Test
  void completeWithNullResponse() {
    var interceptor = new Interceptor() {
      @Override
      public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain) {
        return null;
      }

      @Override
      public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
          HttpRequest request, Chain<T> chain) {
        return CompletableFuture.completedFuture(null);
      }
    };
    var client = Methanol.newBuilder(new RecordingClient()).interceptor(interceptor).build();
    var request = GET("https://localhost");

    assertThatExceptionOfType(NullPointerException.class)
        .isThrownBy(() -> client.send(request, BodyHandlers.discarding()));
    assertThat(client.sendAsync(request, BodyHandlers.discarding()))
        .isCompletedExceptionally()
        .failsWithin(Duration.ZERO)
        .withThrowableOfType(ExecutionException.class)
        .withCauseInstanceOf(NullPointerException.class);
  }

  private static final class TaggedInterceptor extends RecordingInterceptor {
    private final AtomicInteger tagger;
    private final AtomicInteger asyncTagger;

    private int tag;
    private int asyncTag;

    TaggedInterceptor(
        @Nullable HttpResponseStub<?> response, AtomicInteger tagger, AtomicInteger asyncTagger) {
      super(response);
      this.tagger = tagger;
      this.asyncTagger = asyncTagger;
    }

    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
        throws IOException, InterruptedException {
      tag = tagger.incrementAndGet();
      return super.intercept(request, chain);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
        HttpRequest request, Chain<T> chain) {
      asyncTag = asyncTagger.incrementAndGet();
      return super.interceptAsync(request, chain);
    }

    void assertTag(int expected) {
      assertThat(tag).isEqualTo(expected);
    }

    void assertAsyncTag(int expected) {
      assertThat(asyncTag).isEqualTo(expected);
    }
  }

  @Test
  void invocationOrder() throws Exception {
    var tagger = new AtomicInteger();
    var asyncTagger = new AtomicInteger();
    var firstInterceptor = new TaggedInterceptor(null, tagger, asyncTagger);
    var secondInterceptor = new TaggedInterceptor(null, tagger, asyncTagger);
    var thirdInterceptor = new TaggedInterceptor(null, tagger, asyncTagger);
    var fourthInterceptor = new TaggedInterceptor(new HttpResponseStub<>(), tagger, asyncTagger);
    var client = Methanol.newBuilder()
        .interceptor(firstInterceptor)
        .interceptor(secondInterceptor)
        .backendInterceptor(thirdInterceptor)
        .backendInterceptor(fourthInterceptor)
        .build();

    client.send(GET(""), BodyHandlers.discarding());
    firstInterceptor.assertTag(1);
    secondInterceptor.assertTag(2);
    thirdInterceptor.assertTag(3);
    fourthInterceptor.assertTag(4);

    client.sendAsync(GET(""), BodyHandlers.discarding());
    firstInterceptor.assertAsyncTag(1);
    secondInterceptor.assertAsyncTag(2);
    thirdInterceptor.assertAsyncTag(3);
    fourthInterceptor.assertAsyncTag(4);
  }

  @Test
  @SuppressWarnings("unchecked")
  void withBodyHandler() throws Exception {
    var bodyHandler = BodyHandlers.discarding();
    var interceptor = new Interceptor() {
      @Override
      public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
          throws IOException, InterruptedException {
        return chain.withBodyHandler((BodyHandler<T>) bodyHandler).forward(request);
      }

      @Override
      public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
          HttpRequest request, Chain<T> chain) {
        return chain.withBodyHandler((BodyHandler<T>) bodyHandler).forwardAsync(request);
      }
    };
    var recordingInterceptor = new RecordingInterceptor(new HttpResponseStub<>());
    var client = Methanol.newBuilder()
        .interceptor(interceptor)
        .interceptor(recordingInterceptor)
        .build();

    client.send(GET(""), BodyHandlers.discarding());
    assertThat(recordingInterceptor.bodyHandler).isSameAs(bodyHandler);

    client.sendAsync(GET(""), BodyHandlers.discarding());
    assertThat(recordingInterceptor.bodyHandler).isSameAs(bodyHandler);
  }

  @Test
  @SuppressWarnings("unchecked")
  void withPushPromiseHandler() throws Exception {
    PushPromiseHandler<Void> pushPromiseHandler = (__, ___, ____) -> {};
    var interceptor = new Interceptor() {
      @Override
      public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
          throws IOException, InterruptedException {
        return chain
            .withPushPromiseHandler((PushPromiseHandler<T>) pushPromiseHandler)
            .forward(request);
      }

      @Override
      public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
          HttpRequest request, Chain<T> chain) {
        return chain
            .withPushPromiseHandler((PushPromiseHandler<T>) pushPromiseHandler)
            .forwardAsync(request);
      }
    };
    var recordingInterceptor = new RecordingInterceptor(new HttpResponseStub<>());
    var client = Methanol.newBuilder()
        .interceptor(interceptor)
        .interceptor(recordingInterceptor)
        .build();

    client.send(GET(""), BodyHandlers.discarding());
    assertThat(recordingInterceptor.pushPromiseHandler).isSameAs(pushPromiseHandler);

    client.sendAsync(GET(""), BodyHandlers.discarding());
    assertThat(recordingInterceptor.pushPromiseHandler).isSameAs(pushPromiseHandler);
  }

  @Test
  void taggingRequests() throws Exception {
    var backend = new RecordingClient();
    var clientInterceptorRequest = new AtomicReference<HttpRequest>();
    var backendInterceptorRequest = new AtomicReference<HttpRequest>();
    var clientInterceptor = Interceptor.create(request -> {
      clientInterceptorRequest.set(request);
      return MutableRequest.copyOf(request).tag(Integer.class, 1);
    });
    var backendInterceptor = Interceptor.create(request -> {
      backendInterceptorRequest.set(request);
      return MutableRequest.copyOf(request).tag(String.class, "a");
    });
    var client = Methanol.newBuilder(backend)
        .interceptor(clientInterceptor)
        .backendInterceptor(backendInterceptor)
        .build();
    var request = MutableRequest.create().tag(Double.class, 1.0);

    client.send(request, BodyHandlers.discarding());
    verifyThat(clientInterceptorRequest.get())
        .containsTag(Double.class, 1.0);
    verifyThat(backendInterceptorRequest.get())
        .containsTag(Double.class, 1.0)
        .containsTag(Integer.class, 1);
    verifyThat(backend.request)
        .containsTag(Double.class, 1.0)
        .containsTag(Integer.class, 1)
        .containsTag(String.class, "a");

    client.sendAsync(request, BodyHandlers.discarding()).join();
    verifyThat(clientInterceptorRequest.get())
        .containsTag(Double.class, 1.0);
    verifyThat(backendInterceptorRequest.get())
        .containsTag(Double.class, 1.0)
        .containsTag(Integer.class, 1);
    verifyThat(backend.request)
        .containsTag(Double.class, 1.0)
        .containsTag(Integer.class, 1)
        .containsTag(String.class, "a");
  }

  private static String acceptEncodingValue() {
    return String.join(", ", BodyDecoder.Factory.installedBindings().keySet());
  }

  @SuppressWarnings("unchecked")
  private static class RecordingInterceptor implements Interceptor {
    int calls;
    HttpRequest request;
    BodyHandler<?> bodyHandler;
    PushPromiseHandler<?> pushPromiseHandler;
    final HttpResponse<?> response;

    RecordingInterceptor(@Nullable HttpResponse<?> response) {
      this.response = response;
    }

    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
        throws InterruptedException, IOException {
      calls++;
      this.request = request;
      this.bodyHandler = chain.bodyHandler();
      this.pushPromiseHandler = chain.pushPromiseHandler().orElse(null);
      return response == null ? chain.forward(request) : (HttpResponse<T>) response;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
        HttpRequest request, Chain<T> chain) {
      calls++;
      this.request = request;
      this.bodyHandler = chain.bodyHandler();
      this.pushPromiseHandler = chain.pushPromiseHandler().orElse(null);
      return response == null
          ? chain.forwardAsync(request)
          : CompletableFuture.completedFuture((HttpResponse<T>) response);
    }
  }
}
