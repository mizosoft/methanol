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

import com.github.mizosoft.methanol.store.redis.RedisStorageExtension;
import java.io.IOException;

public final class RedisClusterStoreContext extends AbstractRedisStoreContext<LocalRedisCluster> {
  private static final int MASTER_NODE_COUNT = 3;
  private static final int REPLICAS_PER_MASTER = 1;

  private RedisClusterStoreContext(RedisClusterStoreConfig config) {
    super(config);
  }

  @Override
  LocalRedisCluster createRedisServer() throws IOException {
    return LocalRedisCluster.start(MASTER_NODE_COUNT, REPLICAS_PER_MASTER);
  }

  @Override
  void configure(RedisStorageExtension.Builder builder) throws IOException {
    builder.cluster(getOrCreateRedisServer().uris());
  }

  public static RedisClusterStoreContext from(RedisClusterStoreConfig config) throws IOException {
    return new RedisClusterStoreContext(config);
  }

  public static boolean isAvailable() {
    return RedisSupport.isRedisClusterAvailable();
  }
}
