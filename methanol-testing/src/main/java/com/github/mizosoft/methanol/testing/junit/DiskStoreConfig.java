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

import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static java.util.Objects.requireNonNull;

import java.util.OptionalInt;

public final class DiskStoreConfig extends StoreConfig {
  private final FileSystemType fileSystemType;
  private final Execution execution;

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  private final OptionalInt indexUpdateDelaySeconds;

  private final boolean autoAdvanceClock;
  private final boolean dispatchEagerly;

  public DiskStoreConfig(
      long maxSize,
      int appVersion,
      FileSystemType fileSystemType,
      Execution execution,
      int indexUpdateDelaySeconds,
      boolean autoAdvanceClock,
      boolean dispatchEagerly) {
    super(StoreType.DISK, maxSize, appVersion);
    this.fileSystemType = requireNonNull(fileSystemType);
    this.execution = requireNonNull(execution);
    this.indexUpdateDelaySeconds =
        indexUpdateDelaySeconds != UNSET_NUMBER
            ? OptionalInt.of(indexUpdateDelaySeconds)
            : OptionalInt.empty();
    this.indexUpdateDelaySeconds.ifPresent(
        value -> requireArgument(value >= 0, "expected a non-negative value, got: %d", value));
    this.autoAdvanceClock = autoAdvanceClock;
    this.dispatchEagerly = dispatchEagerly;
  }

  public FileSystemType fileSystemType() {
    return fileSystemType;
  }

  public Execution execution() {
    return execution;
  }

  public OptionalInt indexUpdateDelaySeconds() {
    return indexUpdateDelaySeconds;
  }

  public boolean autoAdvanceClock() {
    return autoAdvanceClock;
  }

  public boolean dispatchEagerly() {
    return dispatchEagerly;
  }
}
