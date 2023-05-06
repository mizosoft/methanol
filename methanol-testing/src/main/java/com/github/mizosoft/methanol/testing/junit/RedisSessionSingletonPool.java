/*
 * Copyright (c) 2023 Moataz Abdelnasser
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

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.function.ThrowingSupplier;
import com.github.mizosoft.methanol.internal.function.Unchecked;
import java.io.IOException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** A pool of a single {@link RedisSession} instance. */
class RedisSessionSingletonPool<R extends RedisSession> {
  private final ThrowingSupplier<R> factory;
  private @MonotonicNonNull R instance;

  RedisSessionSingletonPool(ThrowingSupplier<R> factory) {
    this.factory = requireNonNull(factory);
    Runtime.getRuntime().addShutdownHook(new Thread(Unchecked.runnable(this::closePooledInstance)));
  }

  synchronized R acquire() throws IOException {
    var instance = this.instance;
    if (instance != null && instance.isHealthy()) {
      this.instance = null;
      return instance;
    }

    if (instance != null) {
      this.instance = null;
      instance.close();
    }

    try {
      return factory.get();
    } catch (Exception e) {
      if (e instanceof RuntimeException) {
        throw (RuntimeException) e;
      } else if (e instanceof IOException) {
        throw (IOException) e;
      } else {
        throw new IOException(e);
      }
    }
  }

  synchronized void release(R instance) throws IOException {
    if (this.instance == null && instance.reset()) {
      this.instance = instance;
    } else {
      instance.close();
    }
  }

  synchronized void closePooledInstance() throws IOException {
    var instance = this.instance;
    this.instance = null;
    if (instance != null) {
      instance.close();
    }
  }
}
