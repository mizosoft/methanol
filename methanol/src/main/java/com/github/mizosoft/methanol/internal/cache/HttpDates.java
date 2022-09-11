/*
 * Copyright (c) 2022 Moataz Abdelnasser
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

import static com.github.mizosoft.methanol.internal.Validate.requireArgument;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQueries;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Helpers for parsing/printing HTTP dates. */
public class HttpDates {
  private static final Logger logger = System.getLogger(HttpDates.class.getName());

  /** A formatter for the preferred format specified by rfc7231 7.1.1.1. */
  private static final DateTimeFormatter PREFERRED_FORMATTER;

  /** A list of formatters to try in sequence till one succeeds. */
  private static final List<DateTimeFormatter> FORMATTERS;

  static {
    PREFERRED_FORMATTER = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'");

    // These are the formats specified by rfc7231 section 7.1.1.1 for acceptable HTTP dates.
    FORMATTERS =
        List.of(
            // The preferred format is tried first.
            PREFERRED_FORMATTER,

            // Obsolete formats, but allowed by rfc7231.
            DateTimeFormatter.ofPattern("EEEE, dd-MMM-uu HH:mm:ss 'GMT'"), // rfc850
            DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss uuuu"), // C's asctime()

            // A lenient version of the preferred format, with the possibility of zone offsets.
            DateTimeFormatter.RFC_1123_DATE_TIME,

            // The preferred format but with UTC instead of GMT.
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'UTC'"));
  }

  private HttpDates() {}

  public static String toHttpDateString(LocalDateTime dateTime) {
    return PREFERRED_FORMATTER.format(dateTime);
  }

  public static boolean isHttpDate(String value) {
    return toHttpDate0(value, false) != null;
  }

  static @Nullable LocalDateTime toHttpDate(String value) {
    return toHttpDate0(value, true);
  }

  static @Nullable LocalDateTime toHttpDate0(String value, boolean logFailure) {
    TemporalAccessor parsedTemporal = null;
    for (var formatter : FORMATTERS) {
      try {
        parsedTemporal = formatter.parse(value);
        break;
      } catch (DateTimeException ignored) {
        // Try next formatter.
      }
    }

    DateTimeException illegalHttpDate = null;
    if (parsedTemporal != null) {
      try {
        var dateTime = LocalDateTime.from(parsedTemporal);
        var offset = parsedTemporal.query(TemporalQueries.offset());
        return (offset == null || offset.equals(ZoneOffset.UTC))
            ? dateTime
            : toUtcDateTime(dateTime.toInstant(offset));
      } catch (DateTimeException e) {
        illegalHttpDate = e;
      }
    }

    if (logFailure) {
      logger.log(Level.WARNING, () -> "malformed or illegal HTTP date: " + value, illegalHttpDate);
    }
    return null;
  }

  // TODO tolerate -ve values by truncating to 0?
  public static Duration toDeltaSeconds(String value) {
    long secondsLong = Long.parseLong(value);
    requireArgument(secondsLong >= 0, "delta seconds can't be negative");

    // Truncate to Integer.MAX_VALUE to avoid overflows (e.g. when calculating freshness).
    int secondsInt = (int) Math.min(secondsLong, Integer.MAX_VALUE);
    return Duration.ofSeconds(secondsInt);
  }

  static @Nullable Duration toDeltaSecondsOrNull(String value) {
    try {
      return toDeltaSeconds(value);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  static LocalDateTime toUtcDateTime(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  static Duration max(Duration left, Duration right) {
    return left.compareTo(right) >= 0 ? left : right;
  }
}
