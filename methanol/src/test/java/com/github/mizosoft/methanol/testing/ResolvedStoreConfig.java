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

import com.github.mizosoft.methanol.testing.StoreConfig.Execution;
import com.github.mizosoft.methanol.testing.StoreConfig.FileSystemType;
import com.github.mizosoft.methanol.testing.StoreConfig.StoreType;
import com.github.mizosoft.methanol.testutils.MockClock;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/** A configuration resolved from a {@link StoreConfig}. */
public final class ResolvedStoreConfig {
  private static final String TEMP_DIR_PREFIX = "methanol-store-extension-junit-";

  private final long maxSize;
  private final StoreType storeType;

  // DiskStore-only fields

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
    // once by only pairing it with FileSystemType.MEMORY (can't use empty
    // FileSystemType array as the cartesian product itself will be empty).
    return storeType != StoreType.MEMORY || fileSystemType == JIMFS;
  }

  public StoreContext createContext() throws IOException {
    return storeType == StoreType.MEMORY
        ? new StoreContext(this, null, null, null, null, null)
        : createDiskStoreContext();
  }

  private StoreContext createDiskStoreContext() throws IOException {
    FileSystem fileSystem;
    Path tempDirectory;
    if (fileSystemType == SYSTEM) {
      // Do not record the default filesystem to not attempt to close
      // it (which will throw UnsupportedOperationException anyways).
      fileSystem = null;
      tempDirectory = Files.createTempDirectory(TEMP_DIR_PREFIX);
    } else {
      fileSystem = Jimfs.newFileSystem(Configuration.unix());
      var root = fileSystem.getRootDirectories().iterator().next();
      var tempDirectories = Files.createDirectories(root.resolve("temp"));
      tempDirectory = Files.createTempDirectory(tempDirectories, TEMP_DIR_PREFIX);
    }
    var clock = new MockClock();
    if (autoAdvanceClock) {
      clock.autoAdvance(Duration.ofSeconds(1));
    }
    return new StoreContext(
        this, tempDirectory, fileSystem, execution.newExecutor(), new MockHasher(), clock);
  }

  static ResolvedStoreConfig create(List<Object> tuple) {
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
