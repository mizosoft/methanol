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

package com.github.mizosoft.methanol;

import static com.github.mizosoft.methanol.MutableRequest.GET;
import static com.github.mizosoft.methanol.internal.cache.HttpDates.formatHttpDate;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.Methanol.Interceptor;
import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.Store;
import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.cache.Store.EntryReader;
import com.github.mizosoft.methanol.internal.cache.Store.EntryWriter;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorSpec;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.MockClock;
import com.github.mizosoft.methanol.testing.MockWebServerExtension;
import com.github.mizosoft.methanol.testing.MockWebServerExtension.MethanolBuilderFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.QueueDispatcher;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({MockWebServerExtension.class, ExecutorExtension.class})
abstract class AbstractHttpCacheTest {
  Executor executor;
  MethanolBuilderFactory clientBuilderFactory;
  MockWebServer server;
  URI serverUri;
  MockClock clock;
  boolean failOnUnavailableResponses;
  Duration advanceOnSend;

  private Methanol.@Nullable Builder clientBuilder;
  private @Nullable Methanol client;

  @BeforeEach
  @ExecutorSpec(ExecutorType.CACHED_POOL)
  void setUp(Executor executor, MethanolBuilderFactory builderFactory, MockWebServer server) {
    this.executor = executor;
    this.server = server;
    this.serverUri = server.url("/").uri();
    this.clock = new MockClock();
    this.clientBuilderFactory = builderFactory;
    ((QueueDispatcher) server.getDispatcher())
        .setFailFast(new MockResponse().setResponseCode(HTTP_UNAVAILABLE));
  }

  Methanol.Builder resetClientBuilder() {
    failOnUnavailableResponses = true;
    advanceOnSend = Duration.ZERO;
    client = null;
    clientBuilder = newClientBuilder();
    return clientBuilder;
  }

