package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.internal.cache.DateUtils.max;
import static com.github.mizosoft.methanol.internal.cache.DateUtils.toUtcDateTime;
import static java.time.ZoneOffset.UTC;

import com.github.mizosoft.methanol.TrackedResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

/** Policy for computing freshness and age values as defined by RFC 7234 4.2. */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
final class FreshnessPolicy {
  private final Optional<Duration> maxAge;
  private final Instant timeRequestSent;
  private final Instant timeResponseReceived;
  private final Duration age;

  /**
   * The date when the cached response was generated as identified by the {@code Date} header. If
   * the header is not present, the time the response was received is used instead (rfc7231
   * 7.1.1.2).
   */
  private final LocalDateTime date;

  /**
   * Either the value of the {@code Last-Modified} header or {@link #date} if the header is not
   * present (rfc7232 3.3).
   */
  private final LocalDateTime lastModified;

  /**
   * Response expiration date as specified by {@code Expires} header. {@link #maxAge} has a higher
   * precedence in calculating freshness lifetime.
   */
  private final Optional<LocalDateTime> expires;

  FreshnessPolicy(Optional<Duration> maxAge, TrackedResponse<?> response) {
    this.timeRequestSent = response.timeRequestSent();
    this.timeResponseReceived = response.timeResponseReceived();
    this.maxAge = maxAge;

    var dateServed = response.headers().firstValue("Date").map(DateUtils::toHttpDate);
    date = dateServed.orElseGet(() -> toUtcDateTime(timeResponseReceived));
    lastModified =
        response.headers().firstValue("Last-Modified").map(DateUtils::toHttpDate).orElse(date);
    expires = response.headers().firstValue("Expires").map(DateUtils::toHttpDate);
    age =
        response
            .headers()
            .firstValue("Age")
            .map(DateUtils::toDeltaSecondsOrNull)
            .orElse(Duration.ZERO);
  }

  LocalDateTime lastModified() {
    return lastModified;
  }

  /** Computes response freshness lifetime as defined by rfc7324 4.2.1. */
  Optional<Duration> computeFreshnessLifetime() {
    return maxAge.or(() -> expires.map(expires -> Duration.between(date, expires)));
  }

  /**
   * Returns a heuristic freshness lifetime to be used in case there's no explicit expiration.
   */
  Duration computeHeuristicLifetime() {
    // Use 10% of the time the response hasn't been modified
    // as encouraged by rfc7234 4.2.2 and implemented by browsers
    return Duration.between(lastModified, date).dividedBy(10);
  }

  boolean usesHeuristics() {
    return maxAge.isEmpty() && expires.isEmpty();
  }

  /** Computes response's age relative to {@code now} as defined by rfc7324 4.2.3. */
  Duration computeAge(Instant now) {
    var apparentAge =
        max(Duration.between(date.toInstant(UTC), timeResponseReceived), Duration.ZERO);
    var responseDelay = Duration.between(timeRequestSent, timeResponseReceived);
    var correctedAge = age.plus(responseDelay);
    var correctedInitialAge = max(apparentAge, correctedAge);
    var residentTime = Duration.between(timeResponseReceived, now);
    return correctedInitialAge.plus(residentTime);
  }
}
