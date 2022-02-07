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
import java.time.temporal.TemporalQueries;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Static functions for handling HTTP dates. */
public class DateUtils {
  private static final Logger logger = System.getLogger(DateUtils.class.getName());

  private static final DateTimeFormatter PREFERRED_FORMATTER = DateTimeFormatter.RFC_1123_DATE_TIME;
  private static final List<DateTimeFormatter> FORMATTERS;

  // TODO add non-standard formats found in the wild?
  static {
    // Use formats specified by rfc7231 section 7.1.1.1
    FORMATTERS =
        List.of(
            // Add preferred format (tried first)
            PREFERRED_FORMATTER,
            // Add obsolete formats
            DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss 'GMT'"), // rfc850
            DateTimeFormatter.ofPattern("EEEE MMM pdd HH:mm:ss yyyy")); // C's asctime()
  }

  public static String formatHttpDate(LocalDateTime dateTime) {
    return PREFERRED_FORMATTER.format(dateTime.atOffset(ZoneOffset.UTC));
  }

  public static boolean isHttpDate(String value) {
    return toHttpDate0(value, false) != null;
  }

  static @Nullable LocalDateTime toHttpDate(String value) {
    return toHttpDate0(value, true);
  }

  static @Nullable LocalDateTime toHttpDate0(String value, boolean logFailure) {
    DateTimeException parseException = null; // Only recorded if logFailure is true
    for (var formatter : FORMATTERS) {
      try {
        // If the parsed temporal object has a zone, only accept it if
        // it's an offset with which an Instant can be calculated without
        // provider-specific normalization (in case of zone regions).
        var parsed = formatter.parse(value);
        var dateTime = LocalDateTime.from(parsed);
        var zone = parsed.query(TemporalQueries.zone());
        if (zone == null || zone instanceof ZoneOffset) {
          var offset = (ZoneOffset) zone;
          return offset == null || offset.equals(ZoneOffset.UTC)
              ? dateTime
              : toUtcDateTime(dateTime.toInstant(offset));
        } else if (logFailure) {
          throw new DateTimeException("unsupported zone: " + zone);
        }
      } catch (DateTimeException e) {
        if (logFailure) {
          if (parseException != null) {
            parseException.addSuppressed(e);
          } else {
            parseException = e;
          }
        }
      }
    }

    if (logFailure) {
      logger.log(Level.WARNING, () -> "couldn't parse HTTP date: " + value, parseException);
    }
    return null;
  }

  // TODO tolerate -ve values by truncating to 0?
  public static Duration toDeltaSeconds(String value) {
    long secondsLong = Long.parseLong(value);
    requireArgument(secondsLong >= 0, "delta seconds can't be negative");

    // Truncate to Integer.MAX_VALUE to avoid overflows (e.g. when calculating freshness)
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