  private Methanol.Builder newClientBuilder() {
    return clientBuilderFactory
        .get()
        .executor(executor)
        .backendInterceptor(
            new Interceptor() {
              @Override
              public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
                  throws IOException, InterruptedException {
                var response = chain.forward(request);
                if (failOnUnavailableResponses) {
                  assertThat(response.statusCode())
                      .withFailMessage(
                          "Server has no queued responses, expected something to be cached?")
                      .isNotEqualTo(HTTP_UNAVAILABLE);
                }
                clock.advance(advanceOnSend);
                return response;
              }

              @Override
              public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
                  HttpRequest request, Chain<T> chain) {
                return chain
                    .forwardAsync(request)
                    .thenApply(
                        response -> {
                          if (failOnUnavailableResponses) {
                            assertThat(response.statusCode())
                                .withFailMessage(
                                    "Server has no queued responses, expected something to be cached?")
                                .isNotEqualTo(HTTP_UNAVAILABLE);
                          }
                          clock.advance(advanceOnSend);
                          return response;
                        });
              }
            });
  }

  private Methanol.Builder clientBuilder() {
    var clientBuilder = this.clientBuilder;
    if (clientBuilder == null) {
      clientBuilder = newClientBuilder();
      this.clientBuilder = clientBuilder;
    }
    return clientBuilder;
  }

  Methanol client() {
    var client = this.client;
    if (client == null) {
      client = clientBuilder().build();
      this.client = client;
    }
    return client;
  }

  HttpResponse<String> send() throws IOException, InterruptedException {
    return send(client(), serverUri);
  }

  HttpResponse<String> send(URI uri) throws IOException, InterruptedException {
    return send(client(), GET(uri));
  }

  HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
    return send(client(), request);
  }

  HttpResponse<String> send(Methanol client) throws IOException, InterruptedException {
    return send(client, serverUri);
  }

  HttpResponse<String> send(Methanol client, URI uri) throws IOException, InterruptedException {
    return send(client, GET(uri));
  }

  HttpResponse<String> send(Methanol client, HttpRequest request)
      throws IOException, InterruptedException {
    return send(client, request, BodyHandlers.ofString(), null);
  }

  HttpResponse<String> send(
      Methanol client,
      HttpRequest request,
      BodyHandler<String> bodyHandler,
      @Nullable PushPromiseHandler<String> pushPromiseHandler)
      throws IOException, InterruptedException {
    return pushPromiseHandler != null
        ? Utils.get(client.sendAsync(request, bodyHandler, pushPromiseHandler))
        : client.send(request, bodyHandler);
  }

  HttpResponse<String> sendUnchecked(HttpRequest request) {
    try {
      return send(request);
    } catch (IOException | InterruptedException e) {
      return Assertions.fail(e);
    }
  }

  static LocalDateTime toUtcDateTime(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  static String instantToHttpDateString(Instant instant) {
    return formatHttpDate(toUtcDateTime(instant));
  }

  static class ForwardingStore implements Store {
    final Store delegate;

    ForwardingStore(Store delegate) {
      this.delegate = requireNonNull(delegate);
    }

    @Override
    public long maxSize() {
      return delegate.maxSize();
    }

    @Override
    public Optional<Viewer> view(String key) throws IOException {
      return delegate.view(key);
    }

    @Override
    public CompletableFuture<Optional<Viewer>> view(String key, Executor executor) {
      return delegate.view(key, executor);
    }

    @Override
    public Optional<Editor> edit(String key) throws IOException {
      return delegate.edit(key);
    }

    @Override
    public CompletableFuture<Optional<Editor>> edit(String key, Executor executor) {
      return delegate.edit(key, executor);
    }

    @Override
    public boolean removeAll(List<String> keys) throws IOException {
      return delegate.removeAll(keys);
    }

    @Override
    public Iterator<Viewer> iterator() throws IOException {
      return delegate.iterator();
    }

    @Override
    public boolean remove(String key) throws IOException {
      return delegate.remove(key);
    }

    @Override
    public void clear() throws IOException {
      delegate.clear();
    }

    @Override
    public long size() throws IOException {
      return delegate.size();
    }

    @Override
    public void dispose() throws IOException {
      delegate.dispose();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }

    @Override
    public void flush() throws IOException {
      delegate.flush();
    }

    @Override
    public String toString() {
      return Utils.forwardingObjectToString(this, delegate);
    }
  }

  static class ForwardingViewer implements Viewer {
    final Viewer delegate;

    ForwardingViewer(Viewer delegate) {
      this.delegate = requireNonNull(delegate);
    }

    @Override
    public String key() {
      return delegate.key();
    }

    @Override
    public ByteBuffer metadata() {
      return delegate.metadata();
    }

    @Override
    public EntryReader newReader() {
      return delegate.newReader();
    }

    @Override
    public long dataSize() {
      return delegate.dataSize();
    }

    @Override
    public long entrySize() {
      return delegate.entrySize();
    }

    @Override
    public Optional<Editor> edit() throws IOException {
      return delegate.edit();
    }

    @Override
    public CompletableFuture<Optional<Editor>> edit(Executor executor) {
      return delegate.edit(executor);
    }

    @Override
    public boolean removeEntry() throws IOException {
      return delegate.removeEntry();
    }

    @Override
    public void close() {
      delegate.close();
    }
  }

  static class ForwardingEditor implements Editor {
    final Editor delegate;

    ForwardingEditor(Editor delegate) {
      this.delegate = requireNonNull(delegate);
    }

    @Override
    public String key() {
      return delegate.key();
    }

    @Override
    public EntryWriter writer() {
      return delegate.writer();
    }

    @Override
    public void commit(ByteBuffer metadata) throws IOException {
      delegate.commit(metadata);
    }

    @Override
    public CompletableFuture<Void> commit(ByteBuffer metadata, Executor executor) {
      return delegate.commit(metadata, executor);
    }

    @Override
    public void close() {
      delegate.close();
    }
  }
}
