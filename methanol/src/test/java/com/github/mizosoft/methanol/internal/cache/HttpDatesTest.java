package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.internal.cache.HttpDates.toDeltaSeconds;
import static com.github.mizosoft.methanol.internal.cache.HttpDates.toHttpDate;
import static com.github.mizosoft.methanol.internal.cache.HttpDates.toUtcDateTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatObject;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.LOCAL_DATE_TIME;

import com.github.mizosoft.methanol.testutils.Logging;
import java.time.Duration;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class HttpDatesTest {
  static {
    Logging.disable(HttpDates.class);
  }

  @Test
  void validDates() {
    assertThat(toHttpDate("Fri, 09 Sep 2022 23:03:14 GMT")).isEqualTo("2022-09-09T23:03:14");
    assertThat(toHttpDate("Friday, 09-Sep-22 23:03:14 GMT")).isEqualTo("2022-09-09T23:03:14");
    assertThat(toHttpDate("Fri Sep  9 23:03:14 2022")).isEqualTo("2022-09-09T23:03:14");
  }

  @Test
  void dateWithOffset() {
    assertThat(toHttpDate("Sat, 10 Sep 2022 01:03:14 +0200")).isEqualTo("2022-09-09T23:03:14");
  }

  @Test
  void dateWithZoneRegion() {
    assertThat(toHttpDate("Sat, 10 Sep 2022 01:03:14 EGY")).isNull();
  }

  @Test
  void dateWithUtcZoneLiteral() {
    assertThat(toHttpDate("Fri, 09 Sep 2022 23:03:14 UTC")).isEqualTo("2022-09-09T23:03:14");
  }

  @Test
  void test() {
    assertThat(toHttpDate("Fri, 09 Sep 2022 23:03:14 GMT")).isEqualTo("2022-09-09T23:03:14");
  }

  @Test
  void deltaSeconds() {
    assertThat(toDeltaSeconds("1")).isEqualTo(Duration.ofSeconds(1));

    // Long.MAX_VALUE delta seconds are truncated to Integer.MAX_VALUE to avoid overflows.
    assertThat(toDeltaSeconds(Long.toString(Long.MAX_VALUE)))
        .isEqualTo(Duration.ofSeconds(Integer.MAX_VALUE));

    assertThat(toDeltaSeconds("0")).isEqualTo(Duration.ZERO);
    assertThatThrownBy(() -> toDeltaSeconds("-1")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void utcDateFromInstant() {
    assertThatObject(toHttpDate("Fri, 09 Sep 2022 23:03:14 GMT"))
        .extracting(date -> toUtcDateTime(date.toInstant(ZoneOffset.UTC)), LOCAL_DATE_TIME)
        .isEqualTo("2022-09-09T23:03:14");
  }

  @Test
  void httpDateString() {
    assertThatObject(toHttpDate("Fri, 09 Sep 2022 23:03:14 GMT"))
        .extracting(HttpDates::toHttpDateString)
        .isEqualTo("Fri, 09 Sep 2022 23:03:14 GMT");
  }
}
