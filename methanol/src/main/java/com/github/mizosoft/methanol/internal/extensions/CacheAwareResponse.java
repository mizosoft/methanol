package com.github.mizosoft.methanol.internal.extensions;

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
     * Either the cache lacked a matching response or the selected response was stale but failed
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
