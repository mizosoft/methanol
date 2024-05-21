/*
 * Copyright (c) 2024 Moataz Abdelnasser
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

import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.StorageExtension;
import com.github.mizosoft.methanol.internal.cache.InternalStorageExtension;
import com.github.mizosoft.methanol.store.redis.AbstractRedisStorageExtension.RedisClusterStorageExtension;
import com.github.mizosoft.methanol.store.redis.AbstractRedisStorageExtension.RedisStandaloneStorageExtension;
import com.github.mizosoft.methanol.store.redis.RedisClientConnectionProvider.RedisClusterConnectionProvider;
import com.github.mizosoft.methanol.store.redis.RedisClientConnectionProvider.RedisStandaloneConnectionProvider;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import java.nio.ByteBuffer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A {@link StorageExtension} that provides storage on either a Redis Standalone instance or a Redis
 * Cluster. Instances are created with {@link Builder}.
 */
public interface RedisStorageExtension extends InternalStorageExtension {
  static Builder newBuilder() {
    return new Builder();
  }

  /** A builder of {@code RedisStorageExtension}. */
  final class Builder {
    private static final int UNSET_NUMBER = -1;

    private static final int DEFAULT_EDITOR_LOCK_TTL_SECONDS = 10;
    private static final int DEFAULT_STALE_ENTRY_TTL_SECONDS = 5;

    private @MonotonicNonNull RedisStorageExtensionFactory factory;
    private int editorLockTtlSeconds = UNSET_NUMBER;
    private int staleEntryTtlSeconds = UNSET_NUMBER;

    Builder() {}

    /** Specifies the URI of the Redis Standalone instance. */
    @CanIgnoreReturnValue
    public Builder standalone(RedisURI redisUri) {
      return standalone(
          new RedisStandaloneConnectionProvider(redisUri, RedisClient.create(), true));
    }

    /** Specifies the URI of the Redis Standalone instance and the client used to connect to it. */
    @CanIgnoreReturnValue
    public Builder standalone(RedisURI redisUri, RedisClient client) {
      return standalone(new RedisStandaloneConnectionProvider(redisUri, client, false));
    }

    /** Specifies the connection provider used to connect to the Redis Standalone instance */
    @CanIgnoreReturnValue
    public Builder standalone(
        RedisConnectionProvider<StatefulRedisConnection<String, ByteBuffer>> connectionProvider) {
      requireNonNull(connectionProvider);
      this.factory =
          (editorLockTtlSeconds, staleEntryTtlSeconds) ->
              new RedisStandaloneStorageExtension(
                  connectionProvider, editorLockTtlSeconds, staleEntryTtlSeconds);
      return this;
    }

    /** Specifies one or more URIs for discovering the topology of the Redis Cluster instance. */
    @CanIgnoreReturnValue
    public Builder cluster(Iterable<RedisURI> redisUris) {
      return cluster(
          new RedisClusterConnectionProvider(RedisClusterClient.create(redisUris), true));
    }

    /** Specifies the client used to connect to the Redis Cluster instance. */
    @CanIgnoreReturnValue
    public Builder cluster(RedisClusterClient client) {
      return cluster(new RedisClusterConnectionProvider(client, false));
    }

    /** Specifies the connection provider used to connect to the Redis Cluster instance. */
    @CanIgnoreReturnValue
    public Builder cluster(
        RedisConnectionProvider<StatefulRedisClusterConnection<String, ByteBuffer>>
            connectionProvider) {
      requireNonNull(connectionProvider);
      this.factory =
          (editorLockTtlSeconds, staleEntryTtlSeconds) ->
              new RedisClusterStorageExtension(
                  connectionProvider, editorLockTtlSeconds, staleEntryTtlSeconds);
      return this;
    }

    /**
     * Specifies the number of seconds an active entry editor (writer) remains valid. If the given
     * number of seconds passes with no signals from the editor (either because of a crash, or it
     * was suspended for some reason), any data written by the editor is discarded and the older
     * entry (if any) is retained.
     *
     * @throws IllegalArgumentException if the given number of seconds is negative
     */
    @CanIgnoreReturnValue
    public Builder editorLockTtlSeconds(int editorLockTtlSeconds) {
      requireArgument(editorLockTtlSeconds >= 0, "negative ttl: %d", editorLockTtlSeconds);
      this.editorLockTtlSeconds = editorLockTtlSeconds;
      return this;
    }

    /**
     * Specifies the number of seconds a stale entry remains valid for already active readers after
     * being deleted or replaced with a new entry by an editor (writer). This gives said readers a
     * chance to finish reading the older entry.
     *
     * @throws IllegalArgumentException if the given number of seconds is negative
     */
    @CanIgnoreReturnValue
    public Builder staleEntryTtlSeconds(int staleEntryTtlSeconds) {
      requireArgument(staleEntryTtlSeconds >= 0, "negative ttl: %d", staleEntryTtlSeconds);
      this.staleEntryTtlSeconds = staleEntryTtlSeconds;
      return this;
    }

    /** Creates a new {@code RedisStorageExtension}. */
    public RedisStorageExtension build() {
      int editorLockTtlSeconds = this.editorLockTtlSeconds;
      if (editorLockTtlSeconds == UNSET_NUMBER) {
        editorLockTtlSeconds = DEFAULT_EDITOR_LOCK_TTL_SECONDS;
      }
      int staleEntryTtlSeconds = this.staleEntryTtlSeconds;
      if (staleEntryTtlSeconds == UNSET_NUMBER) {
        staleEntryTtlSeconds = DEFAULT_STALE_ENTRY_TTL_SECONDS;
      }
      return factory.create(editorLockTtlSeconds, staleEntryTtlSeconds);
    }

    @FunctionalInterface
    private interface RedisStorageExtensionFactory {
      RedisStorageExtension create(int editorLockTtlSeconds, int staleEntryTtlSeconds);
    }
  }
}
