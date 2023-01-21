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

package com.github.mizosoft.methanol;

import static com.github.mizosoft.methanol.MutableRequest.GET;
import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.github.mizosoft.methanol.HttpCacheTest.ForwardingEditor;
import com.github.mizosoft.methanol.HttpCacheTest.ForwardingStore;
import com.github.mizosoft.methanol.HttpCacheTest.ForwardingViewer;
import com.github.mizosoft.methanol.Methanol.Interceptor;
import com.github.mizosoft.methanol.internal.cache.CacheWritingPublisher;
import com.github.mizosoft.methanol.internal.cache.Store;
import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.testing.MockClock;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension.ExecutorConfig;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.junit.MockWebServerExtension;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({MockWebServerExtension.class, ExecutorExtension.class})
abstract class AbstractHttpCacheTest {
  Executor executor;
  Methanol.Builder clientBuilder;
  MockWebServer server;
  URI serverUri;
  MockClock clock;
  EditAwaiter editAwaiter;
  boolean failOnUnavailableResponses = true;
  Duration advanceOnSend = Duration.ZERO;

  Methanol client; // Must be set by subclass.

  @BeforeEach
  @ExecutorConfig(ExecutorType.CACHED_POOL)
  void setUp(Executor executor, Methanol.Builder builder, MockWebServer server) {
    this.executor = executor;
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
                              "server has no queued responses, expected something to be cached?")
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
                                        "server has no queued responses, expected something to be cached?")
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
    var response = client.send(request, BodyHandlers.ofString());
    editAwaiter.await();
    return response;
  }

  HttpResponse<String> send(Methanol client) throws IOException, InterruptedException {
    return send(client, serverUri);
  }

  HttpResponse<String> send(Methanol client, URI uri) throws IOException, InterruptedException {
    return send(client, MutableRequest.GET(uri));
  }

  HttpResponse<String> send(Methanol client, HttpRequest request)
      throws IOException, InterruptedException {
    var response = client.send(request, BodyHandlers.ofString());
    editAwaiter.await();
    return response;
  }

  static LocalDateTime toUtcDateTime(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  /**
   * Awaits ongoing edits to be completed. By design, {@link CacheWritingPublisher} doesn't make
   * downstream completion wait for the entire body to be written to cache. So if writes take time,
   * the response entry is committed a while after the response is completed. This however agitates
   * tests as they expect things to happen sequentially. This is solved by waiting for all open
   * editors to close after a client.send(...) is issued.
   */
  static final class EditAwaiter {
    private final Phaser phaser = new Phaser(1); // Register self.

    EditAwaiter() {}

    Editor register(Editor editor) {
      return new NotifyingEditor(editor, phaser);
    }

    void await() {
      try {
        phaser.awaitAdvanceInterruptibly(phaser.arrive(), 20, TimeUnit.SECONDS);
      } catch (InterruptedException | TimeoutException e) {
        fail("timed out while waiting for editors to be closed", e);
      }
    }

    /** An Editor that notifies (arrives at) a Phaser when closed or committed. */
    private static final class NotifyingEditor extends ForwardingEditor {
      private final Phaser phaser;
      private final AtomicBoolean closed = new AtomicBoolean();

      NotifyingEditor(Editor delegate, Phaser phaser) {
        super(delegate);
        this.phaser = phaser;
        requireState(phaser.register() >= 0, "phaser terminated");
      }

      @Override
      public CompletableFuture<Boolean> commitAsync(ByteBuffer metadata) {
        // To make sure all changes are applied before waiters are notified, actually do the arrival
        // when committing completes.
        boolean arrive = this.closed.compareAndSet(false, true);
        return super.commitAsync(metadata)
            .whenComplete(
                (__, ___) -> {
                  if (arrive) {
                    phaser.arriveAndDeregister();
                  }
                });
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

  static final class EditAwaiterStore extends ForwardingStore {
    private final EditAwaiter editAwaiter;

    EditAwaiterStore(Store delegate, EditAwaiter editAwaiter) {
      super(delegate);
      this.editAwaiter = editAwaiter;
    }

    @Override
    public Optional<Viewer> view(String key) throws IOException, InterruptedException {
      return super.view(key).map(EditAwaiterStore.EditAwaiterViewer::new);
    }

    @Override
    public CompletableFuture<Optional<Viewer>> viewAsync(String key) {
      return super.viewAsync(key).thenApply(viewer -> viewer.map(EditAwaiterViewer::new));
    }

    @Override
    public Optional<Editor> edit(String key) throws IOException, InterruptedException {
      return super.edit(key).map(editAwaiter::register);
    }

    @Override
    public CompletableFuture<Optional<Editor>> editAsync(String key) {
      return super.editAsync(key).thenApply(editor -> editor.map(editAwaiter::register));
    }

    @Override
    public String toString() {
      return delegate.toString();
    }

    private final class EditAwaiterViewer extends ForwardingViewer {
      EditAwaiterViewer(Viewer delegate) {
        super(delegate);
      }

      @Override
      public CompletableFuture<Optional<Editor>> editAsync() {
        return super.editAsync().thenApply(editor -> editor.map(editAwaiter::register));
      }
    }
  }
}
