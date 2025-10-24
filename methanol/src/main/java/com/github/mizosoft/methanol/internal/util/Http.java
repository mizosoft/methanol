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

package com.github.mizosoft.methanol.internal.util;

import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_GONE;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MULT_CHOICE;
import static java.net.HttpURLConnection.HTTP_NOT_AUTHORITATIVE;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_NOT_IMPLEMENTED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PARTIAL;
import static java.net.HttpURLConnection.HTTP_REQ_TOO_LONG;

public class Http {
  private Http() {}

  /**
   * Returns whether a response with the given code can be cached based on heuristic expiry in case
   * explicit expiry is absent. Based on rfc7231 Section 6.1.
   */
  public static boolean isHeuristicallyCacheable(int statusCode) {
    switch (statusCode) {
      case HTTP_OK:
      case HTTP_NOT_AUTHORITATIVE:
      case HTTP_NO_CONTENT:
      case HTTP_MULT_CHOICE:
      case HTTP_MOVED_PERM:
      case HTTP_NOT_FOUND:
      case HTTP_BAD_METHOD:
      case HTTP_GONE:
      case HTTP_REQ_TOO_LONG:
      case HTTP_NOT_IMPLEMENTED:
        return true;
      case HTTP_PARTIAL:
      // Although partial responses are heuristically cacheable, they're not supported by this
      // implementation.
      default:
        return false;
    }
  }

  /**
   * Returns whether the given request method is unsafe, and hence may invalidate cached entries.
   * Based on rfc7231 Section 4.2.1.
   */
  public static boolean isSafe(String method) {
    return method.equalsIgnoreCase("GET")
        || method.equalsIgnoreCase("HEAD")
        || method.equalsIgnoreCase("OPTIONS")
        || method.equalsIgnoreCase("TRACE");
  }

  /**
   * Returns whether the given request method is idempotent, and hence may be retried multiple
   * times. Based on rfc7231 Section 4.2.2.
   */
  public static boolean isIdempotent(String method) {
    return isSafe(method) || method.equalsIgnoreCase("PUT") || method.equalsIgnoreCase("DELETE");
  }
}
