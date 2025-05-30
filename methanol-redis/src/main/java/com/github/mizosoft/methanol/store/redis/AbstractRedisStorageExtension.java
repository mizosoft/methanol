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

package com.github.mizosoft.methanol.store.redis;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.Store;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.async.RedisHashAsyncCommands;
import io.lettuce.core.api.async.RedisScriptingAsyncCommands;
import io.lettuce.core.api.async.RedisStringAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.api.sync.RedisHashCommands;
import io.lettuce.core.api.sync.RedisKeyCommands;
import io.lettuce.core.api.sync.RedisScriptingCommands;
import io.lettuce.core.api.sync.RedisStringCommands;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

abstract class AbstractRedisStorageExtension<
        C extends StatefulConnection<String, ByteBuffer>,
        CMD extends
            RedisHashCommands<String, ByteBuffer> & RedisScriptingCommands<String, ByteBuffer>
                & RedisKeyCommands<String, ByteBuffer> & RedisStringCommands<String, ByteBuffer>,
        ASYNC_CMD extends
            RedisHashAsyncCommands<String, ByteBuffer>
                & RedisScriptingAsyncCommands<String, ByteBuffer>
                & RedisStringAsyncCommands<String, ByteBuffer>>
    implements RedisStorageExtension {
  final RedisConnectionProvider<C> connectionProvider;
  final int editorLockInactiveTtlSeconds;
  final int staleEntryInactiveTtlSeconds;

  AbstractRedisStorageExtension(
      RedisConnectionProvider<C> connectionProvider,
      int editorLockInactiveTtlSeconds,
      int staleEntryInactiveTtlSeconds) {
    this.connectionProvider = connectionProvider;
    this.editorLockInactiveTtlSeconds = editorLockInactiveTtlSeconds;
    this.staleEntryInactiveTtlSeconds = staleEntryInactiveTtlSeconds;
  }

  @Override
  public Store createStore(Executor executor, int appVersion) {
    try {
      return Utils.get(
          connectionProvider
              .connectAsync()
              .thenApply(connection -> createStore(connection, appVersion))
              .toCompletableFuture());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      throw new UncheckedIOException(Utils.toInterruptedIOException(e));
    }
  }

  abstract AbstractRedisStore<C, CMD, ASYNC_CMD> createStore(C connection, int appVersion);

  static final class RedisStandaloneStorageExtension
      extends AbstractRedisStorageExtension<
          StatefulRedisConnection<String, ByteBuffer>,
          RedisCommands<String, ByteBuffer>,
          RedisAsyncCommands<String, ByteBuffer>> {
    RedisStandaloneStorageExtension(
        RedisConnectionProvider<StatefulRedisConnection<String, ByteBuffer>> connectionProvider,
        int editorLockInactiveTtlSeconds,
        int staleEntryInactiveTtlSeconds) {
      super(connectionProvider, editorLockInactiveTtlSeconds, staleEntryInactiveTtlSeconds);
    }

    @Override
    RedisStandaloneStore createStore(
        StatefulRedisConnection<String, ByteBuffer> connection, int appVersion) {
      return new RedisStandaloneStore(
          connection,
          connectionProvider,
          editorLockInactiveTtlSeconds,
          staleEntryInactiveTtlSeconds,
          appVersion);
    }
  }

  static final class RedisClusterStorageExtension
      extends AbstractRedisStorageExtension<
          StatefulRedisClusterConnection<String, ByteBuffer>,
          RedisClusterCommands<String, ByteBuffer>,
          RedisClusterAsyncCommands<String, ByteBuffer>> {
    RedisClusterStorageExtension(
        RedisConnectionProvider<StatefulRedisClusterConnection<String, ByteBuffer>>
            connectionProvider,
        int editorLockInactiveTtlSeconds,
        int staleEntryInactiveTtlSeconds) {
      super(connectionProvider, editorLockInactiveTtlSeconds, staleEntryInactiveTtlSeconds);
    }

    @Override
    RedisClusterStore createStore(
        StatefulRedisClusterConnection<String, ByteBuffer> connection, int appVersion) {
      return new RedisClusterStore(
          connection,
          connectionProvider,
          editorLockInactiveTtlSeconds,
          staleEntryInactiveTtlSeconds,
          appVersion);
    }
  }
}
