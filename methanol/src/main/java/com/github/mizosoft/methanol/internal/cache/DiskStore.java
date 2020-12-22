package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.internal.Validate.TODO;
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.checkerframework.checker.nullness.qual.Nullable;

/** STUB! */
public final class DiskStore implements Store {
  public DiskStore(Path directory, long maxSize, Executor executor) {
    requireNonNull(directory);
    requireArgument(maxSize > 0, "non-positive maxSize: %s", maxSize);
    requireNonNull(executor);
    TODO();
  }

  @Override
  public long maxSize() {
    return TODO();
  }

  @Override
  public Optional<Executor> executor() {
    return TODO();
  }

  @Override
  public @Nullable Viewer view(String key) {
    return TODO();
  }

  @Override
  public @Nullable Editor edit(String key) {
    return TODO();
  }

  @Override
  public CompletableFuture<@Nullable Viewer> viewAsync(String key) {
    return TODO();
  }

  @Override
  public Iterator<Viewer> viewAll() {
    return TODO();
  }

  @Override
  public boolean remove(String key) {
    return TODO();
  }

  @Override
  public void clear() {
    TODO();
  }

  @Override
  public long size() {
    return TODO();
  }

  @Override
  public void close() {
    TODO();
  }
}
