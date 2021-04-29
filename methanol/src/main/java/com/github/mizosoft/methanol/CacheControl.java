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
import static com.github.mizosoft.methanol.internal.Utils.requirePositiveDuration;
import static com.github.mizosoft.methanol.internal.Utils.validateHeaderValue;
import static com.github.mizosoft.methanol.internal.Utils.validateToken;
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.text.HeaderValueTokenizer;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A group of HTTP cache directives as defined by the {@code Cache-Control} header. This class
 * provides type-safe accessors for the directives mentioned in <a
 * href="https://tools.ietf.org/html/rfc7234#section-5.2">RFC 7234 Section 5.2</a>. Other directives
 * ({@code Cache-Control} extensions) can be accessed using the {@link #directives()} map.
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public final class CacheControl {
  private static final int ABSENT_INT = -1;

  private static final CacheControl EMPTY = new CacheControl(Map.of(), Map.of());

  private final Map<String, String> directives;
  private final Optional<Duration> maxAge;
  private final Optional<Duration> minFresh;
  private final Optional<Duration> sMaxAge;
  private final Optional<Duration> maxStale;
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
    maxAge = Optional.ofNullable((Duration) standardDirectives.get(MAX_AGE));
    minFresh = Optional.ofNullable((Duration) standardDirectives.get(MIN_FRESH));
    sMaxAge = Optional.ofNullable((Duration) standardDirectives.get(S_MAXAGE));
    maxStale = Optional.ofNullable((Duration) standardDirectives.get(MAX_STALE));
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
   * Returns a map of all directives and their arguments. Directives that don't have arguments have
   * an empty string value in the returned map.
   */
  public Map<String, String> directives() {
    return directives;
  }

  /** Returns the value of the {@code max-age} directive if present. */
  public Optional<Duration> maxAge() {
    return maxAge;
  }

  /** Returns the value of the {@code s-maxage} directive if present. */
  public Optional<Duration> sMaxAge() {
    return sMaxAge;
  }

  /** Returns the value of the {@code min-fresh} directive if present. */
  public Optional<Duration> minFresh() {
    return minFresh;
  }

  /** Returns the value of the {@code max-stale} directive if present. */
  public Optional<Duration> maxStale() {
    return maxStale;
  }

  // TODO remove *Seconds()?

  /** Returns the value of the {@code max-age} directive or {@code -1} if not present. */
  public long maxAgeSeconds() {
    return getSecondsIfPresent(maxAge);
  }

  /** Returns the value of the {@code min-fresh} directive or {@code -1} if not present. */
  public long minFreshSeconds() {
    return getSecondsIfPresent(minFresh);
  }

  /** Returns the value of the {@code s-maxage} directive or {@code -1} if not present. */
  public long sMaxAgeSeconds() {
    return getSecondsIfPresent(sMaxAge);
  }

  /** Returns the value of the {@code max-stale} directive or {@code -1} if not present. */
  public long maxStaleSeconds() {
    return getSecondsIfPresent(maxStale);
  }

  private long getSecondsIfPresent(Optional<Duration> duration) {
    return duration.map(Duration::getSeconds).orElse((long) ABSENT_INT);
  }

  /**
   * Returns whether the {@code max-stale} directive is present but has no value, which indicates
   * any number of seconds is acceptable for a stale response.
   */
  public boolean anyMaxStale() {
    return maxStale.isEmpty() && hasMaxStale;
  }

  /** Returns {@code true} if the {@code no-cache} directive is set. */
  public boolean noCache() {
    return noCache;
  }

  /** Returns the header fields nominated by {@code no-cache} if specified. */
  public Set<String> noCacheFields() {
    return noCacheFields;
  }

  /** Returns {@code true} if the {@code no-store} directive is set. */
  public boolean noStore() {
    return noStore;
  }

  /** Returns the header fields nominated by {@code no-store} if specified. */
  public Set<String> noStoreFields() {
    return noStoreFields;
  }

  /** Returns {@code true} if the {@code no-transform} directive is set. */
  public boolean noTransform() {
    return noTransform;
  }

  /** Returns {@code true} if the {@code public} directive is set. */
  public boolean isPublic() {
    return isPublic;
  }

  /** Returns {@code true} if the {@code private} directive is set. */
  public boolean isPrivate() {
    return isPrivate;
  }

  /** Returns the header fields nominated by {@code private} if specified. */
  public Set<String> privateFields() {
    return privateFields;
  }

  /** Returns {@code true} if the {@code only-if-cached} directive is set. */
  public boolean onlyIfCached() {
    return onlyIfCached;
  }

  /** Returns {@code true} if the {@code must-revalidate} directive is set. */
  public boolean mustRevalidate() {
    return mustRevalidate;
  }

  /** Returns {@code true} if the {@code proxy-revalidate} directive is set. */
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
                    : entry.getKey() + "=" + escapeAndQuoteValueIfNeeded(entry.getValue()))
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

  public static Builder newBuilder() {
    return new Builder();
  }

  /** Returns a {@code CacheControl} with no directives. */
  public static CacheControl empty() {
    return EMPTY;
  }

  @SuppressWarnings("unchecked")
  private static Set<String> getFieldSetIfPresent(
      Map<StandardDirective, ?> directives, StandardDirective directive) {
    var value = directives.get(directive);
    return value != null ? (Set<String>) value : Set.of();
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
      if (directive != null) {
        var argumentValue = entry.getValue();
        requireArgument(
            !(directive.requiresArgument() && argumentValue.isEmpty()),
            "directive %s requires an argument",
            directive.token);
        standardDirectives.put(directive, convertArgument(directive, argumentValue));
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

  /** Converts the string argument to the typesafe argument corresponding to the directive. */
  private static @Nullable Object convertArgument(
      StandardDirective directive, String argumentValue) {
    switch (directive) {
      case MAX_STALE:
        // max-stale's argument value is optional
        if (argumentValue.isEmpty()) {
          return null;
        }
        // fallthrough
      case MAX_AGE:
      case MIN_FRESH:
      case S_MAXAGE:
        var duration = Duration.ofSeconds(Long.parseLong(argumentValue));
        requirePositiveDuration(duration);
        return duration;

      case NO_CACHE:
      case NO_STORE:
      case PRIVATE:
        return parseFieldNames(argumentValue);

      default:
        return null; // No argument
    }
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

    final String token;

    StandardDirective(String token) {
      this.token = token;
    }

    boolean requiresArgument() {
      return false;
    }

    static @Nullable StandardDirective get(String name) {
      for (var directive : DIRECTIVES) {
        if (directive.token.equalsIgnoreCase(name)) {
          return directive;
        }
      }
      return null;
    }
  }

  /**
   * A builder of {@code CacheControl} instances for request cache directives. Methods that accept a
   * {@code Duration} will drop any precision finer than that of a second, which is the only
   * precision allowed by cache directives representing durations.
   */
  public static final class Builder {
    private final Map<String, String> directives = new LinkedHashMap<>();
    private final Map<StandardDirective, Object> standardDirectives =
        new EnumMap<>(StandardDirective.class);

    Builder() {}

    /**
     * Sets the given directive.
     *
     * @throws IllegalArgumentException if {@code directive} is invalid
     */
    public Builder directive(String directive) {
      return directive(directive, "");
    }

    /**
     * Sets the given directive to the given argument.
     *
     * @throws IllegalArgumentException if either of {@code directive} or {@code argument} is
     *     invalid
     */
    public Builder directive(String directive, String argument) {
      validateToken(directive);
      validateHeaderValue(argument);
      directives.put(normalizeToken(directive), argument);
      var std = StandardDirective.get(directive);
      if (std != null) {
        standardDirectives.put(std, convertArgument(std, argument));
      }
      return this;
    }

    /** Sets the {@code max-age} directive to the given duration. */
    public Builder maxAge(Duration maxAge) {
      return putDuration(MAX_AGE, maxAge);
    }

    /** Sets the {@code min-fresh} directive to the given duration. */
    public Builder minFresh(Duration minFresh) {
      return putDuration(MIN_FRESH, minFresh);
    }

    /** Sets the {@code max-stale} directive to the given duration. */
    public Builder maxStale(Duration maxStale) {
      return putDuration(MAX_STALE, maxStale);
    }

    /** Sets the {@code max-stale} directive to accept any stale response. */
    public Builder anyMaxStale() {
      return put(MAX_STALE);
    }

    /** Sets the {@code no-cache} directive. */
    public Builder noCache() {
      return put(NO_CACHE);
    }

    /** Sets the {@code no-store} directive. */
    public Builder noStore() {
      return put(NO_STORE);
    }

    /** Sets the {@code no-store} directive. */
    public Builder noTransform() {
      return put(NO_TRANSFORM);
    }

    /** Sets the {@code only-if-cached} directive. */
    public Builder onlyIfCached() {
      return put(ONLY_IF_CACHED);
    }

    private Builder putDuration(StandardDirective directive, Duration duration) {
      var truncated = duration.truncatedTo(ChronoUnit.SECONDS);
      requirePositiveDuration(truncated);
      standardDirectives.put(directive, truncated);
      directives.put(directive.token, Long.toString(truncated.toSeconds()));
      return this;
    }

    private Builder put(StandardDirective directive) {
      standardDirectives.put(directive, null);
      directives.put(directive.token, "");
      return this;
    }

    /** Builds a new {@code Cache-Control}. */
    public CacheControl build() {
      return new CacheControl(
          new LinkedHashMap<>(directives), // Copy directives map as it's stored
          standardDirectives);
    }
  }
}
