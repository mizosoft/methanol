/*
 * Copyright (c) 2024 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.testing;

import static java.net.HttpURLConnection.HTTP_OK;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.ResponseBuilder;
import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.concurrent.CancellationPropagatingFuture;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.checkerframework.checker.nullness.qual.Nullable;

public class RecordingHttpClient extends HttpClient {
  private final BlockingDeque<Call<?>> calls = new LinkedBlockingDeque<>();
  private final AtomicInteger sendCount = new AtomicInteger();
  private @Nullable Consumer<Call<?>> callHandler;

  public RecordingHttpClient() {}

  public RecordingHttpClient handleCalls(@Nullable Consumer<Call<?>> callHandler) {
    this.callHandler = callHandler;
    return this;
  }

  @SuppressWarnings("unchecked")
  public <T> Call<T> lastCall() {
    assertThat(calls).isNotEmpty();
    return (Call<T>) calls.getLast();
  }

  @SuppressWarnings("unchecked")
  public <T> Call<T> awaitCall() {
    try {
      return (Call<T>) calls.takeLast();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  public int sendCount() {
    return sendCount.get();
  }

  @Override
  public Optional<CookieHandler> cookieHandler() {
    return Optional.empty();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    return Optional.empty();
  }

  @Override
  public Redirect followRedirects() {
    return Redirect.NEVER;
  }

  @Override
  public Optional<ProxySelector> proxy() {
    return Optional.empty();
  }

  @Override
  public SSLContext sslContext() {
    throw new UnsupportedOperationException();
  }

  @Override
  public SSLParameters sslParameters() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<Authenticator> authenticator() {
    return Optional.empty();
  }

  @Override
  public Version version() {
    return Version.HTTP_1_1;
  }

  @Override
  public Optional<Executor> executor() {
    return Optional.empty();
  }

  @Override
  public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> responseBodyHandler)
      throws IOException, InterruptedException {
    sendCount.incrementAndGet();

    var call = new Call<>(request, responseBodyHandler, null);
    if (callHandler != null) {
      callHandler.accept(call);
    }
    calls.add(call);
    return Utils.get(call.future());
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, BodyHandler<T> responseBodyHandler) {
    sendCount.incrementAndGet();

    var call = new Call<>(request, responseBodyHandler, null);
    if (callHandler != null) {
      callHandler.accept(call);
    }
    calls.add(call);
    return call.future();
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      BodyHandler<T> responseBodyHandler,
      PushPromiseHandler<T> pushPromiseHandler) {
    sendCount.incrementAndGet();

    var call = new Call<>(request, responseBodyHandler, pushPromiseHandler);
    if (callHandler != null) {
      callHandler.accept(call);
    }
    calls.add(call);
    return call.future();
  }

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  public static final class Call<T> {
    private final CompletableFuture<HttpResponse<T>> responseFuture =
        CancellationPropagatingFuture.create();
    private final HttpRequest request;
    private final BodyHandler<T> bodyHandler;
    private final Optional<PushPromiseHandler<T>> pushPromiseHandler;

    private Call(
        HttpRequest request,
        BodyHandler<T> bodyHandler,
        @Nullable PushPromiseHandler<T> pushPromiseHandler) {
      this.request = request;
      this.bodyHandler = bodyHandler;
      this.pushPromiseHandler = Optional.ofNullable(pushPromiseHandler);
    }

    public HttpRequest request() {
      return request;
    }

    public BodyHandler<T> bodyHandler() {
      return bodyHandler;
    }

    public Optional<PushPromiseHandler<T>> pushPromiseHandler() {
      return pushPromiseHandler;
    }

    public PushPromiseHandler<T> requiredPushPromiseHandler() {
      return pushPromiseHandler.orElseThrow(AssertionError::new);
    }

    public CompletableFuture<HttpResponse<T>> future() {
      return responseFuture;
    }

    public void complete() {
      complete(TestUtils.EMPTY_BUFFER);
    }

    public void complete(Consumer<? super ResponseBuilder<T>> mutator) {
      complete(
          ResponseBuilder.<T>create()
              .statusCode(HTTP_OK)
              .request(request)
              .uri(request.uri())
              .version(request.version().orElse(HttpClient.Version.HTTP_1_1))
              .apply(mutator)
              .build());
    }

    public void complete(ByteBuffer responseBody) {
      complete(TestUtils.okResponseOf(request, responseBody, bodyHandler));
    }

    public void complete(HttpResponse<T> response) {
      assertThat(responseFuture.complete(response)).isTrue();
    }

    public void completeExceptionally(Throwable exception) {
      assertThat(responseFuture.completeExceptionally(exception)).isTrue();
    }
  }
}
