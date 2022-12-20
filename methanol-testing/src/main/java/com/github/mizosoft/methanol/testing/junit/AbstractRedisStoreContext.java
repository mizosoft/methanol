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
import com.github.mizosoft.methanol.internal.function.Unchecked;
import com.github.mizosoft.methanol.store.redis.RedisStorageExtension;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

abstract class AbstractRedisStoreContext<R extends RedisSession> extends StoreContext {
  private static final int MAX_TAIL_LENGTH = 15;

  private static final Logger logger = System.getLogger(AbstractRedisStoreContext.class.getName());

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
      var session = lazySession;
      if (session != null) {
        lazySession = null;
        sessionPool.release(session);
      }
    } catch (IOException e) {
      exceptions.add(e);
    }
  }

  @Override
  void logDebugInfo() {
    var session = lazySession;
    if (session != null) {
      session.logFiles().stream()
          .map(Unchecked.func(AbstractRedisStoreContext::tail))
          .forEach(log -> logger.log(Level.WARNING, log));
    }
  }

  /**
   * Returns a string containing at most the last {@value MAX_TAIL_LENGTH} lines of the given file.
   */
  private static String tail(Path file) throws IOException {
    // This is somewhat inefficient, but it suffices for current usage.
    var tail = new ArrayDeque<String>();
    try (var stream = Files.lines(file)) {
      stream.forEach(
          line -> {
            tail.add(line);
            if (tail.size() > MAX_TAIL_LENGTH) {
              tail.removeFirst();
            }
          });
    }
    return tail.stream().collect(Collectors.joining(System.lineSeparator()));
  }
}
