package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.internal.Validate.TODO;

import com.github.mizosoft.methanol.internal.Validate;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;

public final class CacheReadingPublisher implements Publisher<List<ByteBuffer>> {
  private final Viewer viewer;

  public CacheReadingPublisher(Viewer viewer) {
    this.viewer = viewer;
  }

  @Override
  public void subscribe(Subscriber<? super List<ByteBuffer>> subscriber) {
    TODO();
  }
}
