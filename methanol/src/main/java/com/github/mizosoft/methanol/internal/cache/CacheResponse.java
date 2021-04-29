package com.github.mizosoft.methanol.internal.cache;

import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.internal.extensions.ResponseBuilder;
import com.github.mizosoft.methanol.internal.extensions.TrackedResponse;
import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.Nullable;

/** A {@code RawResponse} retrieved from cache. */
public final class CacheResponse extends PublisherResponse implements Closeable {
  private final Viewer viewer;

  public CacheResponse(CacheResponseMetadata metadata, Viewer viewer, Executor executor) {
    this(metadata.toResponseBuilder().build(), new CacheReadingPublisher(viewer, executor), viewer);
  }

  private CacheResponse(TrackedResponse<?> response, Publisher<List<ByteBuffer>> body, Viewer viewer) {
    super(response, body);
    this.viewer = viewer;
  }

  @Override
  public CacheResponse with(Consumer<ResponseBuilder<?>> mutator) {
    var builder = ResponseBuilder.newBuilder(response);
    mutator.accept(builder);
    return new CacheResponse(builder.build(), publisher, viewer);
  }

  @Override
  public void close() {
    viewer.close(); // TODO close quietly
  }

  public @Nullable Editor edit() {
    return viewer.edit();
  }
}
