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

package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.internal.cache.HttpDates.formatHttpDate;
import static com.github.mizosoft.methanol.internal.cache.HttpDates.toUtcDateTime;
import static com.github.mizosoft.methanol.internal.cache.HttpDates.tryParseHttpDate;

import com.github.mizosoft.methanol.CacheControl;
import com.github.mizosoft.methanol.MutableRequest;
import com.github.mizosoft.methanol.ResponseBuilder;
import com.github.mizosoft.methanol.internal.util.Compare;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * A strategy for determining whether a stored response is fresh enough for the cache to serve
 * without contacting the origin, based on the caching rules imposed by the server and client.
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public final class CacheStrategy {
  private static final Duration ONE_DAY = Duration.ofDays(1);

  /** The age of the cached response. */
  private final Duration age;

  /** How much time the response stays fresh from when this strategy has been computed. */
  private final Duration freshness;

  /** How much time the response has been stale since this strategy had been computed. */
  private final Duration staleness;

  private final Optional<LocalDateTime> lastModified;
  private final Optional<String> etag;
  private final boolean usesHeuristicFreshness;

  private enum Servability {
    NONE,
    FRESH,
    STALE,
    STALE_WHILE_REVALIDATING,
    STALE_ON_ERROR
  }

  private final Servability servability;

  private CacheStrategy(Factory factory, Instant now) {
    var requestCacheControl = factory.requestCacheControl;
    var responseCacheControl = factory.responseCacheControl;
    age = factory.computeAge(now);
    usesHeuristicFreshness = factory.usesHeuristicFreshness();
    lastModified = factory.lastModified;
    etag = factory.cacheResponseHeaders.firstValue("ETag");
    freshness = factory.computeFreshnessLifetime().minus(age);
    staleness = freshness.negated();
    if (requestCacheControl.noCache() || responseCacheControl.noCache()) {
      servability = Servability.NONE;
    } else if (!freshness.isNegative() && !freshness.isZero()) {
      // From rfc7234 Section 4.2:
      // ---
      //   The calculation to determine if a response is fresh is:
      //
      //   response_is_fresh = (freshness_lifetime > current_age)
      // ---
      //
      // So:
      //   response_is_fresh = ((freshness = freshness_lifetime - current_age) > 0)
      //
      // If the request has a Cache-Control: min-fresh=x, we have:
      //   response_is_fresh = ((freshness = freshness_lifetime - current_age) >= x)
      servability =
          requestCacheControl.minFresh().isEmpty()
                  || freshness.compareTo(requestCacheControl.minFresh().get()) >= 0
              ? Servability.FRESH
              : Servability.NONE;
    } else if (responseCacheControl.mustRevalidate()) {
      servability = Servability.NONE;
    } else if (requestCacheControl.maxStale().isPresent() || requestCacheControl.anyMaxStale()) {
      // max-stale is only applicable to requests.
      servability =
          requestCacheControl
                      .maxStale()
                      .map(maxStale -> staleness.compareTo(maxStale) <= 0)
                      .orElse(false)
                  || requestCacheControl.anyMaxStale()
              ? Servability.STALE
              : Servability.NONE;
    } else if (responseCacheControl.staleWhileRevalidate().isPresent()) {
      // stale-while-revalidate is only applicable to responses.
      servability =
          responseCacheControl
                  .staleWhileRevalidate()
                  .map(staleWhileRevalidate -> staleness.compareTo(staleWhileRevalidate) <= 0)
                  .orElse(false)
              ? Servability.STALE_WHILE_REVALIDATING
              : Servability.NONE;
    } else if (requestCacheControl.staleIfError().isPresent()
        || responseCacheControl.staleIfError().isPresent()) {
      // stale-while-revalidate is only applicable to responses.
      servability =
          requestCacheControl
                  .staleIfError()
                  .or(responseCacheControl::staleIfError)
                  .map(staleIfError -> staleness.compareTo(staleIfError) <= 0)
                  .orElse(false)
              ? Servability.STALE_ON_ERROR
              : Servability.NONE;
    } else {
      servability = Servability.NONE;
    }
  }

  public boolean isCacheResponseServable() {
    switch (servability) {
      case FRESH:
      case STALE:
      case STALE_WHILE_REVALIDATING:
        return true;
      default:
        return false;
    }
  }

  public boolean isCacheResponseServableOnError() {
    return servability == Servability.STALE_ON_ERROR;
  }

  public boolean requiresBackgroundRevalidation() {
    return servability == Servability.STALE_WHILE_REVALIDATING;
  }

  public void addCacheHeaders(ResponseBuilder<?> builder) {
    builder.setHeader("Age", Long.toString(age.toSeconds()));
    if (freshness.isNegative()) {
      builder.header("Warning", "110 - \"Response is Stale\"");
    }
    if (usesHeuristicFreshness && age.compareTo(ONE_DAY) > 0) {
      builder.header("Warning", "113 - \"Heuristic Expiration\"");
    }
  }

  public HttpRequest conditionalize(HttpRequest request) {
    return etag.isEmpty() && lastModified.isEmpty()
        ? request
        : MutableRequest.copyOf(request)
            .apply(
                conditionalRequest -> {
                  etag.ifPresent(etag -> conditionalRequest.setHeader("If-None-Match", etag));
                  lastModified.ifPresent(
                      lastModified ->
                          conditionalRequest.setHeader(
                              "If-Modified-Since", formatHttpDate(lastModified)));
                })
            .toImmutableRequest();
  }

  static CacheStrategy create(
      CacheControl requestCacheControl, CacheResponse cacheResponse, Instant now) {
    return new Factory(requestCacheControl, cacheResponse).create(now);
  }

  private static final class Factory {
    final CacheControl requestCacheControl;
    final CacheControl responseCacheControl;
    final Instant timeRequestSent;
    final Instant timeResponseReceived;
    final HttpHeaders cacheResponseHeaders;
    final LocalDateTime date;
    final Optional<Duration> maxAge;
    final Optional<LocalDateTime> expires;
    final Optional<LocalDateTime> lastModified;

    Factory(CacheControl requestCacheControl, CacheResponse cacheResponse) {
      this.requestCacheControl = requestCacheControl;
      this.responseCacheControl = CacheControl.parse(cacheResponse.get().headers());
      this.timeRequestSent = cacheResponse.get().timeRequestSent();
      this.timeResponseReceived = cacheResponse.get().timeResponseReceived();
      this.cacheResponseHeaders = cacheResponse.get().headers();
      this.maxAge = requestCacheControl.maxAge().or(responseCacheControl::maxAge);
      this.lastModified =
          cacheResponse
              .get()
              .headers()
              .firstValue("Last-Modified")
              .flatMap(HttpDates::tryParseHttpDate);

      // As per rfc7231 Section 7.1.1.2, we must use the time the response was received as the value
      // of on absent Date field.
      this.date =
          cacheResponse
              .get()
              .headers()
              .firstValue("Date")
              .flatMap(HttpDates::tryParseHttpDate)
              .orElseGet(() -> toUtcDateTime(timeResponseReceived));

      // As per rfc7234 Section 5.3:
      // ---
      //   A cache recipient MUST interpret invalid date formats, especially the
      //   value "0", as representing a time in the past (i.e., "already
      //   expired").
      // ---
      //
      // So if Expires is invalid, we fall back to the furthest time in the past LocalDateTime can
      // represent. This has the advantage over approaches like falling back to an arbitrary amount
      // of time before the response's date, say a minute, in that it won't accidentally pass even
      // if the user passes in a 'max-stale=60', still satisfying the server's assumed intentions.
      this.expires =
          cacheResponse
              .get()
              .headers()
              .firstValue("Expires")
              .map(expiresValues -> tryParseHttpDate(expiresValues).orElse(LocalDateTime.MIN));
    }

    /** Computes response's age relative to {@code now} as specified by rfc7324 Section 4.2.3. */
    Duration computeAge(Instant now) {
      var age =
          cacheResponseHeaders
              .firstValue("Age")
              .flatMap(HttpDates::tryParseDeltaSeconds)
              .orElse(Duration.ZERO);
      var apparentAge =
          Compare.max(
              Duration.between(date.toInstant(ZoneOffset.UTC), timeResponseReceived),
              Duration.ZERO);
      var responseDelay = Duration.between(timeRequestSent, timeResponseReceived);
      var correctedAge = age.plus(responseDelay);
      var correctedInitialAge = Compare.max(apparentAge, correctedAge);
      var residentTime = Duration.between(timeResponseReceived, now);
      return correctedInitialAge.plus(residentTime);
    }

    /** Computes response's freshness lifetime as specified by rfc7324 Section 4.2.1. */
    Duration computeFreshnessLifetime() {
      return maxAge
          .or(
              () ->
                  expires.map(
                      expires -> Compare.max(Duration.between(date, expires), Duration.ZERO)))
          .orElseGet(this::computeHeuristicFreshnessLifetime);
    }

    Duration computeHeuristicFreshnessLifetime() {
      // As encouraged by rfc7234 Section 4.2.2 & implemented by browsers, use a heuristic of 10% of
      // the time the response hasn't been modified. If the server doesn't specify Last-Modified,
      // fallback to 0 freshness lifetime, effectively making the response stale.
      return lastModified
          .map(lastModified -> Compare.max(Duration.between(lastModified, date), Duration.ZERO))
          .orElse(Duration.ZERO)
          .dividedBy(10);
    }

    boolean usesHeuristicFreshness() {
      return maxAge.isEmpty() && expires.isEmpty();
    }

    CacheStrategy create(Instant now) {
      return new CacheStrategy(this, now);
    }
  }
}
