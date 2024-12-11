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

package com.github.mizosoft.methanol.internal.cache;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.ResponseBuilder;
import com.github.mizosoft.methanol.TrackedResponse;
import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.function.Consumer;

/** A {@code RawResponse} retrieved from cache. */
public final class CacheResponse extends PublisherResponse implements Closeable {
  private final Viewer viewer;
  private final Executor executor;

  public CacheResponse(
      TrackedResponse<?> response,
      Viewer viewer,
      Executor executor,
      CacheReadingPublisher.Listener readListener) {
    this(response, new CacheReadingPublisher(viewer, executor, readListener), viewer, executor);
  }

  private CacheResponse(
      TrackedResponse<?> response,
      Publisher<List<ByteBuffer>> body,
      Viewer viewer,
      Executor executor) {
    super(response, body);
    this.viewer = requireNonNull(viewer);
    this.executor = requireNonNull(executor);
  }

  @Override
  public CacheResponse with(Consumer<ResponseBuilder<?>> mutator) {
    var builder = ResponseBuilder.newBuilder(response);
    mutator.accept(builder);
    return new CacheResponse(builder.buildTrackedResponse(), publisher, viewer, executor);
  }

  @Override
  public void close() {
    viewer.close();
  }

  public CompletableFuture<Optional<Editor>> edit() {
    return viewer.edit(executor);
  }
}
