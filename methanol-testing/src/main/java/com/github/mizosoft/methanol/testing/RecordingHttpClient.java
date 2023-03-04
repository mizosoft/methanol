/*
 * Copyright (c) 2023 Moataz Abdelnasser
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

import static com.github.mizosoft.methanol.testing.TestUtils.headers;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.ResponseBuilder;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class RecordingHttpClient extends HttpClient {
  private final BlockingDeque<Call<?>> calls = new LinkedBlockingDeque<>();
  private final AtomicInteger sendCount = new AtomicInteger();

  public RecordingHttpClient() {}

  public void completeLastCall() {
    var call = lastCall();
    call.future().complete(defaultResponseFor(call.request()));
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
    calls.add(call);

    try {
      return call.future().get();
    } catch (ExecutionException e) {
      var cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      } else if (cause instanceof Error) {
        throw (Error) cause;
      } else if (cause instanceof IOException) {
        throw (IOException) cause;
      } else if (cause instanceof InterruptedException) {
        throw (InterruptedException) cause;
      } else {
        throw new IOException(cause);
      }
    }
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, BodyHandler<T> responseBodyHandler) {
    sendCount.incrementAndGet();

    var call = new Call<>(request, responseBodyHandler, null);
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
    calls.add(call);
    return call.future();
  }

  public static <T> HttpResponse<T> defaultResponseFor(HttpRequest request) {
    return new ResponseBuilder<T>()
        .statusCode(HTTP_OK)
        .request(request)
        .uri(request.uri())
        .version(Version.HTTP_1_1)
        .build();
  }

  public static <T> HttpResponse<T> defaultResponseFor(
      HttpRequest request, ByteBuffer responseBody, BodyHandler<T> bodyHandler) {
    return new ResponseBuilder<T>()
        .statusCode(HTTP_OK)
        .request(request)
        .uri(request.uri())
        .version(Version.HTTP_1_1)
        .body(decodeBody(responseBody, bodyHandler))
        .build();
  }

  private static <T> T decodeBody(ByteBuffer responseBody, BodyHandler<T> bodyHandler) {
    var subscriber =
        bodyHandler.apply(new ImmutableResponseInfo(HTTP_OK, headers(), Version.HTTP_1_1));
    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    subscriber.onNext(List.of(responseBody));
    subscriber.onComplete();
    return subscriber.getBody().toCompletableFuture().join();
  }

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  public static final class Call<T> {
    private final HttpRequest request;
    private final BodyHandler<T> bodyHandler;
    private final Optional<PushPromiseHandler<T>> pushPromiseHandler;
    private final CompletableFuture<HttpResponse<T>> responseFuture = new CompletableFuture<>();

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
      assertThat(responseFuture.complete(defaultResponseFor(request))).isTrue();
    }

    public void complete(ByteBuffer responseBody) {
      assertThat(responseFuture.complete(defaultResponseFor(request, responseBody, bodyHandler)))
          .isTrue();
    }

    public void completeExceptionally(Throwable exception) {
      assertThat(responseFuture.completeExceptionally(exception)).isTrue();
    }
  }
}
