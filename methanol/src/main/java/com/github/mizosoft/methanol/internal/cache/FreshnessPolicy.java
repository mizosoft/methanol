package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.internal.cache.DateUtils.max;
import static com.github.mizosoft.methanol.internal.cache.DateUtils.toUtcDateTime;
import static java.time.ZoneOffset.UTC;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

/** Policy for computing freshness and age values as defined by RFC 7234 4.2. */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public final class FreshnessPolicy {

  /** Value of max-age directive as set by the response or overridden by the request. */
  private final Optional<Duration> maxAge;

  /** Time the request was sent. */
  private final Instant requestTime;

  /** Time the response was received. */
  private final Instant responseTime;

  /** Value of the Age header or zero if not set. */
  private final Duration age;

  /**
   * The date when the cached response was generated as identified by the {@code Date} header. If
   * the header is not present, the time the response was received is used instead (rfc7231
   * 7.1.1.2).
   */
  private final LocalDateTime date;

  /**
   * The time the response was last modified. This is either the value of the {@code Last-Modified}
   * header or {@link #date} if the header is not present (rfc7232 3.3).
   */
  private final LocalDateTime lastModified;

  /**
   * Response expiration date as specified by {@code Expires} header. This is only used in freshness
   * calculations if no {@code max-age} directive is present in either the request or the response.
   */
  private final Optional<LocalDateTime> expires;

  public FreshnessPolicy(
      Optional<Duration> maxAge,
      Instant requestTime,
      Instant responseTime,
      HttpHeaders responseHeaders) {
    this.maxAge = maxAge;
    this.requestTime = requestTime;
    this.responseTime = responseTime;
    var dateServed = responseHeaders.firstValue("Date").map(DateUtils::toHttpDate);
    date = dateServed.orElseGet(() -> toUtcDateTime(responseTime));
    lastModified =
        responseHeaders.firstValue("Last-Modified").map(DateUtils::toHttpDate).orElse(date);
    expires = responseHeaders.firstValue("Expires").map(DateUtils::toHttpDate);
    age =
        responseHeaders
            .firstValue("Age")
            .map(DateUtils::toDeltaSecondsDurationLenient)
            .orElse(Duration.ZERO);
  }

  /** Computes the effective value of {@code Last-Modified}. */
  public LocalDateTime computeEffectiveLastModified() {
    return lastModified;
  }

  /** Computes response's age relative to {@code now} as defined by rfc7324 4.2.3. */
  public Duration computeAge(Instant now) {
    var apparentAge = max(Duration.between(date.toInstant(UTC), responseTime), Duration.ZERO);
    var responseDelay = Duration.between(requestTime, responseTime);
    var correctedAge = age.plus(responseDelay);
    var correctedInitialAge = max(apparentAge, correctedAge);
    var residentTime = Duration.between(responseTime, now);
    return correctedInitialAge.plus(residentTime);
  }

  /** Computes response freshness lifetime as defined by rfc7324 4.2.1. */
  public Optional<Duration> computeFreshnessLifetime() {
    return maxAge.or(() -> expires.map(expires -> Duration.between(date, expires)));
  }

  /**
   * Returns a heuristic freshness lifetime to be used in case none is explicitly defined by the
   * server.
   */
  public Duration computeHeuristicLifetime() {
    // Use 10% of the time the response hasn't been modified
    // as encouraged by rfc7234 4.2.2 and implemented by browsers
    return Duration.between(lastModified, date).dividedBy(10);
  }

  public boolean hasExplicitExpiration() {
    return maxAge.isPresent() || expires.isPresent();
  }
}
