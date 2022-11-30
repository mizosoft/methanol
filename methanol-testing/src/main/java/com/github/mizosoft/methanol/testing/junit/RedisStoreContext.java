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
import com.github.mizosoft.methanol.store.redis.ByteBufferCodec;
import com.github.mizosoft.methanol.store.redis.RedisStore;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

public final class RedisStoreContext extends StoreContext {
  private @MonotonicNonNull LocalRedisSession lazySession;
  private final List<String> redisLog = new ArrayList<>();

  private RedisStoreContext(RedisStoreConfig config) {
    super(config);
  }

  @Override
  public RedisStoreConfig config() {
    return (RedisStoreConfig) super.config();
  }

  @Override
  Store createStore() throws IOException {
    var config = config();
    return new RedisStore(
        getOrStartSession()
            .client()
            .connect(RedisCodec.of(StringCodec.UTF8, ByteBufferCodec.INSTANCE)),
        config.editorLockTimeToLiveSeconds().orElse(15),
        config.staleEntryTimeToLiveSeconds().orElse(15),
        config.appVersion());
  }

  @Override
  void attachDebugInfo(Throwable exception) throws IOException {
    var session = lazySession;
    if (session != null) {
      session.dumpRemainingLog(redisLog);
      exception.addSuppressed(new RedisLogAttachment(redisLog));
    }
  }

  @Override
  void close(List<Exception> exceptions) {
    super.close(exceptions);
    try {
      var session = lazySession;
      if (session != null) {
        session.close();
      }
    } catch (Exception e) {
      exceptions.add(e);
    }
  }

  private LocalRedisSession getOrStartSession() throws IOException {
    var session = lazySession;
    if (session == null) {
      try {
        session = LocalRedisSession.start(redisLog);
      } catch (TimeoutException | InterruptedException e) {
        var uncheckedEx = new CompletionException(e);
        uncheckedEx.addSuppressed(new RedisLogAttachment(redisLog));
        throw uncheckedEx;
      }
      lazySession = session;
    }
    return session;
  }

  public static RedisStoreContext from(RedisStoreConfig config) throws IOException {
    return new RedisStoreContext(config);
  }

  public static boolean isAvailable() {
    return LocalRedisSession.isAvailable();
  }

  /**
   * A {@code Throwable} that is used to attach redis-server's log to a test exception as a
   * suppressed {@code Throwable}. This {@code Throwable} is hence not associated with any stack
   * trace.
   */
  private static final class RedisLogAttachment extends Throwable {
    public RedisLogAttachment(List<String> log) {
      super(
          "redis log:"
              + System.lineSeparator()
              + log.stream().collect(Collectors.joining(System.lineSeparator())));
      setStackTrace(new StackTraceElement[0]);
    }
  }
}
