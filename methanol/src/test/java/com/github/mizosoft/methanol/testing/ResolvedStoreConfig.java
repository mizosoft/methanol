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

import static com.github.mizosoft.methanol.testing.StoreConfig.FileSystemType.JIMFS;
import static com.github.mizosoft.methanol.testing.StoreConfig.FileSystemType.SYSTEM;
import static com.github.mizosoft.methanol.testing.StoreConfig.StoreType.DISK;
import static com.github.mizosoft.methanol.testing.StoreConfig.StoreType.MEMORY;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.mizosoft.methanol.testing.StoreConfig.Execution;
import com.github.mizosoft.methanol.testing.StoreConfig.FileSystemType;
import com.github.mizosoft.methanol.testing.StoreConfig.StoreType;
import com.github.mizosoft.methanol.testutils.MockClock;
import com.github.mizosoft.methanol.testutils.io.file.LeakDetectingFileSystem;
import com.github.mizosoft.methanol.testutils.io.file.WindowsEmulatingFileSystem;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/** A configuration resolved from a {@link StoreConfig}. */
public final class ResolvedStoreConfig {
  private static final String TEMP_DIR_PREFIX = "methanol-store-extension-junit-";
  private static final String SYSTEM_TEMP_DIR =
      requireNonNull(System.getProperty("java.io.tmpdir"));

  private final long maxSize;
  private final StoreType storeType;

  // DiskStore-only config

  private final FileSystemType fileSystemType;
  private final Execution execution;
  private final int appVersion;
  private final @Nullable Duration indexUpdateDelay;
  private final boolean autoInit;
  private final boolean autoAdvanceClock;

  ResolvedStoreConfig(
      long maxSize,
      StoreType storeType,
      FileSystemType fileSystemType,
      Execution execution,
      int appVersion,
      @Nullable Duration indexUpdateDelay,
      boolean autoInit,
      boolean autoAdvanceClock) {
    this.maxSize = maxSize;
    this.storeType = storeType;
    this.fileSystemType = fileSystemType;
    this.execution = execution;
    this.appVersion = appVersion;
    this.indexUpdateDelay = indexUpdateDelay;
    this.autoInit = autoInit;
    this.autoAdvanceClock = autoAdvanceClock;
  }

  public long maxSize() {
    return maxSize;
  }

  public StoreType storeType() {
    return storeType;
  }

  public FileSystemType fileSystemType() {
    return fileSystemType;
  }

  public Execution execution() {
    return execution;
  }

  public int appVersion() {
    return appVersion;
  }

  public @Nullable Duration indexUpdateDelay() {
    return indexUpdateDelay;
  }

  public boolean autoInit() {
    return autoInit;
  }

  public boolean autoAdvanceClock() {
    return autoAdvanceClock;
  }

  boolean isCompatible() {
    // Memory store doesn't use a FileSystem, so ensure it's only generated
    // once by only pairing it with FileSystemType.JIMFS (can't use empty
    // FileSystemType array as the cartesian product itself will be empty).
    return storeType != MEMORY || fileSystemType == JIMFS;
  }

  public StoreContext createContext() throws IOException {
    return storeType == MEMORY
        ? new StoreContext(this, null, null, null, null, null)
        : createDiskStoreContext();
  }

  private StoreContext createDiskStoreContext() throws IOException {
    var tempDir = createTempDir(fileSystemType);
    var clock = new MockClock();
    if (autoAdvanceClock) {
      clock.autoAdvance(Duration.ofSeconds(1));
    }
    return new StoreContext(
        this, tempDir, tempDir.getFileSystem(), execution.newExecutor(), new MockHasher(), clock);
  }

  private Path createTempDir(FileSystemType fileSystemType) throws IOException {
    FileSystem fileSystem;
    Path rootTempDir;
    switch (fileSystemType) {
      case JIMFS:
        fileSystem = LeakDetectingFileSystem.wrap(Jimfs.newFileSystem(Configuration.unix()));
        rootTempDir =
            Files.createDirectories(
                fileSystem.getRootDirectories().iterator().next().resolve("temp"));
        break;

      case SYSTEM:
        fileSystem = LeakDetectingFileSystem.wrap(FileSystems.getDefault());
        rootTempDir = fileSystem.getPath(SYSTEM_TEMP_DIR);
        break;

      case WINDOWS:
        fileSystem =
            LeakDetectingFileSystem.wrap(
                WindowsEmulatingFileSystem.wrap(Jimfs.newFileSystem(Configuration.windows())));
        rootTempDir =
            Files.createDirectories(
                fileSystem.getRootDirectories().iterator().next().resolve("temp"));
        break;

      default:
        return fail("unknown FileSystemType: " + fileSystemType);
    }

    return Files.createTempDirectory(rootTempDir, TEMP_DIR_PREFIX);
  }

  static ResolvedStoreConfig create(List<?> tuple) {
    int i = 0;
    long maxSize = (long) tuple.get(i++);
    var storeType = (StoreType) tuple.get(i++);
    var fileSystemType = (FileSystemType) tuple.get(i++);
    var execution = (Execution) tuple.get(i++);
    var appVersion = (int) tuple.get(i++);
    long indexUpdateDelaySeconds = (long) tuple.get(i++);
    var indexUpdateDelay =
        indexUpdateDelaySeconds != StoreConfig.DEFAULT_FLUSH_DELAY
            ? Duration.ofSeconds(indexUpdateDelaySeconds)
            : null;
    boolean autoInit = (boolean) tuple.get(i++);
    boolean autoAdvanceClock = (boolean) tuple.get(i);
    return new ResolvedStoreConfig(
        maxSize,
        storeType,
        fileSystemType,
        execution,
        appVersion,
        indexUpdateDelay,
        autoInit,
        autoAdvanceClock);
  }

  public static ResolvedStoreConfig createDefault(StoreType storeType) {
    var fileSystemType = storeType == DISK ? SYSTEM : JIMFS;
    return new ResolvedStoreConfig(
        Long.MAX_VALUE, storeType, fileSystemType, Execution.ASYNC, 1, null, true, true);
  }
}
