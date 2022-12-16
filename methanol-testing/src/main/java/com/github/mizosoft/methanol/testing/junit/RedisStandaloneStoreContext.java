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

import com.github.mizosoft.methanol.internal.cache.Store;
import com.github.mizosoft.methanol.store.redis.RedisStandaloneStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.io.IOException;

public final class RedisStandaloneStoreContext extends AbstractRedisStoreContext<LocalRedisServer> {
  private RedisStandaloneStoreContext(RedisStandaloneStoreConfig config) {
    super(config);
  }

  @Override
  LocalRedisServer createRedisServer() throws IOException {
    return LocalRedisServer.start();
  }

  @Override
  Store createStore() throws IOException {
    var client = registerClient(RedisClient.create(getOrCreateRedisServer().uri()));
    return new RedisStandaloneStore(
        client.connect(RedisCodec.of(StringCodec.UTF8, ByteBufferCodec.INSTANCE)),
        config().editorLockTtlSeconds().orElse(15),
        config().staleEntryTtlSeconds().orElse(15),
        config().appVersion());
  }

  public static RedisStandaloneStoreContext from(RedisStandaloneStoreConfig config)
      throws IOException {
    return new RedisStandaloneStoreContext(config);
  }

  public static boolean isAvailable() {
    return RedisSupport.isRedisStandaloneAvailable();
  }
}
