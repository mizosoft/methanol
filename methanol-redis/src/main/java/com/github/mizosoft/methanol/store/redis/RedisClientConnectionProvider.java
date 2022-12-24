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

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.Utils;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;

/**
 * A {@link RedisConnectionProvider} that creates connections using a provided subtype of {@link
 * AbstractRedisClient}.
 */
abstract class RedisClientConnectionProvider<
        CLIENT extends AbstractRedisClient, CONN extends StatefulConnection<String, ByteBuffer>>
    implements RedisConnectionProvider<CONN> {
  private static final RedisCodec<String, ByteBuffer> CODEC =
      RedisCodec.of(StringCodec.UTF8, ByteBufferCodec.INSTANCE);

  final CLIENT client;
  final boolean closeClient;

  RedisClientConnectionProvider(CLIENT client, boolean closeClient) {
    this.client = requireNonNull(client);
    this.closeClient = closeClient;
  }

  @Override
  public void release(CONN connection) {
    connection.close();
  }

  @Override
  public void close() {
    if (closeClient) {
      client.close();
    }
  }

  static final class RedisStandaloneConnectionProvider
      extends RedisClientConnectionProvider<
          RedisClient, StatefulRedisConnection<String, ByteBuffer>> {
    private final RedisURI redisUri;

    RedisStandaloneConnectionProvider(RedisURI redisUri, RedisClient client, boolean closeClient) {
      super(client, closeClient);
      this.redisUri = requireNonNull(redisUri);
    }

    @Override
    public CompletionStage<StatefulRedisConnection<String, ByteBuffer>> connectAsync() {
      return client.connectAsync(CODEC, redisUri);
    }
  }

  static final class RedisClusterConnectionProvider
      extends RedisClientConnectionProvider<
          RedisClusterClient, StatefulRedisClusterConnection<String, ByteBuffer>> {
    RedisClusterConnectionProvider(RedisClusterClient client, boolean closeClient) {
      super(client, closeClient);
    }

    @Override
    public CompletionStage<StatefulRedisClusterConnection<String, ByteBuffer>> connectAsync() {
      return client.refreshPartitionsAsync().thenCompose(__ -> client.connectAsync(CODEC));
    }
  }

  private enum ByteBufferCodec implements RedisCodec<ByteBuffer, ByteBuffer> {
    INSTANCE;

    @Override
    public ByteBuffer decodeKey(ByteBuffer bytes) {
      return copy(bytes);
    }

    @Override
    public ByteBuffer decodeValue(ByteBuffer bytes) {
      return copy(bytes);
    }

    @Override
    public ByteBuffer encodeKey(ByteBuffer key) {
      return copy(key);
    }

    @Override
    public ByteBuffer encodeValue(ByteBuffer value) {
      return copy(value);
    }

    private static ByteBuffer copy(ByteBuffer source) {
      // Copy the buffer without consuming it.
      return Utils.copy(source.duplicate());
    }
  }
}
