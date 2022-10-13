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

package com.github.mizosoft.methanol.testing.junit;

import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static com.github.mizosoft.methanol.testing.junit.StoreConfig.Execution.QUEUED;
import static java.nio.file.FileVisitResult.CONTINUE;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.mizosoft.methanol.internal.cache.DiskStore;
import com.github.mizosoft.methanol.internal.cache.MemoryStore;
import com.github.mizosoft.methanol.internal.cache.Store;
import com.github.mizosoft.methanol.testing.MockClock;
import com.github.mizosoft.methanol.testing.MockDelayer;
import com.github.mizosoft.methanol.testing.MockExecutor;
import java.io.IOException;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.checkerframework.checker.nullness.qual.Nullable;

/** A store's configuration for a test case. */
public final class StoreContext implements AutoCloseable {
  private final ResolvedStoreConfig config;
  private final @Nullable Path directory;
  private final @Nullable FileSystem fileSystem;
  private final @Nullable Executor executor;
  private final @Nullable MockHasher hasher;
  private final @Nullable MockClock clock;
  private final @Nullable MockDelayer delayer;

  private final List<Store> createdStores = new ArrayList<>();

  StoreContext(
      ResolvedStoreConfig config,
      @Nullable Path directory,
      @Nullable FileSystem fileSystem,
      @Nullable Executor executor,
      @Nullable MockHasher hasher,
      @Nullable MockClock clock) {
    this.config = config;
    this.directory = directory;
    this.fileSystem = fileSystem;
    this.executor = executor;
    this.hasher = hasher;
    this.clock = clock;
    this.delayer = clock != null ? new MockDelayer(clock) : null;
  }

  public long maxSize() {
    return config.maxSize();
  }

  public MockExecutor mockExecutor() {
    if (!(executor instanceof MockExecutor)) {
      throw new UnsupportedOperationException("unavailable MockExecutor");
    }
    return ((MockExecutor) executor);
  }

  public MockHasher hasher() {
    if (hasher == null) {
      throw new UnsupportedOperationException("unavailable hasher");
    }
    return hasher;
  }

  public MockClock clock() {
    if (clock == null) {
      throw new UnsupportedOperationException("unavailable clock");
    }
    return clock;
  }

  public MockDelayer delayer() {
    if (delayer == null) {
      throw new UnsupportedOperationException("unavailable delayer");
    }
    return delayer;
  }

  public Path directory() {
    if (directory == null) {
      throw new UnsupportedOperationException("unavailable directory");
    }
    return directory;
  }

  public ResolvedStoreConfig config() {
    return config;
  }

  /** If execution is mocked, makes sure all tasks queued so far are executed. */
  public void drainQueuedTasks() {
    if (delayer != null) {
      delayer.drainQueuedTasks(false);
    }
    if (executor instanceof MockExecutor) {
      ((MockExecutor) executor).runAll();
    }
  }

  public Store newStore() throws IOException {
    var store = createStore();
    createdStores.add(store);
    initializeAll();
    return store;
  }

  private Store createStore() {
    switch (config.storeType()) {
      case MEMORY:
        return new MemoryStore(config.maxSize());
      case DISK:
        var builder =
            DiskStore.newBuilder()
                .debugIndexOperations(true)
                .maxSize(config.maxSize())
                .directory(directory)
                .executor(executor)
                .hasher(hasher)
                .clock(clock)
                .delayer(delayer)
                .appVersion(config.appVersion());
        if (config.indexUpdateDelay() != null) {
          builder.indexUpdateDelay(config.indexUpdateDelay());
        }
        return builder.build();
      default:
        return fail("unexpected StoreType: " + config.storeType());
    }
  }

  public void initializeAll() throws IOException {
    executeOnSameThreadIfExecutionIsQueued(true);
    for (var store : createdStores) {
      if (config().autoInit()) {
        store.initialize();
      }
    }
    executeOnSameThreadIfExecutionIsQueued(false);
  }

  @Override
  public void close() throws Exception {
    var exceptions = new ArrayList<Exception>();

    // Make sure no more tasks are queued.
    if (delayer != null) {
      // Ignore rejected tasks as the test might have caused an executor to be shutdown.
      delayer.drainQueuedTasks(true);
    }
    if (executor instanceof MockExecutor) {
      try {
        var mockExecutor = (MockExecutor) executor;
        mockExecutor.executeOnSameThread(true);
        mockExecutor.runAll();
      } catch (Exception e) {
        exceptions.add(e);
      }
    }

    for (var store : createdStores) {
      try {
        store.close();
      } catch (Exception e) {
        exceptions.add(e);
      }
    }

    // Await ExecutorService termination if we have one.
    if (executor instanceof ExecutorService) {
      var service = (ExecutorService) executor;
      service.shutdown();
      try {
        if (!service.awaitTermination(2, TimeUnit.MINUTES)) {
          throw new TimeoutException("timed out while waiting for pool's termination: " + service);
        }
      } catch (Exception e) {
        exceptions.add(e);
      }
    }

    // Delete the temp directory if we have one.
    if (directory != null) {
      try {
        Files.walkFileTree(
            directory,
            new SimpleFileVisitor<>() {
              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                  throws IOException {
                Files.deleteIfExists(file);
                return CONTINUE;
              }

              @Override
              public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                  throws IOException {
                if (exc != null) {
                  throw exc;
                }
                Files.deleteIfExists(dir);
                return CONTINUE;
              }
            });
      } catch (NoSuchFileException | ClosedFileSystemException ignored) {
        // OK
      } catch (Exception e) {
        exceptions.add(e);
      }
    }

    // Close the FileSystem if we have one.
    if (fileSystem != null) {
      try {
        fileSystem.close();
      } catch (Exception e) {
        exceptions.add(e);
      }
    }

    if (exceptions.size() == 1) {
      throw exceptions.get(0);
    } else if (exceptions.size() > 1) {
      var compositeException =
          new IOException("encountered one or more exceptions while closing stores");
      exceptions.forEach(compositeException::addSuppressed);
      throw compositeException;
    }
  }

  /**
   * If execution is queued, configures whether to run tasks on the same thread instead of queueing
   * them.
   */
  private void executeOnSameThreadIfExecutionIsQueued(boolean policy) {
    if (config.execution() == QUEUED) {
      castNonNull((MockExecutor) executor).executeOnSameThread(policy);
    }
  }
}
