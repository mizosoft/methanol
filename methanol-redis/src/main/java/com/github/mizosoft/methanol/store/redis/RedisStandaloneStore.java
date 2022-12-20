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

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisHashAsyncCommands;
import io.lettuce.core.api.async.RedisKeyAsyncCommands;
import io.lettuce.core.api.async.RedisScriptingAsyncCommands;
import io.lettuce.core.api.async.RedisStringAsyncCommands;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/** A {@code Store} implementation backed by a Redis Standalone instance. */
class RedisStandaloneStore extends AbstractRedisStore<StatefulRedisConnection<String, ByteBuffer>> {
  RedisStandaloneStore(
      StatefulRedisConnection<String, ByteBuffer> connection,
      RedisConnectionProvider<StatefulRedisConnection<String, ByteBuffer>> connectionProvider,
      int editorLockTtlSeconds,
      int staleEntryTtlSeconds,
      int appVersion) {
    super(
        connection,
        connectionProvider,
        editorLockTtlSeconds,
        staleEntryTtlSeconds,
        appVersion,
        String.format("methanol:%d:%d:mclock", STORE_VERSION, appVersion));
  }

  @SuppressWarnings("unchecked")
  @Override
  <
          CMD extends
              RedisHashAsyncCommands<String, ByteBuffer>
                  & RedisScriptingAsyncCommands<String, ByteBuffer>
                  & RedisKeyAsyncCommands<String, ByteBuffer>
                  & RedisStringAsyncCommands<String, ByteBuffer>>
      CMD commands() {
    return (CMD) connection.async();
  }

  @Override
  public CompletableFuture<Void> removeAllAsync(List<String> keys) {
    var entryKeys = keys.stream().map(this::toEntryKey).collect(Collectors.toUnmodifiableList());
    return Script.REMOVE_ALL
        .evalOn(commands())
        .asVoid(entryKeys, List.of(encodeLong(staleEntryTtlSeconds)));
  }
}
