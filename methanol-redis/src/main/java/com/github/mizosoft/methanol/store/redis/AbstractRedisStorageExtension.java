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

package com.github.mizosoft.methanol.store.redis;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.Store;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

abstract class AbstractRedisStorageExtension<C extends StatefulConnection<String, ByteBuffer>>
    implements RedisStorageExtension {
  final RedisConnectionProvider<C> connectionProvider;
  final int editorLockTtlSeconds;
  final int staleEntryTtlSeconds;

  AbstractRedisStorageExtension(
      RedisConnectionProvider<C> connectionProvider,
      int editorLockTtlSeconds,
      int staleEntryTtlSeconds) {
    this.connectionProvider = connectionProvider;
    this.editorLockTtlSeconds = editorLockTtlSeconds;
    this.staleEntryTtlSeconds = staleEntryTtlSeconds;
  }

  @Override
  public Store createStore(Executor executor, int appVersion) {
    try {
      return Utils.get(createStoreAsync(executor, appVersion));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      throw new UncheckedIOException(Utils.toInterruptedIOException(e));
    }
  }

  @Override
  public CompletableFuture<? extends Store> createStoreAsync(Executor executor, int appVersion) {
    return connectionProvider
        .connectAsync()
        .thenApply(connection -> createStore(connection, appVersion))
        .toCompletableFuture();
  }

  abstract AbstractRedisStore<C> createStore(C connection, int appVersion);

  static final class RedisStandaloneStorageExtension
      extends AbstractRedisStorageExtension<StatefulRedisConnection<String, ByteBuffer>> {
    RedisStandaloneStorageExtension(
        RedisConnectionProvider<StatefulRedisConnection<String, ByteBuffer>> connectionProvider,
        int editorLockTtlSeconds,
        int staleEntryTtlSeconds) {
      super(connectionProvider, editorLockTtlSeconds, staleEntryTtlSeconds);
    }

    @Override
    RedisStandaloneStore createStore(
        StatefulRedisConnection<String, ByteBuffer> connection, int appVersion) {
      return new RedisStandaloneStore(
          connection, connectionProvider, editorLockTtlSeconds, staleEntryTtlSeconds, appVersion);
    }
  }

  static final class RedisClusterStorageExtension
      extends AbstractRedisStorageExtension<StatefulRedisClusterConnection<String, ByteBuffer>> {
    RedisClusterStorageExtension(
        RedisConnectionProvider<StatefulRedisClusterConnection<String, ByteBuffer>>
            connectionProvider,
        int editorLockTtlSeconds,
        int staleEntryTtlSeconds) {
      super(connectionProvider, editorLockTtlSeconds, staleEntryTtlSeconds);
    }

    @Override
    RedisClusterStore createStore(
        StatefulRedisClusterConnection<String, ByteBuffer> connection, int appVersion) {
      return new RedisClusterStore(
          connection, connectionProvider, editorLockTtlSeconds, staleEntryTtlSeconds, appVersion);
    }
  }
}
