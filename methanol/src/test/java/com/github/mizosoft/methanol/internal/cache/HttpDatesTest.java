/*
 * Copyright (c) 2025 Moataz Hussein
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
import java.util.Locale;
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

  @Test
  void defaultLocalDoesNotAffectFormatting() {
    var currentDefault = Locale.getDefault(Locale.Category.FORMAT);
    Locale.setDefault(Locale.Category.FORMAT, Locale.FRANCE);
    try {
      assertThatObject(tryParseHttpDate("Fri, 09 Sep 2022 23:03:14 GMT").orElse(null))
          .extracting(HttpDates::formatHttpDate)
          .isEqualTo("Fri, 09 Sep 2022 23:03:14 GMT");

      assertThat(tryParseHttpDate("Fri, 09 Sep 2022 23:03:14 GMT").orElse(null))
          .isEqualTo("2022-09-09T23:03:14");
      assertThat(tryParseHttpDate("Friday, 09-Sep-22 23:03:14 GMT").orElse(null))
          .isEqualTo("2022-09-09T23:03:14");
      assertThat(tryParseHttpDate("Fri Sep  9 23:03:14 2022").orElse(null))
          .isEqualTo("2022-09-09T23:03:14");
    } finally {
      Locale.setDefault(Locale.Category.FORMAT, currentDefault);
    }
  }
}
