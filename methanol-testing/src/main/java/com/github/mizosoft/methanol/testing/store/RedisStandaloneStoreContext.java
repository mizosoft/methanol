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
import io.lettuce.core.api.StatefulRedisConnection;
import java.io.IOException;

public final class RedisStandaloneStoreContext
    extends AbstractRedisStoreContext<RedisStandaloneSession> {
  private static final RedisSessionSingletonPool<RedisStandaloneSession> serverPool =
      new RedisSessionSingletonPool<>(RedisStandaloneSession::start);

  RedisStandaloneStoreContext(RedisStandaloneStoreConfig config) throws IOException {
    super(config, serverPool);
  }

  @Override
  void configure(RedisStorageExtension.Builder builder) {
    builder.standalone(session.uri());
  }

  @Override
  public StatefulRedisConnection<String, String> connect() {
    return session.connect();
  }

  public static boolean isAvailable() {
    return RedisSupport.isRedisStandaloneAvailable();
  }
}
