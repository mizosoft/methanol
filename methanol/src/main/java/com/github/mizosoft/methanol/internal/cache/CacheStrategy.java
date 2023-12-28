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

package com.github.mizosoft.methanol.internal.cache;

import com.github.mizosoft.methanol.CacheControl;
import com.github.mizosoft.methanol.MutableRequest;
import com.github.mizosoft.methanol.ResponseBuilder;
import com.github.mizosoft.methanol.TrackedResponse;
import com.github.mizosoft.methanol.internal.util.Compare;

import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.github.mizosoft.methanol.internal.cache.HttpDates.toHttpDateString;
import static com.github.mizosoft.methanol.internal.cache.HttpDates.toUtcDateTime;

/**
 * A strategy for determining whether a stored response is fresh enough for the cache to serve
 * without contacting the origin, based on the caching rules imposed by the server & client.
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
class CacheStrategy {
  private static final Duration ONE_DAY = Duration.ofDays(1);

  private final CacheControl requestCacheControl;
  private final CacheControl responseCacheControl;
  private final Duration age;
  private final Duration freshness;
  private final Duration staleness;
  private final Optional<LocalDateTime> lastModified;
  private final Optional<String> etag;
  private final boolean usesHeuristicFreshness;

  CacheStrategy(Builder builder, Instant now) {
    requestCacheControl = builder.requestCacheControl;
    responseCacheControl = builder.responseCacheControl;
    age = builder.computeAge(now);
    freshness = builder.computeFreshnessLifetime().minus(age);
    staleness = freshness.negated();
    usesHeuristicFreshness = builder.usesHeuristicFreshness();
    lastModified = builder.lastModified;
    etag = builder.cacheResponseHeaders.firstValue("ETag");
  }

  boolean canServeCacheResponse(StalenessRule stalenessRule) {
    if (requestCacheControl.noCache() || responseCacheControl.noCache()) {
      return false;
    }

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
    if (!freshness.isNegative() && !freshness.isZero()) {
      return requestCacheControl.minFresh().isEmpty()
          || freshness.compareTo(requestCacheControl.minFresh().get()) >= 0;
    }
    return !responseCacheControl.mustRevalidate()
        && stalenessRule.permits(staleness, requestCacheControl, responseCacheControl);
  }

  void addCacheHeaders(ResponseBuilder<?> builder) {
    builder.setHeader("Age", Long.toString(age.toSeconds()));
    if (freshness.isNegative()) {
      builder.header("Warning", "110 - \"Response is Stale\"");
    }
    if (usesHeuristicFreshness && age.compareTo(ONE_DAY) > 0) {
      builder.header("Warning", "113 - \"Heuristic Expiration\"");
    }
  }

  HttpRequest conditionalize(HttpRequest request) {
    var conditionalizedRequest = MutableRequest.copyOf(request);
    etag.ifPresent(etag -> conditionalizedRequest.setHeader("If-None-Match", etag));
    lastModified.ifPresent(
        lastModified ->
            conditionalizedRequest.setHeader("If-Modified-Since", toHttpDateString(lastModified)));
    return conditionalizedRequest.toImmutableRequest();
  }

  static Builder newBuilder(HttpRequest request, TrackedResponse<?> cacheResponse) {
    return new Builder(request, cacheResponse);
  }

  static final class Builder {
    final Instant timeRequestSent;
    final Instant timeResponseReceived;
    final HttpHeaders cacheResponseHeaders;
    final CacheControl requestCacheControl;
    final CacheControl responseCacheControl;
    final LocalDateTime date;
    final Optional<Duration> maxAge;
    final Optional<LocalDateTime> expires;
    final Optional<LocalDateTime> lastModified;

    Builder(HttpRequest request, TrackedResponse<?> cacheResponse) {
      timeRequestSent = cacheResponse.timeRequestSent();
      timeResponseReceived = cacheResponse.timeResponseReceived();
      cacheResponseHeaders = cacheResponse.headers();
      requestCacheControl = CacheControl.parse(request.headers());
      responseCacheControl = CacheControl.parse(cacheResponse.headers());
      maxAge = requestCacheControl.maxAge().or(responseCacheControl::maxAge);
      expires = cacheResponse.headers().firstValue("Expires").map(HttpDates::toHttpDate);
      lastModified = cacheResponse.headers().firstValue("Last-Modified").map(HttpDates::toHttpDate);

      // As per rfc7231 Section 7.1.1.2, we must use the time the response was received as the value
      // of on absent Date field.
      date =
          cacheResponse
              .headers()
              .firstValue("Date")
              .map(HttpDates::toHttpDate)
              .orElseGet(() -> toUtcDateTime(timeResponseReceived));
    }

    /** Computes response's age relative to {@code now} as specified by rfc7324 Section 4.2.3. */
    Duration computeAge(Instant now) {
      var age =
          cacheResponseHeaders
              .firstValue("Age")
              .map(HttpDates::toDeltaSecondsOrNull)
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

    CacheStrategy build(Instant now) {
      return new CacheStrategy(this, now);
    }
  }

  /**
   * A rule for accepting stale responses upto a maximum staleness.
   */
  enum StalenessRule {
    MAX_STALE {
      @Override
      Optional<Duration> maxStale(
          CacheControl requestCacheControl, CacheControl responseCacheControl) {
        // max-stale is only applicable to requests.
        return requestCacheControl.anyMaxStale()
            ? Optional.of(Duration.ofSeconds(Long.MAX_VALUE)) // Accept any staleness.
            : requestCacheControl.maxStale();
      }
    },

    STALE_WHILE_REVALIDATE {
      @Override
      Optional<Duration> maxStale(
          CacheControl requestCacheControl, CacheControl responseCacheControl) {
        // stale-while-revalidate is only applicable to responses.
        return responseCacheControl.staleWhileRevalidate();
      }
    },

    STALE_IF_ERROR {
      @Override
      Optional<Duration> maxStale(
          CacheControl requestCacheControl, CacheControl responseCacheControl) {
        // stale-if-error is applicable to requests and responses, but the former overrides the
        // latter.
        return requestCacheControl.staleIfError().or(responseCacheControl::staleIfError);
      }
    };

    abstract Optional<Duration> maxStale(
        CacheControl requestCacheControl, CacheControl responseCacheControl);

    boolean permits(
        Duration staleness, CacheControl requestCacheControl, CacheControl responseCacheControl) {
      return maxStale(requestCacheControl, responseCacheControl)
          .map(maxStaleness -> staleness.compareTo(maxStaleness) <= 0)
          .orElse(false);
    }
  }
}
