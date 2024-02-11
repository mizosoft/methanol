package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.internal.cache.HttpDates.parseDeltaSeconds;
import static com.github.mizosoft.methanol.internal.cache.HttpDates.toUtcDateTime;
import static com.github.mizosoft.methanol.internal.cache.HttpDates.tryParseHttpDate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatObject;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.LOCAL_DATE_TIME;

import com.github.mizosoft.methanol.testing.Logging;
import java.time.Duration;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class HttpDatesTest {
  static {
    Logging.disable(HttpDates.class);
  }

  @Test
  void validDates() {
    assertThat(tryParseHttpDate("Fri, 09 Sep 2022 23:03:14 GMT").orElse(null))
        .isEqualTo("2022-09-09T23:03:14");
    assertThat(tryParseHttpDate("Friday, 09-Sep-22 23:03:14 GMT").orElse(null))
        .isEqualTo("2022-09-09T23:03:14");
    assertThat(tryParseHttpDate("Fri Sep  9 23:03:14 2022").orElse(null))
        .isEqualTo("2022-09-09T23:03:14");
  }

  @Test
  void caseInsensitiveDate() {
    assertThat(tryParseHttpDate("fri, 09 sep 2022 23:03:14 gmt").orElse(null))
        .isEqualTo("2022-09-09T23:03:14");
  }

  @Test
  void dateWithOffset() {
    assertThat(tryParseHttpDate("Sat, 10 Sep 2022 01:03:14 +0200").orElse(null))
        .isEqualTo("2022-09-09T23:03:14");
  }

  @Test
  void dateWithZoneRegion() {
    assertThat(tryParseHttpDate("Sat, 10 Sep 2022 01:03:14 EGY").orElse(null)).isNull();
  }

  @Test
  void dateWithUtcZoneLiteral() {
    assertThat(tryParseHttpDate("Fri, 09 Sep 2022 23:03:14 UTC").orElse(null))
        .isEqualTo("2022-09-09T23:03:14");
  }

  @Test
  void dateWithGmtZoneLiteral() {
    assertThat(tryParseHttpDate("Fri, 09 Sep 2022 23:03:14 GMT").orElse(null))
        .isEqualTo("2022-09-09T23:03:14");
  }

  @Test
  void deltaSeconds() {
    assertThat(parseDeltaSeconds("1")).isEqualTo(Duration.ofSeconds(1));

    // Long.MAX_VALUE delta seconds are truncated to Integer.MAX_VALUE to avoid overflows.
    assertThat(parseDeltaSeconds(Long.toString(Long.MAX_VALUE)))
        .isEqualTo(Duration.ofSeconds(Integer.MAX_VALUE));

    assertThat(parseDeltaSeconds("0")).isEqualTo(Duration.ZERO);
    assertThatThrownBy(() -> parseDeltaSeconds("-1")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void utcDateFromInstant() {
    assertThatObject(tryParseHttpDate("Fri, 09 Sep 2022 23:03:14 GMT").orElse(null))
        .extracting(date -> toUtcDateTime(date.toInstant(ZoneOffset.UTC)), LOCAL_DATE_TIME)
        .isEqualTo("2022-09-09T23:03:14");
  }

  @Test
  void httpDateString() {
    assertThatObject(tryParseHttpDate("Fri, 09 Sep 2022 23:03:14 GMT").orElse(null))
        .extracting(HttpDates::formatHttpDate)
        .isEqualTo("Fri, 09 Sep 2022 23:03:14 GMT");
  }
}
