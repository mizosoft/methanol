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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CacheControlTest {
  @Test
  void parseDirectives() {
    var allDirectives =
        "max-age=1, min-fresh=2, s-maxage=3, max-stale=4, no-cache, no-store"
            + ", no-transform, public, private, only-if-cached, must-revalidate, proxy-revalidate";
    var cacheControl = CacheControl.parse(allDirectives);
    assertEquals(1, cacheControl.maxAgeSeconds());
    assertEquals(2, cacheControl.minFreshSeconds());
    assertEquals(3, cacheControl.sMaxAgeSeconds());
    assertEquals(4, cacheControl.maxStaleSeconds());
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
    assertEquals(1, cacheControl.maxAgeSeconds());
    assertTrue(cacheControl.isPublic());
    assertEquals(2, cacheControl.minFreshSeconds());
    assertTrue(cacheControl.noTransform());
  }

  @Test
  void defaultValues() {
    var cacheControl = CacheControl.parse("my-directive");
    assertEquals(-1, cacheControl.maxAgeSeconds());
    assertEquals(-1, cacheControl.minFreshSeconds());
    assertEquals(-1, cacheControl.sMaxAgeSeconds());
    assertEquals(-1, cacheControl.maxStaleSeconds());
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
    assertEquals(Map.of("my-directive", ""), cacheControl.directives());
  }

  @Test
  void directivesWithFieldNames() {
    var value =
        "no-cache=\"Language\", no-store=Content-Encoding"
            + ", private=\"Authorization, X-My-Private-Header\"";
    var cacheControl = CacheControl.parse(value);
    assertEquals(Set.of("language"), cacheControl.noCacheFields());
    assertEquals(Set.of("content-encoding"), cacheControl.noStoreFields());
    assertEquals(Set.of("authorization", "x-my-private-header"), cacheControl.privateFields());
  }

  @Test
  void maxStaleNoArgument() {
    var cacheControl = CacheControl.parse("max-stale");
    assertEquals(-1, cacheControl.maxAgeSeconds());
    assertTrue(cacheControl.anyMaxStale());
  }

  @Test
  void multipleValuesReplaceEachOther() {
    var cacheControl = CacheControl.parse("max-age=1, max-age=2");
    assertEquals(2, cacheControl.maxAgeSeconds());
  }

  @Test
  void builder() {
    assertEquals(CacheControl.empty(), CacheControl.newBuilder().build());

    var cacheControl = CacheControl.newBuilder()
        .directive("my-directive")
        .directive("my-directive-with-argument", "123")
        .maxAge(Duration.ofSeconds(1))
        .minFresh(Duration.ofSeconds(2))
        .maxStale(Duration.ofSeconds(3))
        .noCache()
        .noStore()
        .noTransform()
        .onlyIfCached()
        .build();
    var headerValue = "my-directive, my-directive-with-argument=123, max-age=1, min-fresh=2,"
        + " max-stale=3, no-cache, no-store, no-transform, only-if-cached";
    var parsed = CacheControl.parse(headerValue);
    assertEquals(parsed, cacheControl);
    assertEquals(headerValue, cacheControl.toString());
  }

  @Test
  void durationWithNanoPart() {
    var cacheControl = CacheControl.newBuilder()
        .maxAge(Duration.ofSeconds(1).plusNanos(1))
        .build();
    assertEquals(Optional.of(Duration.ofSeconds(1)), cacheControl.maxAge());
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
    assertThrows(IllegalArgumentException.class, () -> CacheControl.parse("no-c@che"));
    assertThrows(IllegalArgumentException.class, () -> CacheControl.parse(List.of("no-c@che")));
    assertThrows(IllegalArgumentException.class, () -> CacheControl.parse("max-age")); // no required argument
    assertThrows(IllegalArgumentException.class, () -> CacheControl.parse("max-age=-1")); // negative delta seconds
    assertThrows(IllegalArgumentException.class, () -> CacheControl.parse("no-cache=\"illeg@l\""));
    var iae = assertThrows(IllegalArgumentException.class, () -> CacheControl.parse("max-age=one"));
    assertNotNull(iae.getCause());
    assertThrows(NumberFormatException.class, () -> { throw iae.getCause(); });
  }

  @Test
  void buildInvalid() {
    var builder = CacheControl.newBuilder();
    assertThrows(IllegalArgumentException.class, () -> builder.directive("illeg@l"));
    assertThrows(IllegalArgumentException.class, () -> builder.directive("legal", "ba\r")); // Illegal value
    assertThrows(IllegalArgumentException.class, () -> builder.maxAge(Duration.ofSeconds(0)));
    assertThrows(IllegalArgumentException.class, () -> builder.minFresh(Duration.ofSeconds(0)));
    assertThrows(IllegalArgumentException.class, () -> builder.maxStale(Duration.ofSeconds(0)));
  }
}
