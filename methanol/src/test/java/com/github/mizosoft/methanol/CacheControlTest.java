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

import static java.time.Duration.ofSeconds;
import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CacheControlTest {
  @Test
  void parseDirectives() {
    var allDirectives =
        "max-age=1, min-fresh=2, s-maxage=3, max-stale=4, stale-while-revalidate=5, stale-if-error=6,"
            + " no-cache, no-store, no-transform, public, private, only-if-cached, must-revalidate, proxy-revalidate";
    var cacheControl = CacheControl.parse(allDirectives);
    assertEquals(Optional.of(ofSeconds(1)), cacheControl.maxAge());
    assertEquals(Optional.of(ofSeconds(2)), cacheControl.minFresh());
    assertEquals(Optional.of(ofSeconds(3)), cacheControl.sMaxAge());
    assertEquals(Optional.of(ofSeconds(4)), cacheControl.maxStale());
    assertEquals(Optional.of(ofSeconds(5)), cacheControl.staleWhileRevalidate());
    assertEquals(Optional.of(ofSeconds(6)), cacheControl.staleIfError());
    assertFalse(cacheControl.anyMaxStale());
    assertTrue(cacheControl.noCache());
    assertTrue(cacheControl.noStore());
    assertTrue(cacheControl.noTransform());
    assertTrue(cacheControl.isPublic());
    assertTrue(cacheControl.isPrivate());
    assertTrue(cacheControl.onlyIfCached());
    assertTrue(cacheControl.mustRevalidate());
    assertTrue(cacheControl.proxyRevalidate());
    assertEquals(
        Map.ofEntries(
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
            entry("proxy-revalidate", "")),
        cacheControl.directives());
    assertEquals(allDirectives, cacheControl.toString());
  }

  @Test
  void parseMultipleValues() {
    var value1 = "max-age=1, public";
    var value2 = "min-fresh=2, no-transform";
    var cacheControl = CacheControl.parse(List.of(value1, value2));
    assertEquals(Optional.of(ofSeconds(1)), cacheControl.maxAge());
    assertTrue(cacheControl.isPublic());
    assertEquals(Optional.of(ofSeconds(2)), cacheControl.minFresh());
    assertTrue(cacheControl.noTransform());
  }

  @Test
  void defaultValues() {
    var cacheControl = CacheControl.parse("my-directive");
    assertHasNoKnownDirectives(cacheControl);
    assertEquals(Map.of("my-directive", ""), cacheControl.directives());
  }

  @Test
  void empty() {
    var cacheControl = CacheControl.empty();
    assertHasNoKnownDirectives(cacheControl);
    assertTrue(cacheControl.directives().isEmpty(), cacheControl.directives()::toString);
  }

  @Test
  void parseEmptyValues() {
    var cacheControl = CacheControl.parse(List.of());
    assertHasNoKnownDirectives(cacheControl);
    assertTrue(cacheControl.directives().isEmpty(), cacheControl.directives()::toString);
  }

  @Test
  void directivesWithFieldNames() {
    var value =
        "no-cache=\"Language\", no-store=Content-Encoding, "
            + "private=\"Authorization, X-My-Private-Header\"";
    var cacheControl = CacheControl.parse(value);
    assertEquals(Set.of("language"), cacheControl.noCacheFields());
    assertEquals(Set.of("content-encoding"), cacheControl.noStoreFields());
    assertEquals(Set.of("authorization", "x-my-private-header"), cacheControl.privateFields());
  }

  @Test
  void maxStaleNoArgument() {
    var cacheControl = CacheControl.parse("max-stale");
    assertEquals(Optional.empty(), cacheControl.maxStale());
    assertTrue(cacheControl.anyMaxStale());
  }

  @Test
  void multipleValuesReplaceEachOther() {
    var cacheControl = CacheControl.parse("max-age=1, max-age=2");
    assertEquals(Optional.of(ofSeconds(2)), cacheControl.maxAge());
  }

  @Test
  void builder() {
    assertEquals(CacheControl.empty(), CacheControl.newBuilder().build());

    var cacheControl = CacheControl.newBuilder()
        .directive("my-directive")
        .directive("my-directive-with-argument", "123")
        .maxAge(ofSeconds(1))
        .minFresh(ofSeconds(2))
        .maxStale(ofSeconds(3))
        .staleIfError(ofSeconds(5))
        .noCache()
        .noStore()
        .noTransform()
        .onlyIfCached()
        .build();
    var headerValue = "my-directive, my-directive-with-argument=123, "
        + "max-age=1, min-fresh=2, max-stale=3, stale-if-error=5, "
        + "no-cache, no-store, no-transform, only-if-cached";
    var parsed = CacheControl.parse(headerValue);
    assertEquals(parsed, cacheControl);
    assertEquals(headerValue, cacheControl.toString());
  }

  @Test
  void durationIsTruncated() {
    var cacheControl = CacheControl.newBuilder()
        .maxAge(ofSeconds(1).plusNanos(1))
        .build();
    assertEquals(Optional.of(ofSeconds(1)), cacheControl.maxAge());
  }

  @Test
  void buildAnyMaxStale() {
    var cacheControl = CacheControl.newBuilder()
        .anyMaxStale()
        .build();
    assertTrue(cacheControl.anyMaxStale());
    assertTrue(cacheControl.maxStale().isEmpty());
  }

  @Test
  void equalsAndHashcode() {
    var cacheControl1 = CacheControl.parse("max-age=1, no-transform, max-stale=2");
    var cacheControl2 = CacheControl.parse("max-age=\"1\", max-stale=\"2\", no-transform");
    var cacheControl3 = CacheControl.parse("max-age=2, max-stale=3, no-transform");
    assertEquals(cacheControl1, cacheControl2);
    assertNotEquals(cacheControl2, cacheControl3);
    assertEquals(cacheControl2.hashCode(), cacheControl2.hashCode());
  }

  @Test
  void parseInvalid() {
    assertThrows(IllegalArgumentException.class, () -> CacheControl.parse(""));
    assertThrows(IllegalArgumentException.class, () -> CacheControl.parse("no-c@che"));
    assertThrows(IllegalArgumentException.class, () -> CacheControl.parse(List.of("no-c@che")));
    assertThrows(IllegalArgumentException.class, () -> CacheControl.parse("max-age")); // no required argument
    assertThrows(IllegalArgumentException.class, () -> CacheControl.parse("min-fresh")); // no required argument
    assertThrows(IllegalArgumentException.class, () -> CacheControl.parse("s-maxage")); // no required argument
    assertThrows(IllegalArgumentException.class, () -> CacheControl.parse("stale-while-revalidate")); // no required argument
    assertThrows(IllegalArgumentException.class, () -> CacheControl.parse("stale-if-error")); // no required argument
    assertThrows(IllegalArgumentException.class, () -> CacheControl.parse("max-age=-1")); // negative delta seconds
    assertThrows(IllegalArgumentException.class, () -> CacheControl.parse("no-cache=\"illeg@l\""));
    var iae = assertThrows(IllegalArgumentException.class, () -> CacheControl.parse("max-age=one"));
    assertNotNull(iae.getCause());
    assertThrows(NumberFormatException.class, () -> { throw iae.getCause(); });
  }

  @Test
  void buildInvalid() {
    var builder = CacheControl.newBuilder();
    assertThrows(IllegalArgumentException.class, () -> builder.directive(""));
    assertThrows(IllegalArgumentException.class, () -> builder.directive("illeg@l"));
    assertThrows(IllegalArgumentException.class, () -> builder.directive("legal", "ba\r")); // Illegal value
    assertThrows(IllegalArgumentException.class, () -> builder.maxAge(ofSeconds(-1)));
    assertThrows(IllegalArgumentException.class, () -> builder.minFresh(ofSeconds(-1)));
    assertThrows(IllegalArgumentException.class, () -> builder.maxStale(ofSeconds(-1)));
    assertThrows(IllegalArgumentException.class, () -> builder.staleIfError(ofSeconds(-1)));
  }

  private static void assertHasNoKnownDirectives(CacheControl cacheControl) {
    assertEquals(Optional.empty(), cacheControl.maxAge());
    assertEquals(Optional.empty(), cacheControl.minFresh());
    assertEquals(Optional.empty(), cacheControl.sMaxAge());
    assertEquals(Optional.empty(), cacheControl.maxStale());
    assertEquals(Optional.empty(), cacheControl.staleWhileRevalidate());
    assertEquals(Optional.empty(), cacheControl.staleIfError());
    assertFalse(cacheControl.anyMaxStale());
    assertFalse(cacheControl.noCache());
    assertFalse(cacheControl.noStore());
    assertFalse(cacheControl.noTransform());
    assertFalse(cacheControl.isPublic());
    assertFalse(cacheControl.isPrivate());
    assertFalse(cacheControl.onlyIfCached());
    assertFalse(cacheControl.mustRevalidate());
    assertFalse(cacheControl.proxyRevalidate());
    assertTrue(cacheControl.noCacheFields().isEmpty());
    assertTrue(cacheControl.noStoreFields().isEmpty());
    assertTrue(cacheControl.privateFields().isEmpty());
  }
}
