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

import com.github.mizosoft.methanol.internal.cache.Store;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A group of created {@link Store} instances and their associated configuration as required by a
 * test case. There's a specialized context class for each store type.
 */
public abstract class StoreContext implements AutoCloseable {
  private final StoreConfig config;
  private final List<Store> createdStores = new ArrayList<>();

  StoreContext(StoreConfig config) {
    this.config = requireNonNull(config);
  }

  public StoreConfig config() {
    return config;
  }

  public Store createAndRegisterStore() throws IOException {
    var store = createStore();
    createdStores.add(store);
    return store;
  }

  abstract Store createStore() throws IOException;

  /** If execution is queued, makes sure all tasks queued so far are executed. */
  public void drainQueuedTasksIfNeeded() {}

  /** Logs debug info after a test failure. */
  void logDebugInfo() {}

  @Override
  public void close() throws Exception {
    var exceptions = new ArrayList<Exception>();
    close(exceptions);
    if (exceptions.size() == 1) {
      throw exceptions.get(0);
    } else if (exceptions.size() > 1) {
      var compositeException = new IOException("encountered one or more exceptions when closing");
      exceptions.forEach(compositeException::addSuppressed);
      throw compositeException;
    }
  }

  void close(List<Exception> exceptions) {
    for (var store : createdStores) {
      try {
        store.close();
      } catch (Exception e) {
        exceptions.add(e);
      }
    }
  }

  public static StoreContext from(StoreConfig config) throws IOException {
    if (config instanceof MemoryStoreConfig) {
      return MemoryStoreContext.from((MemoryStoreConfig) config);
    } else if (config instanceof DiskStoreConfig) {
      return DiskStoreContext.from((DiskStoreConfig) config);
    } else if (config instanceof RedisStandaloneStoreConfig) {
      return RedisStandaloneStoreContext.from((RedisStandaloneStoreConfig) config);
    } else {
      return RedisClusterStoreContext.from((RedisClusterStoreConfig) config);
    }
  }
}
