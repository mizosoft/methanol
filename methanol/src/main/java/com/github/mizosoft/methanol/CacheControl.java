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

import static com.github.mizosoft.methanol.CacheControl.StandardDirective.MAX_AGE;
import static com.github.mizosoft.methanol.CacheControl.StandardDirective.MAX_STALE;
import static com.github.mizosoft.methanol.CacheControl.StandardDirective.MIN_FRESH;
import static com.github.mizosoft.methanol.CacheControl.StandardDirective.MUST_REVALIDATE;
import static com.github.mizosoft.methanol.CacheControl.StandardDirective.NO_CACHE;
import static com.github.mizosoft.methanol.CacheControl.StandardDirective.NO_STORE;
import static com.github.mizosoft.methanol.CacheControl.StandardDirective.NO_TRANSFORM;
import static com.github.mizosoft.methanol.CacheControl.StandardDirective.ONLY_IF_CACHED;
import static com.github.mizosoft.methanol.CacheControl.StandardDirective.PRIVATE;
import static com.github.mizosoft.methanol.CacheControl.StandardDirective.PROXY_REVALIDATE;
import static com.github.mizosoft.methanol.CacheControl.StandardDirective.PUBLIC;
import static com.github.mizosoft.methanol.CacheControl.StandardDirective.S_MAXAGE;
import static com.github.mizosoft.methanol.internal.Utils.escapeAndQuoteValueIfNeeded;
import static com.github.mizosoft.methanol.internal.Utils.normalizeToken;
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.text.HeaderValueTokenizer;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A group of HTTP cache directives as defined by the {@code Cache-Control} header. This class
 * provides type-safe accessors for the directives mentioned in <a
 * href="https://tools.ietf.org/html/rfc7234#section-5.2">RFC 7234 Section 5.2</a>. Other directives
 * ({@code Cache-Control} extensions) can be accessed using the {@link #directives()} map.
 */
public final class CacheControl {
  private static final int ABSENT_INT = -1;

  private final Map<String, String> directives;
  private final int maxAgeSeconds;
  private final int minFreshSeconds;
  private final int sMaxAgeSeconds;
  private final int maxStaleSeconds;
  private final boolean hasMaxStale;
  private final boolean noCache;
  private final boolean noStore;
  private final boolean noTransform;
  private final boolean isPublic;
  private final boolean isPrivate;
  private final boolean onlyIfCached;
  private final boolean mustRevalidate;
  private final boolean proxyRevalidate;
  private final Set<String> noCacheFields;
  private final Set<String> noStoreFields;
  private final Set<String> privateFields;

  private CacheControl(
      Map<String, String> directives, Map<StandardDirective, ?> standardDirectives) {
    this.directives = Collections.unmodifiableMap(directives);
    maxAgeSeconds = getIntIfPresent(standardDirectives, MAX_AGE);
    minFreshSeconds = getIntIfPresent(standardDirectives, MIN_FRESH);
    sMaxAgeSeconds = getIntIfPresent(standardDirectives, S_MAXAGE);
    maxStaleSeconds = getIntIfPresent(standardDirectives, MAX_STALE);
    hasMaxStale = standardDirectives.containsKey(MAX_STALE);
    noCache = standardDirectives.containsKey(NO_CACHE);
    noStore = standardDirectives.containsKey(NO_STORE);
    noTransform = standardDirectives.containsKey(NO_TRANSFORM);
    isPublic = standardDirectives.containsKey(PUBLIC);
    isPrivate = standardDirectives.containsKey(PRIVATE);
    onlyIfCached = standardDirectives.containsKey(ONLY_IF_CACHED);
    mustRevalidate = standardDirectives.containsKey(MUST_REVALIDATE);
    proxyRevalidate = standardDirectives.containsKey(PROXY_REVALIDATE);
    noCacheFields = getFieldSetIfPresent(standardDirectives, NO_CACHE);
    noStoreFields = getFieldSetIfPresent(standardDirectives, NO_STORE);
    privateFields = getFieldSetIfPresent(standardDirectives, PRIVATE);
  }

  /**
   * Returns a map of all directives and their arguments. Directives that don't have arguments will
   * have an empty string value.
   */
  public Map<String, String> directives() {
    return directives;
  }

  /** Returns the value of the {@code max-age} directive or {@code -1} if not present. */
  public int maxAgeSeconds() {
    return maxAgeSeconds;
  }

  /** Returns the value of the {@code min-fresh} directive or {@code -1} if not present. */
  public int minFreshSeconds() {
    return minFreshSeconds;
  }

  /** Returns the value of the {@code s-maxage} directive or {@code -1} if not present. */
  public int sMaxAgeSeconds() {
    return sMaxAgeSeconds;
  }

  /** Returns the value of the {@code max-stale} directive or {@code -1} if not present. */
  public int maxStaleSeconds() {
    return maxStaleSeconds;
  }

  /**
   * Returns whether the {@code max-stale} directive is present but has no value, which indicates
   * any number of seconds is acceptable for a stale response.
   */
  public boolean anyMaxStale() {
    return maxStaleSeconds == ABSENT_INT && hasMaxStale;
  }

  /** Returns whether the {@code no-cache} directive is present. */
  public boolean noCache() {
    return noCache;
  }

  /** Returns the header fields nominated by {@code no-cache} if specified. */
  public Set<String> noCacheFields() {
    return noCacheFields;
  }

  /** Returns whether the {@code no-store} directive is present. */
  public boolean noStore() {
    return noStore;
  }

  /** Returns the header fields nominated by {@code no-store} if specified. */
  public Set<String> noStoreFields() {
    return noStoreFields;
  }

  /** Returns whether the {@code no-transform} directive is present. */
  public boolean noTransform() {
    return noTransform;
  }

  /** Returns whether the {@code public} directive is present. */
  public boolean isPublic() {
    return isPublic;
  }

  /** Returns whether the {@code private} directive is present. */
  public boolean isPrivate() {
    return isPrivate;
  }

  /** Returns the header fields nominated by {@code private} if specified. */
  public Set<String> privateFields() {
    return privateFields;
  }

  /** Returns whether the {@code only-if-cached} directive is present. */
  public boolean onlyIfCached() {
    return onlyIfCached;
  }

  /** Returns whether the {@code must-revalidate} directive is present. */
  public boolean mustRevalidate() {
    return mustRevalidate;
  }

  /** Returns whether the {@code proxy-revalidate} directive is present. */
  public boolean proxyRevalidate() {
    return proxyRevalidate;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    return obj instanceof CacheControl && directives.equals(((CacheControl) obj).directives);
  }

  @Override
  public int hashCode() {
    return 31 * directives.hashCode();
  }

  @Override
  public String toString() {
    return directives.entrySet().stream()
        .map(
            entry ->
                entry.getValue().isEmpty() // has no value
                    ? entry.getKey()
                    : (entry.getKey() + "=" + escapeAndQuoteValueIfNeeded(entry.getValue())))
        .collect(Collectors.joining(", "));
  }

  /**
   * Parses cache directives represented by the given value.
   *
   * @throws IllegalArgumentException if the given value has invalid cache directives
   */
  public static CacheControl parse(String value) {
    requireNonNull(value);
    try {
      var directives = new LinkedHashMap<String, String>();
      parseDirectives(value, directives);
      return new CacheControl(directives, parseStandardDirectives(directives));
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new IllegalArgumentException(format("couldn't parse: '%s'", value), e);
    }
  }

  /**
   * Parses cache directives represented by each of the given values.
   *
   * @throws IllegalArgumentException if any of the given values has invalid cache directives
   */
  public static CacheControl parse(List<String> values) {
    requireNonNull(values);
    try {
      var directives = new LinkedHashMap<String, String>();
      values.forEach(value -> parseDirectives(value, directives));
      return new CacheControl(directives, parseStandardDirectives(directives));
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new IllegalArgumentException(
          format("couldn't parse: '%s'", String.join(", ", values)), e);
    }
  }

  private static int getIntIfPresent(
      Map<StandardDirective, ?> directives, StandardDirective directive) {
    var value = directives.get(directive);
    return value != null ? (int) value : ABSENT_INT;
  }

  @SuppressWarnings("unchecked")
  private static Set<String> getFieldSetIfPresent(
      Map<StandardDirective, ?> directives, StandardDirective directive) {
    var value = directives.get(directive);
    return value != null ? (Set<String>) value : Set.of();
  }

  private static int parseDeltaSeconds(String value) {
    long longVal = Long.parseLong(value);
    requireArgument(longVal >= 0, "negative delta-seconds: %d", longVal);
    return (int) Math.min(Integer.MAX_VALUE, longVal);
  }

  private static Set<String> parseFieldNames(String value) {
    if (value.isEmpty()) {
      return Set.of();
    }

    var tokenizer = new HeaderValueTokenizer(value);
    var fields = new LinkedHashSet<String>();
    do {
      fields.add(normalizeToken(tokenizer.nextToken()));
    } while (tokenizer.consumeDelimiter(','));
    return Collections.unmodifiableSet(fields);
  }

  private static Map<StandardDirective, ?> parseStandardDirectives(Map<String, String> directives) {
    var standardDirectives = new EnumMap<>(StandardDirective.class);
    for (var entry : directives.entrySet()) {
      var directive = StandardDirective.get(entry.getKey());
      if (directive == null) {
        continue;
      }

      var argumentValue = entry.getValue();
      requireArgument(
          !(directive.requiresArgument() && argumentValue.isEmpty()),
          "directive %s requires an argument",
          directive.name);
      switch (directive) {
        case MAX_STALE:
          // max-stale's argument value is optional
          if (argumentValue.isEmpty()) {
            standardDirectives.put(MAX_STALE, ABSENT_INT);
            break;
          }
        case MAX_AGE:
        case MIN_FRESH:
        case S_MAXAGE:
          standardDirectives.put(directive, parseDeltaSeconds(argumentValue));
          break;
        case NO_CACHE:
        case NO_STORE:
        case PRIVATE:
          standardDirectives.put(directive, parseFieldNames(argumentValue));
          break;
        default:
          standardDirectives.put(directive, null);
      }
    }
    return Collections.unmodifiableMap(standardDirectives);
  }

  private static void parseDirectives(String value, Map<String, String> directives) {
    var tokenizer = new HeaderValueTokenizer(value);
    do {
      var name = normalizeToken(tokenizer.nextToken());
      var argumentValue = "";
      if (tokenizer.consumeCharIfPresent('=')) {
        argumentValue = tokenizer.nextTokenOrQuotedString();
      }
      directives.put(name, argumentValue);
    } while (tokenizer.consumeDelimiter(','));
  }

  enum StandardDirective {
    MAX_AGE("max-age") {
      @Override
      boolean requiresArgument() {
        return true;
      }
    },
    MIN_FRESH("min-fresh") {
      @Override
      boolean requiresArgument() {
        return true;
      }
    },
    S_MAXAGE("s-maxage") {
      @Override
      boolean requiresArgument() {
        return true;
      }
    },
    MAX_STALE("max-stale"),
    NO_CACHE("no-cache"),
    NO_STORE("no-store"),
    NO_TRANSFORM("no-transform"),
    PUBLIC("public"),
    PRIVATE("private"),
    ONLY_IF_CACHED("only-if-cached"),
    MUST_REVALIDATE("must-revalidate"),
    PROXY_REVALIDATE("proxy-revalidate");

    // Cached enum values array
    private static final StandardDirective[] DIRECTIVES = values();

    final String name;

    StandardDirective(String name) {
      this.name = name;
    }

    boolean requiresArgument() {
      return false;
    }

    static @Nullable StandardDirective get(String name) {
      for (var directive : DIRECTIVES) {
        if (directive.name.equalsIgnoreCase(name)) {
          return directive;
        }
      }
      return null;
    }
  }
}
