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

import io.lettuce.core.AbstractRedisClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

abstract class AbstractRedisStoreContext<R extends AutoCloseable> extends StoreContext {
  final List<AbstractRedisClient> createdClients = new ArrayList<>();
  private @MonotonicNonNull R lazyRedisServer;

  AbstractRedisStoreContext(AbstractRedisStoreConfig config) {
    super(config);
  }

  R getOrCreateRedisServer() throws IOException {
    var server = lazyRedisServer;
    if (server == null) {
      server = createRedisServer();
      lazyRedisServer = server;
    }
    return server;
  }

  abstract R createRedisServer() throws IOException;

  public AbstractRedisStoreConfig config() {
    return (AbstractRedisStoreConfig) super.config();
  }

  <T extends AbstractRedisClient> T registerClient(T client) {
    createdClients.add(client);
    return client;
  }

  @Override
  void close(List<Exception> exceptions) {
    super.close(exceptions);
    for (var client : createdClients) {
      try {
        client.close();
      } catch (Exception e) {
        exceptions.add(e);
      }
    }
    createdClients.clear();

    var server = lazyRedisServer;
    if (server != null) {
      try {
        server.close();
      } catch (Exception e) {
        exceptions.add(e);
      }
    }
  }
}
