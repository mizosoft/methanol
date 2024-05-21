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

package com.github.mizosoft.methanol.internal.concurrent;

import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

public final class Lazy<T> implements Supplier<T> {
  private final ReentrantLock lock = new ReentrantLock();
  private final Supplier<T> factory;

  private volatile @MonotonicNonNull T lazyValue;

  private Lazy(Supplier<T> factory) {
    this.factory = requireNonNull(factory);
  }

  @Override
  public T get() {
    var value = lazyValue;
    if (value == null) {
      // Ensure we're not initializing the value recursively.
      requireState(!lock.isHeldByCurrentThread(), "Recursive initialization of a lazy value");

      lock.lock();
      try {
        value = lazyValue;
        if (value == null) {
          value = requireNonNull(factory.get());
          lazyValue = value;
        }
      } finally {
        lock.unlock();
      }
    }
    return value;
  }

  public static <T> Lazy<T> of(Supplier<T> factory) {
    return new Lazy<>(factory);
  }
}
