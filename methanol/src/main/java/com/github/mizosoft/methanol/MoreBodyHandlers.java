/*
 * MIT License
 *
 * Copyright (c) 2019 Moataz Abdelnasser
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

package com.github.mizosoft.methanol;

import java.net.http.HttpResponse.BodyHandler;
import java.util.concurrent.Executor;

/**
 * Provides additional {@link java.net.http.HttpResponse.BodyHandler} implementations.
 */
public class MoreBodyHandlers {

  private MoreBodyHandlers() { // non-instantiable
  }

  /**
   * Returns a {@code BodyHandler} that wraps the result of the given handler in a {@link
   * BodyDecoder} if required. The decoder is created using the factory corresponding to the value
   * of the {@code Content-Type} header, throwing {@code UnsupportedOperationException} if no such
   * factory is registered. If the header is not present, the result of the given handler is
   * returned directly.
   *
   * <p>The {@code Content-Encoding} and {@code Content-Length} headers are removed when invoking
   * the given handler to avoid recursive decompression attempts or using the wrong body length.
   *
   * @param downstreamHandler the handler returning the downstream
   * @param <T>               the subscriber's body type
   */
  public static <T> BodyHandler<T> decoding(BodyHandler<T> downstreamHandler) {
    throw new UnsupportedOperationException("Not implemented");
  }

  /**
   * Returns a {@code BodyHandler} that wraps the result of the given handler in a {@link
   * BodyDecoder} with the given executor if required. The decoder is created using the factory
   * corresponding to the value of the {@code Content-Type} header, throwing {@code
   * UnsupportedOperationException} if no such factory is registered. If the header is not present,
   * the result of the given handler is returned directly.
   *
   * <p>The {@code Content-Encoding} and {@code Content-Length} headers are removed when invoking
   * the given handler to avoid recursive decompression attempts or using the wrong body length.
   *
   * @param downstreamHandler the handler returning the downstream
   * @param executor          the executor used to supply downstream items
   * @param <T>               the subscriber's body type
   */
  public static <T> BodyHandler<T> decoding(BodyHandler<T> downstreamHandler, Executor executor) {
    throw new UnsupportedOperationException("Not implemented");
  }
}
