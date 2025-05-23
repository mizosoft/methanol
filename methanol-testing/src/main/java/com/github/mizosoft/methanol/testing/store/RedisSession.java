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

package com.github.mizosoft.methanol.testing.store;

import io.lettuce.core.api.StatefulConnection;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** A session with a Redis Standalone or Cluster setup. */
public interface RedisSession extends AutoCloseable {

  /** Returns the log files attached to this session. */
  List<Path> logFiles();

  /**
   * Resets this session to its initial state. Returns {@code true} if the session is operable after
   * being reset.
   */
  boolean reset();

  /** Returns {@code true} if this session is operable. */
  boolean isHealthy();

  /** Returns a connection to the redis server represented by this session. */
  StatefulConnection<String, String> connect();

  @Override
  void close() throws IOException;
}
