/*
 * Copyright (c) 2019-2021 Moataz Abdelnasser
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

import java.net.http.HttpResponse;

/** Static functions for checking response status codes. */
public class HttpStatus {
  private HttpStatus() {}

  /** Returns {@code true} if {@code statusCode} is a 1xx informational status code. */
  public static boolean isInformational(int statusCode) {
    return StatusKind.INFORMATIONAL.includes(statusCode);
  }

  /** Returns {@code true} if {@code response.statusCode()} is a 1xx informational status code. */
  public static boolean isInformational(HttpResponse<?> response) {
    return isInformational(response.statusCode());
  }

  /** Returns {@code true} if {@code statusCode} is a 2xx success status code. */
  public static boolean isSuccessful(int statusCode) {
    return StatusKind.SUCCESSFUL.includes(statusCode);
  }

  /** Returns {@code true} if {@code response.statusCode()} is a 2xx successful status code. */
  public static boolean isSuccessful(HttpResponse<?> response) {
    return isSuccessful(response.statusCode());
  }

  /** Returns {@code true} if {@code statusCode} is a 3xx redirection status code. */
  public static boolean isRedirection(int statusCode) {
    return StatusKind.REDIRECTION.includes(statusCode);
  }

  /** Returns {@code true} if {@code response.statusCode()} is a 3xx redirection status code. */
  public static boolean isRedirection(HttpResponse<?> response) {
    return isRedirection(response.statusCode());
  }

  /** Returns {@code true} if {@code statusCode} is a 4xx client error status code. */
  public static boolean isClientError(int statusCode) {
    return StatusKind.CLIENT_ERROR.includes(statusCode);
  }

  /** Returns {@code true} if {@code response.statusCode()} is a 4xx client error status code. */
  public static boolean isClientError(HttpResponse<?> response) {
    return isClientError(response.statusCode());
  }

  /** Returns {@code true} if {@code statusCode} is a 5xx server error status code. */
  public static boolean isServerError(int statusCode) {
    return StatusKind.SERVER_ERROR.includes(statusCode);
  }

  /** Returns {@code true} if {@code response.statusCode()} is a 5xx server error status code. */
  public static boolean isServerError(HttpResponse<?> response) {
    return isServerError(response.statusCode());
  }

  enum StatusKind {
    INFORMATIONAL(100),
    SUCCESSFUL(200),
    REDIRECTION(300),
    CLIENT_ERROR(400),
    SERVER_ERROR(500);

    private final int from;
    private final int to;

    StatusKind(int from) {
      this(from, from + 99);
    }

    StatusKind(int from, int to) {
      this.from = from;
      this.to = to;
    }

    boolean includes(int statusCode) {
      return statusCode >= from && statusCode <= to;
    }
  }
}
