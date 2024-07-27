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

package com.github.mizosoft.methanol;

import static com.github.mizosoft.methanol.MutableRequest.GET;
import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static com.github.mizosoft.methanol.internal.cache.HttpDates.formatHttpDate;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.github.mizosoft.methanol.Methanol.Interceptor;
import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.CacheWritingPublisher;
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
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.QueueDispatcher;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({MockWebServerExtension.class, ExecutorExtension.class})
abstract class AbstractHttpCacheTest {
  AwaitableExecutor executor;
  Methanol.Builder clientBuilder;
  MockWebServer server;
  URI serverUri;
  MockClock clock;
  EditAwaiter editAwaiter;
  boolean failOnUnavailableResponses = true;
  Duration advanceOnSend = Duration.ZERO;

  Methanol client; // Must be set by subclass to apply cache setup.

  @BeforeEach
  @ExecutorSpec(ExecutorType.CACHED_POOL)
  void setUp(Executor executor, Methanol.Builder builder, MockWebServer server) {
    this.executor = new AwaitableExecutor(executor);
    this.server = server;
    this.serverUri = server.url("/").uri();
    this.clock = new MockClock();
    this.editAwaiter = new EditAwaiter();
    this.clientBuilder =
        builder
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

    ((QueueDispatcher) server.getDispatcher())
        .setFailFast(new MockResponse().setResponseCode(HTTP_UNAVAILABLE));
  }

  HttpResponse<String> send() throws IOException, InterruptedException {
    return send(client, serverUri);
  }

  HttpResponse<String> send(URI uri) throws IOException, InterruptedException {
    return send(client, GET(uri));
  }

  HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
    return send(client, request);
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
    var response =
        pushPromiseHandler != null
            ? Utils.get(client.sendAsync(request, bodyHandler, pushPromiseHandler))
            : client.send(request, bodyHandler);
    executor.await();
    editAwaiter.await();
    return response;
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

  /**
   * An object that allows awaiting ongoing edits. By design, {@link CacheWritingPublisher} doesn't
   * make downstream completion wait for the entire body to be written to cache. So if writes take
   * time, the response entry is committed a while after the response is completed. This however
   * agitates tests as they expect things to happen sequentially. This is solved by waiting for all
   * open editors to close after each client.send(...).
   */
  static final class EditAwaiter {
    private final Phaser phaser = new Phaser(1); // Register self.

    EditAwaiter() {}

    Editor register(Editor editor) {
      return new NotifyingEditor(editor, phaser);
    }

    void await() {
      try {
        phaser.awaitAdvanceInterruptibly(phaser.arrive(), 5, TimeUnit.SECONDS);
      } catch (InterruptedException | TimeoutException e) {
        fail("Timed out / interrupted while waiting for editors to be closed", e);
      }
    }

    /** An Editor that notifies (arrives at) a Phaser when closed or committed. */
    private static final class NotifyingEditor extends ForwardingEditor {
      private final Phaser phaser;
      private final AtomicBoolean closed = new AtomicBoolean();

      NotifyingEditor(Editor delegate, Phaser phaser) {
        super(delegate);
        this.phaser = phaser;
        requireState(phaser.register() >= 0, "Phaser terminated");
      }

      @Override
      public void close() {
        try {
          super.close();
        } finally {
          if (closed.compareAndSet(false, true)) {
            phaser.arriveAndDeregister();
          }
        }
      }
    }
  }

  static final class EditAwaitableStore extends ForwardingStore {
    private final EditAwaiter editAwaiter;

    EditAwaitableStore(Store delegate, EditAwaiter editAwaiter) {
      super(delegate);
      this.editAwaiter = requireNonNull(editAwaiter);
    }

    @Override
    public Optional<Viewer> view(String key) throws IOException {
      return super.view(key).map(EditAwaitableViewer::new);
    }

    @Override
    public CompletableFuture<Optional<Viewer>> view(String key, Executor executor) {
      return super.view(key, executor).thenApply(viewer -> viewer.map(EditAwaitableViewer::new));
    }

    @Override
    public Optional<Editor> edit(String key) throws IOException {
      return super.edit(key).map(editAwaiter::register);
    }

    @Override
    public CompletableFuture<Optional<Editor>> edit(String key, Executor executor) {
      return super.edit(key, executor).thenApply(editor -> editor.map(editAwaiter::register));
    }

    @Override
    public String toString() {
      return delegate.toString();
    }

    private final class EditAwaitableViewer extends ForwardingViewer {
      EditAwaitableViewer(Viewer delegate) {
        super(delegate);
      }

      @Override
      public Optional<Editor> edit() throws IOException {
        return super.edit().map(editAwaiter::register);
      }

      @Override
      public CompletableFuture<Optional<Editor>> edit(Executor executor) {
        return super.edit(executor).thenApply(editor -> editor.map(editAwaiter::register));
      }
    }
  }

  /** An executor that allows blocking until a delegate executor has no scheduled tasks. */
  static final class AwaitableExecutor implements Executor {
    private final Executor delegate;
    private final Phaser phaser = new Phaser(1); // Register self.

    AwaitableExecutor(Executor delegate) {
      this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
      var deregister = new AtomicBoolean();
      phaser.register();
      try {
        delegate.execute(
            () -> {
              try {
                command.run();
              } finally {
                if (deregister.compareAndSet(false, true)) {
                  phaser.arriveAndDeregister();
                }
              }
            });
      } catch (RuntimeException | Error e) {
        if (deregister.compareAndSet(false, true)) {
          phaser.arriveAndDeregister();
        }
      }
    }

    void await() {
      try {
        phaser.awaitAdvanceInterruptibly(phaser.arrive(), 5, TimeUnit.SECONDS);
      } catch (InterruptedException | TimeoutException e) {
        fail("Timed out / interrupted while waiting for tasks to finish", e);
      }
    }
  }
}
