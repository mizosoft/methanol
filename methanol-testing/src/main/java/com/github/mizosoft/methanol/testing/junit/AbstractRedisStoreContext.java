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
import com.github.mizosoft.methanol.store.redis.RedisStorageExtension;
import java.io.IOException;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

abstract class AbstractRedisStoreContext<R extends RedisSession> extends StoreContext {
  private final RedisSessionSingletonPool<R> sessionPool;
  private @MonotonicNonNull R lazySession;

  AbstractRedisStoreContext(
      AbstractRedisStoreConfig config, RedisSessionSingletonPool<R> sessionPool) {
    super(config);
    this.sessionPool = sessionPool;
  }

  abstract void configure(RedisStorageExtension.Builder builder) throws IOException;

  R getSession() throws IOException {
    var session = lazySession;
    if (session == null) {
      session = sessionPool.acquire();
      lazySession = session;
    }
    return session;
  }

  void releaseSession(boolean returnToPool) throws IOException {
    var session = lazySession;
    if (session != null) {
      lazySession = null;
      if (returnToPool) {
        sessionPool.release(session);
      } else {
        session.close();
      }
    }
  }

  @Override
  Store createStore() throws IOException {
    var builder = RedisStorageExtension.newBuilder();
    configure(builder);
    config().editorLockTtlSeconds().ifPresent(builder::editorLockTtlSeconds);
    config().staleEntryTtlSeconds().ifPresent(builder::staleEntryTtlSeconds);
    return builder.build().createStore(Runnable::run, config().appVersion());
  }

  public AbstractRedisStoreConfig config() {
    return (AbstractRedisStoreConfig) super.config();
  }

  @Override
  void close(List<Exception> exceptions) {
    super.close(exceptions);

    try {
      releaseSession(true);
    } catch (IOException e) {
      exceptions.add(e);
    }
  }
}
