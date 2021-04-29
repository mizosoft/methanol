/*
 * Copyright (c) 2019-2021 Moataz Abdelnasser
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

import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.mizosoft.methanol.internal.cache.DiskStore;
import com.github.mizosoft.methanol.internal.cache.MemoryStore;
import com.github.mizosoft.methanol.internal.cache.Store;
import com.github.mizosoft.methanol.testing.StoreConfig.Execution;
import com.github.mizosoft.methanol.testutils.MockClock;
import com.github.mizosoft.methanol.testutils.MockExecutor;
import java.io.IOException;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Context for the store's configuration. */
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
      throw new UnsupportedOperationException("unavailable mockHasher");
    }
    return hasher;
  }

  public MockClock clock() {
    if (clock == null) {
      throw new UnsupportedOperationException("unavailable MockClock");
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
      delayer.dispatchExpiredTasks(Instant.MAX, false);
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
                .maxSize(config.maxSize())
                .directory(directory)
                .executor(executor)
                .hasher(hasher)
                .clock(clock)
                .delayer(delayer)
                .appVersion(config.appVersion());
        return config.indexUpdateDelay() != null
            ? builder.indexUpdateDelay(config.indexUpdateDelay()).build()
            : builder.build();
      default:
        return fail("unexpected StoreType: " + config.storeType());
    }
  }

  void initializeAll() throws IOException {
    executeOnSameThreadIfQueuedExecution();
    for (var store : createdStores) {
      if (config().autoInit()) {
        store.initialize();
      }
    }
    resetExecuteOnSameThreadIfQueued();
  }

  @Override
  public void close() throws Exception {
    Exception caughtException = null;

    // First make sure no more tasks are queued
    if (delayer != null) {
      // Ignore rejected tasks as the test might have caused an executor to shutdown
      delayer.dispatchExpiredTasks(Instant.MAX, true);
    }
    if (executor instanceof MockExecutor) {
      try {
        var mockExecutor = (MockExecutor) executor;
        mockExecutor.executeOnSameThread(true);
        mockExecutor.runAll();
      } catch (Exception e) {
        caughtException = e;
      }
    }

    // Then close created stores
    for (var store : createdStores) {
      try {
        store.close();
      } catch (IOException ioe) {
        if (caughtException != null) {
          caughtException.addSuppressed(ioe);
        } else {
          caughtException = ioe;
        }
      }
    }

    // Then await ExecutorService termination if we have one
    if (executor instanceof ExecutorService) {
      var service = (ExecutorService) executor;
      service.shutdown();
      try {
        if (!service.awaitTermination(20, TimeUnit.SECONDS)) {
          throw new TimeoutException("timed-out while waiting for pool's termination: " + service);
        }
      } catch (InterruptedException | TimeoutException e) {
        if (caughtException != null) {
          caughtException.addSuppressed(e);
        } else {
          caughtException = e;
        }
      }
    }

    // Then delete the temp directory if we have one
    if (directory != null) {
      try {
        Files.walkFileTree(
            directory,
            new SimpleFileVisitor<>() {
              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                  throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                  throws IOException {
                if (exc != null) {
                  throw exc;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
              }
            });
      } catch (NoSuchFileException | ClosedFileSystemException ignored) {
        // OK
      } catch (IOException ioe) {
        if (caughtException != null) {
          caughtException.addSuppressed(ioe);
        } else {
          caughtException = ioe;
        }
      }
    }

    // Finally close the FileSystem if we have one
    if (fileSystem != null) {
      try {
        fileSystem.close();
      } catch (IOException ioe) {
        if (caughtException != null) {
          caughtException.addSuppressed(ioe);
        } else {
          throw ioe;
        }
      }
    }

    if (caughtException != null) {
      throw caughtException;
    }
  }

  /**
   * If the execution is queued, configures it to run tasks on the same thread instead of queueing
   * them.
   */
  private void executeOnSameThreadIfQueuedExecution() {
    if (config.execution() == Execution.QUEUED) {
      castNonNull((MockExecutor) executor).executeOnSameThread(true);
    }
  }

  private void resetExecuteOnSameThreadIfQueued() {
    if (config.execution() == Execution.QUEUED) {
      castNonNull((MockExecutor) executor).executeOnSameThread(false);
    }
  }
}
