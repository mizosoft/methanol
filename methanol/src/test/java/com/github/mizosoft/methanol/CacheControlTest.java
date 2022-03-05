/*
 * Copyright (c) 2019, 2020 Moataz Abdelnasser
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

package com.github.mizosoft.methanol;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CacheControlTest {
  @Test
  void parseDirectives() {
    var allDirectives =
        "max-age=1, min-fresh=2, s-maxage=3, max-stale=4, stale-while-revalidate=5, stale-if-error=6,"
            + " no-cache, no-store, no-transform, public, private, only-if-cached, must-revalidate, proxy-revalidate";
    var cacheControl = CacheControl.parse(allDirectives);
    assertThat(cacheControl.maxAge()).hasValue(Duration.ofSeconds(1));
    assertThat(cacheControl.minFresh()).hasValue(Duration.ofSeconds(2));
    assertThat(cacheControl.sMaxAge()).hasValue(Duration.ofSeconds(3));
    assertThat(cacheControl.maxStale()).hasValue(Duration.ofSeconds(4));
    assertThat(cacheControl.staleWhileRevalidate()).hasValue(Duration.ofSeconds(5));
    assertThat(cacheControl.staleIfError()).hasValue(Duration.ofSeconds(6));
    assertThat(cacheControl.anyMaxStale()).isFalse();
    assertThat(cacheControl.noCache()).isTrue();
    assertThat(cacheControl.noStore()).isTrue();
    assertThat(cacheControl.noTransform()).isTrue();
    assertThat(cacheControl.isPublic()).isTrue();
    assertThat(cacheControl.isPrivate()).isTrue();
    assertThat(cacheControl.onlyIfCached()).isTrue();
    assertThat(cacheControl.mustRevalidate()).isTrue();
    assertThat(cacheControl.proxyRevalidate()).isTrue();
    assertThat(cacheControl.directives())
        .containsOnly(
            entry("max-age", "1"),
            entry("min-fresh", "2"),
            entry("s-maxage", "3"),
            entry("max-stale", "4"),
            entry("stale-while-revalidate", "5"),
            entry("stale-if-error", "6"),
            entry("no-cache", ""),
            entry("no-store", ""),
            entry("no-transform", ""),
            entry("public", ""),
            entry("private", ""),
            entry("only-if-cached", ""),
            entry("must-revalidate", ""),
            entry("proxy-revalidate", ""));

    assertThat(cacheControl).hasToString(allDirectives);
  }

  @Test
  void parseMultipleValues() {
    var value1 = "max-age=1, public";
    var value2 = "min-fresh=2, no-transform";
    var cacheControl = CacheControl.parse(List.of(value1, value2));
    assertThat(cacheControl.maxAge()).hasValue(Duration.ofSeconds(1));
    assertThat(cacheControl.isPublic()).isTrue();
    assertThat(cacheControl.minFresh()).hasValue(Duration.ofSeconds(2));
    assertThat(cacheControl.noTransform()).isTrue();
  }

  @Test
  void parseUnknownDirective() {
    var cacheControl = CacheControl.parse("my-directive");
    assertHasNoKnownDirectives(cacheControl);
    assertThat(cacheControl.directives()).containsOnly(entry("my-directive", ""));
  }

  @Test
  void empty() {
    var cacheControl = CacheControl.empty();
    assertHasNoKnownDirectives(cacheControl);
    assertThat(cacheControl.directives()).isEmpty();
  }

  @Test
  void parseEmptyValues() {
    var cacheControl = CacheControl.parse(List.of());
    assertHasNoKnownDirectives(cacheControl);
    assertThat(cacheControl.directives()).isEmpty();
  }

  @Test
  void directivesWithFieldNames() {
    var cacheControl =
        CacheControl.parse(
            "no-cache=\"Language\", no-store=Content-Encoding, private=\"Authorization, X-My-Private-Header\"");
    assertThat(cacheControl.noCacheFields()).containsExactly("language");
    assertThat(cacheControl.noStoreFields()).containsExactly("content-encoding");
    assertThat(cacheControl.privateFields())
        .containsExactly("authorization", "x-my-private-header");
  }

  @Test
  void maxStaleNoArgument() {
    var cacheControl = CacheControl.parse("max-stale");
    assertThat(cacheControl.maxStale()).isEmpty();
    assertThat(cacheControl.anyMaxStale()).isTrue();
  }

  @Test
  void lastValueTakesPrecedence() {
    var cacheControl = CacheControl.parse("max-age=1, max-age=2");
    assertThat(cacheControl.maxAge()).hasValue(Duration.ofSeconds(2));
  }

  @Test
  void createWithBuilder() {
    assertThat(CacheControl.newBuilder().build()).isEqualTo(CacheControl.empty());

    var cacheControl =
        CacheControl.newBuilder()
            .directive("my-directive")
            .directive("my-directive-with-argument", "123")
            .maxAge(Duration.ofSeconds(1))
            .minFresh(Duration.ofSeconds(2))
            .maxStale(Duration.ofSeconds(3))
            .staleIfError(Duration.ofSeconds(4))
            .noCache()
            .noStore()
            .noTransform()
            .onlyIfCached()
            .build();
    assertThat(cacheControl.maxAge()).hasValue(Duration.ofSeconds(1));
    assertThat(cacheControl.minFresh()).hasValue(Duration.ofSeconds(2));
    assertThat(cacheControl.maxStale()).hasValue(Duration.ofSeconds(3));
    assertThat(cacheControl.staleIfError()).hasValue(Duration.ofSeconds(4));
    assertThat(cacheControl.noCache()).isTrue();
    assertThat(cacheControl.noStore()).isTrue();
    assertThat(cacheControl.noTransform()).isTrue();
    assertThat(cacheControl.onlyIfCached()).isTrue();

    var headerValue =
        "max-age=1, min-fresh=2, max-stale=3, stale-if-error=4, "
            + "no-cache, no-store, no-transform, only-if-cached, "
            + "my-directive, my-directive-with-argument=123";
    // Map.copyOf, which CacheControl uses to copy added non-standard directives, has a pretty
    // chaotic order which seems to change with different runs, so also check with non-standard
    // fields' order switched.
    assertThat(cacheControl)
        .satisfiesAnyOf(
            cc -> assertThat(cc).hasToString(headerValue),
            cc ->
                assertThat(cc)
                    .hasToString(
                        "max-age=1, min-fresh=2, max-stale=3, stale-if-error=4, "
                            + "no-cache, no-store, no-transform, only-if-cached, "
                            + "my-directive-with-argument=123, my-directive"));
    assertThat(cacheControl).isEqualTo(CacheControl.parse(headerValue));
  }

  @Test
  void durationIsTruncatedToSeconds() {
    var cacheControl = CacheControl.newBuilder().maxAge(Duration.ofSeconds(1).plusNanos(1)).build();
    assertThat(cacheControl.maxAge()).hasValue(Duration.ofSeconds(1));
    assertThat(cacheControl).hasToString("max-age=1");
  }

  @Test
  void tooLargeDuration() {
    var cacheControl = CacheControl.parse("max-age=" + (Integer.MAX_VALUE + 1L));
    assertThat(cacheControl.maxAge()).hasValue(Duration.ofSeconds(Integer.MAX_VALUE));

    // The toString value isn't changed
    assertThat(cacheControl).hasToString("max-age=" + (Integer.MAX_VALUE + 1L));
  }

  @Test
  void anyMaxStaleAfterMaxStale() {
    var cacheControl =
        CacheControl.newBuilder().maxStale(Duration.ofSeconds(1)).anyMaxStale().build();
    assertThat(cacheControl.maxStale()).isEmpty();
    assertThat(cacheControl).hasToString("max-stale");
  }

  @Test
  void maxStaleAfterAnyMaxStale() {
    var cacheControl =
        CacheControl.newBuilder().anyMaxStale().maxStale(Duration.ofSeconds(1)).build();
    assertThat(cacheControl.maxStale()).hasValue(Duration.ofSeconds(1));
    assertThat(cacheControl).hasToString("max-stale=1");
  }

  @Test
  void danglingDelimiters() {
    var cacheControl = CacheControl.parse(",, ,max-age=1, ,, min-fresh=2,, ,");
    assertThat(cacheControl.maxAge()).hasValue(Duration.ofSeconds(1));
    assertThat(cacheControl.minFresh()).hasValue(Duration.ofSeconds(2));
    assertThat(cacheControl).hasToString("max-age=1, min-fresh=2");
  }

  @Test
  void anyMaxStale() {
    var cacheControl = CacheControl.newBuilder().anyMaxStale().build();
    assertThat(cacheControl.anyMaxStale()).isTrue();
    assertThat(cacheControl.maxStale()).isEmpty();
  }

  @Test
  void equalsAndHashcode() {
    var cacheControl1 = CacheControl.parse("max-age=1");
    var cacheControl2 = CacheControl.parse("max-age=\"1\"");
    var cacheControl3 = CacheControl.parse("max-age=2");
    assertThat(cacheControl1).isEqualTo(cacheControl2);
    assertThat(cacheControl1).hasSameHashCodeAs(cacheControl2);
    assertThat(cacheControl1).isNotEqualTo(cacheControl3);
    assertThat(cacheControl1).doesNotHaveSameHashCodeAs(cacheControl3);
  }

  @Test
  void parseInvalid() {
    assertThatIllegalArgumentException().isThrownBy(() -> CacheControl.parse(""));
    assertThatIllegalArgumentException().isThrownBy(() -> CacheControl.parse("no-c@che"));
    assertThatIllegalArgumentException().isThrownBy(() -> CacheControl.parse(List.of("no-c@che")));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CacheControl.parse("max-age")); // No required argument
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CacheControl.parse("min-fresh")); // No required argument
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CacheControl.parse("s-maxage")); // No required argument
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CacheControl.parse("stale-while-revalidate")); // No required argument
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CacheControl.parse("stale-if-error")); // No required argument
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CacheControl.parse("max-age=-1")); // Negative delta seconds
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CacheControl.parse("no-cache=\"illeg@l\""));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CacheControl.parse("max-age=one"))
        .withCauseInstanceOf(NumberFormatException.class);
  }

  @Test
  void buildInvalid() {
    var builder = CacheControl.newBuilder();
    assertThatIllegalArgumentException().isThrownBy(() -> builder.directive(""));
    assertThatIllegalArgumentException().isThrownBy(() -> builder.directive("illeg@l"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> builder.directive("legal", "ba\r")); // Illegal value
    assertThatIllegalArgumentException().isThrownBy(() -> builder.maxAge(Duration.ofSeconds(-1)));
    assertThatIllegalArgumentException().isThrownBy(() -> builder.minFresh(Duration.ofSeconds(-1)));
    assertThatIllegalArgumentException().isThrownBy(() -> builder.maxStale(Duration.ofSeconds(-1)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> builder.staleIfError(Duration.ofSeconds(-1)));
  }

  private static void assertHasNoKnownDirectives(CacheControl cacheControl) {
    assertThat(cacheControl.maxAge()).isEmpty();
    assertThat(cacheControl.minFresh()).isEmpty();
    assertThat(cacheControl.sMaxAge()).isEmpty();
    assertThat(cacheControl.maxStale()).isEmpty();
    assertThat(cacheControl.staleWhileRevalidate()).isEmpty();
    assertThat(cacheControl.staleIfError()).isEmpty();
    assertThat(cacheControl.anyMaxStale()).isFalse();
    assertThat(cacheControl.noCache()).isFalse();
    assertThat(cacheControl.noStore()).isFalse();
    assertThat(cacheControl.noTransform()).isFalse();
    assertThat(cacheControl.isPublic()).isFalse();
    assertThat(cacheControl.isPrivate()).isFalse();
    assertThat(cacheControl.onlyIfCached()).isFalse();
    assertThat(cacheControl.mustRevalidate()).isFalse();
    assertThat(cacheControl.proxyRevalidate()).isFalse();
    assertThat(cacheControl.noCacheFields()).isEmpty();
    assertThat(cacheControl.noStoreFields()).isEmpty();
    assertThat(cacheControl.privateFields()).isEmpty();
  }
}
