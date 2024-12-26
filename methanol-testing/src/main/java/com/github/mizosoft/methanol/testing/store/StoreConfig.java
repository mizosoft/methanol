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

package com.github.mizosoft.methanol.testing.store;

import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public abstract class StoreConfig {
  static final int UNSET_NUMBER = -1;

  private final StoreType storeType;
  private final long maxSize;
  private final int appVersion;

  StoreConfig(StoreType storeType, long maxSize, int appVersion) {
    requireArgument(maxSize > 0, "non-positive maxSize: %s", maxSize);
    this.storeType = requireNonNull(storeType);
    this.maxSize = maxSize;
    this.appVersion = appVersion;
  }

  public StoreType storeType() {
    return storeType;
  }

  public long maxSize() {
    return maxSize;
  }

  public int appVersion() {
    return appVersion;
  }

  @Override
  public abstract String toString();

  public static StoreConfig createDefault(StoreType storeType) {
    switch (storeType) {
      case MEMORY:
        return new MemoryStoreConfig(Long.MAX_VALUE);
      case DISK:
        return new DiskStoreConfig(
            Long.MAX_VALUE,
            1,
            FileSystemType.SYSTEM,
            Execution.ASYNC,
            UNSET_NUMBER,
            true,
            true,
            true);
      case REDIS_STANDALONE:
        return new RedisStandaloneStoreConfig(1, UNSET_NUMBER, UNSET_NUMBER);
      case REDIS_CLUSTER:
        return new RedisClusterStoreConfig(1, UNSET_NUMBER, UNSET_NUMBER);
      default:
        throw new AssertionError();
    }
  }

  public enum StoreType {
    MEMORY,
    DISK,
    REDIS_STANDALONE,
    REDIS_CLUSTER
  }

  public enum FileSystemType {
    IN_MEMORY,
    SYSTEM,
    EMULATED_WINDOWS, // See WindowsEmulatingFileSystem.
    NONE
  }

  public enum Execution {
    SAME_THREAD {
      @Override
      public Executor newExecutor() {
        return FlowSupport.SYNC_EXECUTOR;
      }
    },
    ASYNC {
      @Override
      public Executor newExecutor() {
        return Executors.newCachedThreadPool();
      }
    };

    abstract Executor newExecutor();
  }
}
