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

package com.github.mizosoft.methanol.testing;

import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
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

public class HttpClientStub extends HttpClient {
  public HttpClientStub() {}

  @Override
  public Optional<CookieHandler> cookieHandler() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Redirect followRedirects() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<ProxySelector> proxy() {
    throw new UnsupportedOperationException();
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
    throw new UnsupportedOperationException();
  }

  @Override
  public Version version() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<Executor> executor() {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> responseBodyHandler) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      BodyHandler<T> responseBodyHandler,
      PushPromiseHandler<T> pushPromiseHandler) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, BodyHandler<T> responseBodyHandler) {
    throw new UnsupportedOperationException();
  }
}
