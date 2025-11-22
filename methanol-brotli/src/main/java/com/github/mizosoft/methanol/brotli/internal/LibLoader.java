/*
 * Copyright (c) 2025 Moataz Hussein
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

package com.github.mizosoft.methanol.brotli.internal;

/** Abstraction for loading native libraries. */
interface LibLoader {
  /** Loads the native library from the specified absolute path. */
  void load(String absolutePath) throws UnsatisfiedLinkError;

  /** Loads the native library with the specified name from the library path. */
  void loadLibrary(String libName) throws UnsatisfiedLinkError;

  /** System implementation that delegates to {@link System#load} and {@link System#loadLibrary}. */
  enum SystemLibLoader implements LibLoader {
    INSTANCE;

    @Override
    public void load(String absolutePath) throws UnsatisfiedLinkError {
      System.load(absolutePath);
    }

    @Override
    public void loadLibrary(String libName) throws UnsatisfiedLinkError {
      System.loadLibrary(libName);
    }
  }
}
