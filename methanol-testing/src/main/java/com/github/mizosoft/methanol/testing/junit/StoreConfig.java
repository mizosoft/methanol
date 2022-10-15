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

public abstract class StoreConfig {
  private final StoreType storeType;
  private final boolean autoInit;
  private final long maxSize;

  StoreConfig(StoreType storeType, boolean autoInit, long maxSize) {
    this.storeType = requireNonNull(storeType);
    this.autoInit = autoInit;
    this.maxSize = maxSize;
  }

  public StoreType storeType() {
    return storeType;
  }

  public long maxSize() {
    return maxSize;
  }

  public boolean autoInit() {
    return autoInit;
  }

  public static StoreConfig createDefault(StoreType storeType) {
    switch (storeType) {
      case MEMORY:
        return new MemoryStoreConfig(true, Long.MAX_VALUE);
      case DISK:
        return new DiskStoreConfig(
            true, Long.MAX_VALUE, FileSystemType.SYSTEM, Execution.ASYNC, null, true, 1);
      case REDIS:
        return new RedisStoreConfig(true, 15000, 10000, 1);
      default:
        throw new AssertionError();
    }
  }
}
