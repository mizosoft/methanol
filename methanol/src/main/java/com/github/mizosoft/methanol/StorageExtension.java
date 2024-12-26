/*
 * Copyright (c) 2024 Moataz Hussein
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

package com.github.mizosoft.methanol;

import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.cache.DiskStore;
import com.github.mizosoft.methanol.internal.cache.InternalStorageExtension;
import com.github.mizosoft.methanol.internal.cache.MemoryStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/** An extension that provides a storage backend for an {@link HttpCache}. */
public interface StorageExtension {

  /** Returns a {@code StorageExtension} for saving data in memory, not exceeding the given size. */
  static StorageExtension inMemory(long maxSize) {
    requireArgument(maxSize > 0, "Non-positive maxSize: %d", maxSize);
    return (InternalStorageExtension) (executor, appVersion) -> new MemoryStore(maxSize);
  }

  /**
   * Returns a {@code StorageExtension} for saving data on disk under the given directory, not
   * exceeding the given size.
   *
   * @throws IllegalArgumentException if {@code maxSize} is not positive
   */
  static StorageExtension onDisk(Path directory, long maxSize) {
    requireNonNull(directory);
    requireArgument(maxSize > 0, "Non-positive maxSize: %d", maxSize);
    return (InternalStorageExtension)
        (executor, appVersion) -> {
          try {
            return DiskStore.newBuilder()
                .directory(directory)
                .maxSize(maxSize)
                .executor(executor)
                .appVersion(appVersion)
                .build();
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        };
  }
}
