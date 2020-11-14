package com.github.mizosoft.methanol.internal.cache;

import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import org.checkerframework.checker.nullness.qual.Nullable;

/** A {@code RawResponse} retrieved from cache. */
public final class CacheResponse extends PublisherResponse {
  private final Viewer viewer;

  public CacheResponse(CacheResponseMetadata metadata, Viewer viewer) {
    super(metadata.toResponseBuilder().build(), new CacheReadingPublisher(viewer));
    this.viewer = viewer;
  }

  public void dispose() {
    viewer.close(); // TODO close quietly
  }

  public @Nullable Editor edit() {
    return viewer.edit();
  }
}
