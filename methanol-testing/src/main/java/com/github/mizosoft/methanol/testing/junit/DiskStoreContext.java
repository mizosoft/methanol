/*
 * Copyright (c) 2023 Moataz Abdelnasser
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

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.cache.DiskStore;
import com.github.mizosoft.methanol.internal.cache.Store;
import com.github.mizosoft.methanol.testing.MemoryFileSystemProvider;
import com.github.mizosoft.methanol.testing.MockClock;
import com.github.mizosoft.methanol.testing.MockDelayer;
import com.github.mizosoft.methanol.testing.MockExecutor;
import com.github.mizosoft.methanol.testing.file.LeakDetectingFileSystem;
import com.github.mizosoft.methanol.testing.file.WindowsEmulatingFileSystem;
import com.github.mizosoft.methanol.testing.junit.StoreConfig.FileSystemType;
import java.io.IOException;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class DiskStoreContext extends StoreContext {
  private static final String TEMP_DIRECTORY_PREFIX = DiskStoreContext.class.getName();
  private static final String SYSTEM_ROOT_TEMP_DIRECTORY =
      requireNonNull(System.getProperty("java.io.tmpdir"));

  private final Path directory;
  private final FileSystem fileSystem;
  private final Executor executor;
  private final MockClock clock;
  private final MockHasher hasher;
  private final MockDelayer delayer;

  private DiskStoreContext(
      DiskStoreConfig config,
      Path directory,
      FileSystem fileSystem,
      Executor executor,
      MockHasher hasher,
      MockClock clock) {
    super(config);
    this.directory = requireNonNull(directory);
    this.fileSystem = requireNonNull(fileSystem);
    this.executor = requireNonNull(executor);
    this.hasher = requireNonNull(hasher);
    this.clock = requireNonNull(clock);
    this.delayer = new MockDelayer(clock, config.dispatchEagerly());
  }

  @Override
  public DiskStoreConfig config() {
    return (DiskStoreConfig) super.config();
  }

  public Path directory() {
    return directory;
  }

  public MockHasher hasher() {
    return hasher;
  }

  public MockClock mockClock() {
    return clock;
  }

  public MockDelayer mockDelayer() {
    return delayer;
  }

  public MockExecutor mockExecutor() {
    if (!(executor instanceof MockExecutor)) {
      throw new UnsupportedOperationException("unavailable MockExecutor");
    }
    return ((MockExecutor) executor);
  }

  @Override
  public void drainQueuedTasksIfNeeded() {
    if (delayer != null) {
      delayer.drainQueuedTasks(false);
    }
    if (executor instanceof MockExecutor) {
      ((MockExecutor) executor).runAll();
    }
  }

  @Override
  Store createStore() throws IOException {
    var builder =
        DiskStore.newBuilder()
            .debugIndexOps(true)
            .maxSize(config().maxSize())
            .directory(directory)
            .executor(executor)
            .hasher(hasher)
            .clock(clock)
            .delayer(delayer)
            .appVersion(config().appVersion());
    config()
        .indexUpdateDelaySeconds()
        .ifPresent(seconds -> builder.indexUpdateDelay(Duration.ofSeconds(seconds)));
    return builder.build();
  }

  @Override
  void close(List<Exception> exceptions) {
    // Make sure no more tasks are queued. We ignore rejected tasks as the test might have caused an
    // executor to be shutdown.
    delayer.drainQueuedTasks(true);
    if (executor instanceof MockExecutor) {
      try {
        var mockExecutor = mockExecutor();
        mockExecutor.executeDirectly(true); // Allow recursive task submission.
        mockExecutor.runAll();
      } catch (Exception e) {
        exceptions.add(e);
      }
    }

    super.close(exceptions);

    // Make sure all tasks finished execution if we have an ExecutorService.
    if (executor instanceof ExecutorService) {
      var service = (ExecutorService) executor;
      service.shutdown();
      try {
        if (!service.awaitTermination(20, TimeUnit.SECONDS)) {
          throw new TimeoutException("timed out while waiting for pool's termination: " + service);
        }
      } catch (Exception e) {
        exceptions.add(e);
      }
    }

    try {
      Directories.deleteRecursively(directory);
    } catch (NoSuchFileException | ClosedFileSystemException ignored) {
      // OK
    } catch (Exception e) {
      exceptions.add(e);
    }

    // Close the FileSystem. This will detect resource leaks if the FileSystem is wrapped in a
    // LeakDetectingFileSystem.
    try {
      fileSystem.close();
    } catch (Exception e) {
      exceptions.add(e);
    }
  }

  public static DiskStoreContext create(DiskStoreConfig spec) throws IOException {
    var directory = createTempDir(spec.fileSystemType());
    var clock = new MockClock();
    if (spec.autoAdvanceClock()) {
      clock.autoAdvance(Duration.ofSeconds(1));
    }
    return new DiskStoreContext(
        spec,
        directory,
        directory.getFileSystem(),
        spec.execution().newExecutor(),
        new MockHasher(),
        clock);
  }

  private static Path createTempDir(FileSystemType fsType) throws IOException {
    return Files.createTempDirectory(
        getRootTempDirectory(createFileSystem(fsType), fsType), TEMP_DIRECTORY_PREFIX);
  }

  private static FileSystem createFileSystem(FileSystemType type) {
    switch (type) {
      case IN_MEMORY:
        return LeakDetectingFileSystem.wrap(
            MemoryFileSystemProvider.installed().newMemoryFileSystem());
      case SYSTEM:
        return LeakDetectingFileSystem.wrap(FileSystems.getDefault());
      case EMULATED_WINDOWS:
        return LeakDetectingFileSystem.wrap(
            WindowsEmulatingFileSystem.wrap(
                MemoryFileSystemProvider.installed().newMemoryFileSystem()));
      default:
        throw new AssertionError();
    }
  }

  private static Path getRootTempDirectory(FileSystem fs, FileSystemType fsType)
      throws IOException {
    switch (fsType) {
      case IN_MEMORY:
      case EMULATED_WINDOWS:
        return Files.createDirectories(fs.getRootDirectories().iterator().next().resolve("temp"));
      case SYSTEM:
        return fs.getPath(SYSTEM_ROOT_TEMP_DIRECTORY);
      default:
        throw new AssertionError();
    }
  }
}
