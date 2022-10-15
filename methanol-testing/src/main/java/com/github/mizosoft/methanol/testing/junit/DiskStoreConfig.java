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

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.testing.junit.StoreSpec.Execution;
import com.github.mizosoft.methanol.testing.junit.StoreSpec.FileSystemType;
import com.github.mizosoft.methanol.testing.junit.StoreSpec.StoreType;
import java.time.Duration;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class DiskStoreConfig extends StoreConfig {
  private final FileSystemType fileSystemType;
  private final Execution execution;

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  private final Optional<Duration> indexUpdateDelay;

  private final boolean autoAdvanceClock;
  private final int appVersion;

  public DiskStoreConfig(
      boolean autoInit,
      long maxSize,
      FileSystemType fileSystemType,
      Execution execution,
      @Nullable Duration indexUpdateDelay,
      boolean autoAdvanceClock,
      int appVersion) {
    super(StoreType.DISK, autoInit, maxSize);
    this.fileSystemType = requireNonNull(fileSystemType);
    this.execution = requireNonNull(execution);
    this.indexUpdateDelay = Optional.ofNullable(indexUpdateDelay);
    this.autoAdvanceClock = autoAdvanceClock;
    this.appVersion = appVersion;
  }

  public FileSystemType fileSystemType() {
    return fileSystemType;
  }

  public Execution execution() {
    return execution;
  }

  public Optional<Duration> indexUpdateDelay() {
    return indexUpdateDelay;
  }

  public boolean autoAdvanceClock() {
    return autoAdvanceClock;
  }

  public int appVersion() {
    return appVersion;
  }
}
