/*
 * Copyright (c) 2024 Moataz Hussein
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

import io.lettuce.core.api.StatefulConnection;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;

/**
 * A strategy for connecting to Redis using Lettuce.
 *
 * @param <C> the type of the connection
 */
public interface RedisConnectionProvider<C extends StatefulConnection<String, ByteBuffer>>
    extends AutoCloseable {

  /** Asynchronously connects to Redis and returns the connection. */
  CompletionStage<C> connectAsync();

  /** Specifies that a connection returned by this provider is not needed anymore. */
  void release(C connection);

  /** Releases the resources associated with this {@code RedisConnectionProvider}. */
  @Override
  void close();
}
