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

import java.util.Optional;

// TODO export this as public API?

/** A {@code TrackedResponse} that knows it may have been generated from an HTTP cache. */
public interface CacheAwareResponse<T> extends TrackedResponse<T> {

  /**
   * Returns an {@code Optional} for the response received as a result of using the network. An
   * empty optional is returned in case the response was entirely constructed from cache.
   */
  Optional<TrackedResponse<?>> networkResponse();

  /**
   * Returns an {@code Optional} for the response constructed from cache. An empty optional is
   * returned in case no response matching the initiating request was found in the cache.
   */
  Optional<TrackedResponse<?>> cacheResponse();

  /** Returns this response's {@link CacheStatus}. */
  CacheStatus cacheStatus();

  /** The status of an attempt to retrieve an HTTP response from cache. */
  enum CacheStatus {

    /**
     * Either the cache lacked a matching response or the matching response was stale but failed
     * validation with the server and thus had to be re-downloaded.
     */
    MISS,

    /** The response was entirely constructed from cache and no network was used. */
    HIT,

    /**
     * The response was constructed from cache but a conditional {@code GET} had to be made to
     * validate it with the server.
     */
    CONDITIONAL_HIT,

    /**
     * The response was generated locally to satisfy a request that prohibited network use despite
     * being necessary to serve a valid response. The resulted response is normally {@link
     * java.net.HttpURLConnection#HTTP_GATEWAY_TIMEOUT Gateway timeout}.
     */
    LOCALLY_GENERATED
  }
}
