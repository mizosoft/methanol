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

package com.github.mizosoft.methanol.testing.store;

import com.github.mizosoft.methanol.store.redis.RedisStorageExtension;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import java.io.IOException;

public final class RedisClusterStoreContext extends AbstractRedisStoreContext<RedisClusterSession> {
  private static final int MASTER_NODE_COUNT = 3;
  private static final int REPLICAS_PER_MASTER = 1;

  private static final RedisSessionSingletonPool<RedisClusterSession> clusterPool =
      new RedisSessionSingletonPool<>(
          () -> RedisClusterSession.start(MASTER_NODE_COUNT, REPLICAS_PER_MASTER));

  RedisClusterStoreContext(RedisClusterStoreConfig config) throws IOException {
    super(config, clusterPool);
  }

  @Override
  void configure(RedisStorageExtension.Builder builder) {
    builder.cluster(session.uris());
  }

  @Override
  public StatefulRedisClusterConnection<String, String> connect() {
    return session.connect();
  }

  public static boolean isAvailable() {
    return RedisSupport.isRedisClusterAvailable();
  }
}
