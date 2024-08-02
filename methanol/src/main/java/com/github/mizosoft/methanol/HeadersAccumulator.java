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

package com.github.mizosoft.methanol;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.net.http.HttpHeaders;
import java.util.function.BiPredicate;

/** An accumulator of header name-value pairs. */
interface HeadersAccumulator {

  /**
   * Adds the given header name-value pair.
   *
   * @throws IllegalArgumentException if the given header is invalid
   */
  @CanIgnoreReturnValue
  HeadersAccumulator header(String name, String value);

  /**
   * Adds each of the given header name-value pairs. The pairs must be appended to each other in the
   * given array, where each name is followed by a corresponding value.
   *
   * @throws IllegalArgumentException if any of the given headers is invalid, or the given array has
   *     an uneven or non-positive length
   */
  @CanIgnoreReturnValue
  HeadersAccumulator headers(String... headers);

  /** Adds all the given headers. */
  @CanIgnoreReturnValue
  HeadersAccumulator headers(HttpHeaders headers);

  /**
   * Sets the header represented by the given name to the given value, overwriting the previous
   * value (if any).
   *
   * @throws IllegalArgumentException if the given header is invalid
   */
  @CanIgnoreReturnValue
  HeadersAccumulator setHeader(String name, String value);

  /** Removes all the headers added so far. */
  @CanIgnoreReturnValue
  HeadersAccumulator removeHeaders();

  /** Removes all the header values associated with the given name. */
  @CanIgnoreReturnValue
  HeadersAccumulator removeHeader(String name);

  /** Removes all the header name-value pairs matched by the given predicate. */
  @CanIgnoreReturnValue
  HeadersAccumulator removeHeadersIf(BiPredicate<String, String> filter);
}
