/*
 * Copyright (c) 2022 Moataz Abdelnasser
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

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class RecordingHttpClient extends HttpClient {
  private @MonotonicNonNull CallRecord<?> latestCall;
  private boolean completeCallsEagerly;

  public RecordingHttpClient() {}

  public void completeCallsEagerly(boolean on) {
    completeCallsEagerly = on;
  }

  public void completeLatestCall() {
    var call = latestCall();
    call.future().complete(new FakeHttpResponse<>(call.request()));
  }

  public CallRecord<?> latestCall() {
    var call = latestCall;
    if (call == null) {
      throw new AssertionError("received no requests");
    }
    return call;
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
    var call = new CallRecord<>(request, responseBodyHandler, null);
    if (completeCallsEagerly) {
      call.future().complete(new FakeHttpResponse<>(request));
    }
    latestCall = call;
    return call.future().join();
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, BodyHandler<T> responseBodyHandler) {
    var call = new CallRecord<>(request, responseBodyHandler, null);
    if (completeCallsEagerly) {
      call.future().complete(new FakeHttpResponse<>(request));
    }
    latestCall = call;
    return call.future();
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      BodyHandler<T> responseBodyHandler,
      PushPromiseHandler<T> pushPromiseHandler) {
    var call = new CallRecord<>(request, responseBodyHandler, pushPromiseHandler);
    if (completeCallsEagerly) {
      call.future().complete(new FakeHttpResponse<>(request));
    }
    latestCall = call;
    return call.future();
  }

  public static final class CallRecord<T> {
    private final HttpRequest request;
    private final BodyHandler<T> bodyHandler;
    private final @Nullable PushPromiseHandler<T> pushPromiseHandler;
    private final CompletableFuture<HttpResponse<T>> responseFuture = new CompletableFuture<>();

    private CallRecord(
        HttpRequest request,
        BodyHandler<T> bodyHandler,
        @Nullable PushPromiseHandler<T> pushPromiseHandler) {
      this.request = request;
      this.bodyHandler = bodyHandler;
      this.pushPromiseHandler = pushPromiseHandler;
    }

    public HttpRequest request() {
      return request;
    }

    public BodyHandler<T> bodyHandler() {
      return bodyHandler;
    }

    public PushPromiseHandler<T> pushPromiseHandler() {
      return pushPromiseHandler;
    }

    public CompletableFuture<HttpResponse<T>> future() {
      return responseFuture;
    }
  }

  private static final class FakeHttpResponse<T> implements HttpResponse<T> {
    private final HttpRequest request;

    FakeHttpResponse(HttpRequest request) {
      this.request = request;
    }

    @Override
    public int statusCode() {
      return HTTP_OK;
    }

    @Override
    public HttpRequest request() {
      return request;
    }

    @Override
    public Optional<HttpResponse<T>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
      return TestUtils.headers(/* empty */ );
    }

    @Override
    public T body() {
      return null;
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return request.uri();
    }

    @Override
    public Version version() {
      return request.version().orElse(Version.HTTP_1_1);
    }
  }
}
