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

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.Store;
import com.github.mizosoft.methanol.testing.AggregateException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates and manages {@link Store} instances corresponding to a given configuration. There's a
 * specialized context class for each store type.
 */
public abstract class StoreContext implements AutoCloseable {
  private final StoreConfig config;
  private final List<Store> createdStores = new ArrayList<>();
  private boolean closed;

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

  /** Logs debug info after a test failure. */
  void logDebugInfo() {}

  @Override
  public void close() throws Exception {
    if (closed) {
      return;
    }

    closed = true;
    var exceptions = new ArrayList<Exception>();
    close(exceptions);
    if (exceptions.size() == 1) {
      throw exceptions.get(0);
    } else if (exceptions.size() > 1) {
      throw new AggregateException("Multiple exceptions thrown while closing", exceptions);
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

  @Override
  public String toString() {
    return Utils.toStringIdentityPrefix(this) + "[config=" + config() + "]";
  }

  public static StoreContext of(StoreConfig config) throws IOException {
    if (config instanceof MemoryStoreConfig) {
      return new MemoryStoreContext((MemoryStoreConfig) config);
    } else if (config instanceof DiskStoreConfig) {
      return new DiskStoreContext((DiskStoreConfig) config);
    } else if (config instanceof RedisStandaloneStoreConfig) {
      return new RedisStandaloneStoreContext((RedisStandaloneStoreConfig) config);
    } else if (config instanceof RedisClusterStoreConfig) {
      return new RedisClusterStoreContext((RedisClusterStoreConfig) config);
    } else {
      throw new IllegalArgumentException("Unexpected config: " + config);
    }
  }
}
