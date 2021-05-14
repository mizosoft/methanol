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

import static com.github.mizosoft.methanol.CacheControl.KnownDirective.MAX_AGE;
import static com.github.mizosoft.methanol.CacheControl.KnownDirective.MAX_STALE;
import static com.github.mizosoft.methanol.CacheControl.KnownDirective.MIN_FRESH;
import static com.github.mizosoft.methanol.CacheControl.KnownDirective.MUST_REVALIDATE;
import static com.github.mizosoft.methanol.CacheControl.KnownDirective.NO_CACHE;
import static com.github.mizosoft.methanol.CacheControl.KnownDirective.NO_STORE;
import static com.github.mizosoft.methanol.CacheControl.KnownDirective.NO_TRANSFORM;
import static com.github.mizosoft.methanol.CacheControl.KnownDirective.ONLY_IF_CACHED;
import static com.github.mizosoft.methanol.CacheControl.KnownDirective.PRIVATE;
import static com.github.mizosoft.methanol.CacheControl.KnownDirective.PROXY_REVALIDATE;
import static com.github.mizosoft.methanol.CacheControl.KnownDirective.PUBLIC;
import static com.github.mizosoft.methanol.CacheControl.KnownDirective.STALE_IF_ERROR;
import static com.github.mizosoft.methanol.CacheControl.KnownDirective.STALE_WHILE_REVALIDATE;
import static com.github.mizosoft.methanol.CacheControl.KnownDirective.S_MAXAGE;
import static com.github.mizosoft.methanol.internal.Utils.escapeAndQuoteValueIfNeeded;
import static com.github.mizosoft.methanol.internal.Utils.normalizeToken;
import static com.github.mizosoft.methanol.internal.Utils.requireNonNegativeDuration;
import static com.github.mizosoft.methanol.internal.Utils.validateHeaderValue;
import static com.github.mizosoft.methanol.internal.Utils.validateToken;
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.function.Predicate.not;

import com.github.mizosoft.methanol.internal.text.HeaderValueTokenizer;
import java.net.http.HttpHeaders;
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
 * A group of <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Cache-Control">
 * cache directives</a>.
 *
 * <p>{@code CacheControl} provides type-safe accessors for the directives specified in <a
 * href="https://tools.ietf.org/html/rfc7234#section-5.2">RFC 7234</a>. Additionally, there's
 * support for the {@code stale-while-revalidate} {@literal &} {@code stale-if-error} extensions
 * specified in <a href="https://tools.ietf.org/html/rfc5861">RFC 5861</a>. Other {@code
 * Cache-Control} extensions can be accessed using the {@link #directives()} map.
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public final class CacheControl {
  private static final CacheControl EMPTY = new CacheControl(Map.of(), Map.of());

  private final Map<String, String> directives;
  private final Optional<Duration> maxAge;
  private final Optional<Duration> minFresh;
  private final Optional<Duration> sMaxAge;
  private final Optional<Duration> maxStale;
  private final Optional<Duration> staleWhileRevalidate;
  private final Optional<Duration> staleIfError;
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

  private CacheControl(Map<String, String> directives, Map<KnownDirective, ?> knownDirectives) {
    this.directives = Collections.unmodifiableMap(directives);
    maxAge = getDurationIfPresent(knownDirectives, MAX_AGE);
    minFresh = getDurationIfPresent(knownDirectives, MIN_FRESH);
    sMaxAge = getDurationIfPresent(knownDirectives, S_MAXAGE);
    maxStale = getDurationIfPresent(knownDirectives, MAX_STALE);
    staleWhileRevalidate = getDurationIfPresent(knownDirectives, STALE_WHILE_REVALIDATE);
    staleIfError = getDurationIfPresent(knownDirectives, STALE_IF_ERROR);
    hasMaxStale = knownDirectives.containsKey(MAX_STALE);
    noCache = knownDirectives.containsKey(NO_CACHE);
    noStore = knownDirectives.containsKey(NO_STORE);
    noTransform = knownDirectives.containsKey(NO_TRANSFORM);
    isPublic = knownDirectives.containsKey(PUBLIC);
    isPrivate = knownDirectives.containsKey(PRIVATE);
    onlyIfCached = knownDirectives.containsKey(ONLY_IF_CACHED);
    mustRevalidate = knownDirectives.containsKey(MUST_REVALIDATE);
    proxyRevalidate = knownDirectives.containsKey(PROXY_REVALIDATE);
    noCacheFields = getFieldSetIfPresent(knownDirectives, NO_CACHE);
    noStoreFields = getFieldSetIfPresent(knownDirectives, NO_STORE);
    privateFields = getFieldSetIfPresent(knownDirectives, PRIVATE);
  }

  /**
   * Returns a map of all directives and their arguments. Directives that don't have arguments are
   * mapped to an empty string.
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

  /**
   * Returns whether the {@code max-stale} directive is present but has no value, which indicates
   * that a response with any staleness is acceptable.
   */
  public boolean anyMaxStale() {
    return maxStale.isEmpty() && hasMaxStale;
  }

  /** Returns the value of the {@code stale-while-revalidate} directive if present. */
  public Optional<Duration> staleWhileRevalidate() {
    return staleWhileRevalidate;
  }

  /** Returns the value of the {@code stale-if-error} directive if present. */
  public Optional<Duration> staleIfError() {
    return staleIfError;
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
      return new CacheControl(directives, parseKnownDirectives(directives));
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
    if (values.isEmpty()) {
      return empty();
    }
    try {
      var directives = new LinkedHashMap<String, String>();
      values.forEach(value -> parseDirectives(value, directives));
      return new CacheControl(directives, parseKnownDirectives(directives));
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new IllegalArgumentException(
          format("couldn't parse: '%s'", String.join(", ", values)), e);
    }
  }

  /**
   * Parses the cache directives specified by the given headers.
   *
   * @throws IllegalArgumentException if the given headers have any invalid cache directives
   */
  public static CacheControl parse(HttpHeaders headers) {
    return CacheControl.parse(
        headers.allValues("Cache-Control").stream()
            .filter(not(String::isBlank))
            .collect(Collectors.toUnmodifiableList()));
  }

  /** Returns a new {@code Builder}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** Returns a {@code CacheControl} with no directives. */
  public static CacheControl empty() {
    return EMPTY;
  }

  private static Optional<Duration> getDurationIfPresent(
      Map<KnownDirective, ?> directives, KnownDirective directive) {
    return Optional.ofNullable((Duration) directives.get(directive));
  }

  @SuppressWarnings("unchecked")
  private static Set<String> getFieldSetIfPresent(
      Map<KnownDirective, ?> directives, KnownDirective directive) {
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

  private static Map<KnownDirective, ?> parseKnownDirectives(Map<String, String> directives) {
    var knownDirectives = new EnumMap<>(KnownDirective.class);
    for (var entry : directives.entrySet()) {
      var directive = KnownDirective.get(entry.getKey());
      if (directive != null) {
        var argumentValue = entry.getValue();
        requireArgument(
            !(directive.requiresArgument && argumentValue.isEmpty()),
            "directive %s requires an argument",
            directive.token);
        knownDirectives.put(directive, convertArgument(directive, argumentValue));
      }
    }
    return Collections.unmodifiableMap(knownDirectives);
  }

  /** Converts a string argument to the typesafe argument corresponding to the directive. */
  private static @Nullable Object convertArgument(KnownDirective directive, String argumentValue) {
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
      case STALE_WHILE_REVALIDATE:
      case STALE_IF_ERROR:
        var duration = Duration.ofSeconds(Long.parseLong(argumentValue));
        requireNonNegativeDuration(duration);
        return duration;

      case NO_CACHE:
      case NO_STORE:
      case PRIVATE:
        return parseFieldNames(argumentValue);

      default:
        return null; // No argument
    }
  }

  enum KnownDirective {
    MAX_AGE("max-age", true),
    MIN_FRESH("min-fresh", true),
    S_MAXAGE("s-maxage", true),
    MAX_STALE("max-stale", false),
    STALE_WHILE_REVALIDATE("stale-while-revalidate", true),
    STALE_IF_ERROR("stale-if-error", true),
    NO_CACHE("no-cache", false),
    NO_STORE("no-store", false),
    NO_TRANSFORM("no-transform", false),
    PUBLIC("public", false),
    PRIVATE("private", false),
    ONLY_IF_CACHED("only-if-cached", false),
    MUST_REVALIDATE("must-revalidate", false),
    PROXY_REVALIDATE("proxy-revalidate", false);

    // Cached enum values array
    private static final KnownDirective[] DIRECTIVES = values();

    final String token;
    final boolean requiresArgument;

    KnownDirective(String token, boolean requiresArgument) {
      this.token = token;
      this.requiresArgument = requiresArgument;
    }

    static @Nullable KnownDirective get(String name) {
      for (var directive : DIRECTIVES) {
        if (directive.token.equalsIgnoreCase(name)) {
          return directive;
        }
      }
      return null;
    }
  }

  /**
   * A builder of {@code CacheControl} instances declaring request cache directives.
   *
   * <p>Methods that accept a {@code Duration} drop any precision finer than that of a second, which
   * is the only precision allowed by cache directives representing durations.
   */
  public static final class Builder {
    private final Map<String, String> directives = new LinkedHashMap<>();
    private final Map<KnownDirective, Object> knownDirectives = new EnumMap<>(KnownDirective.class);

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
      var known = KnownDirective.get(directive);
      if (known != null) {
        knownDirectives.put(known, convertArgument(known, argument));
      }
      return this;
    }

    /**
     * Sets the {@code max-age} directive to the given duration.
     *
     * @throws IllegalArgumentException If {@code maxAge} doesn't contain a positive number of
     *     seconds
     */
    public Builder maxAge(Duration maxAge) {
      return putDuration(MAX_AGE, maxAge);
    }

    /**
     * Sets the {@code min-fresh} directive to the given duration.
     *
     * @throws IllegalArgumentException If {@code minFresh} doesn't contain a positive number of
     *     seconds
     */
    public Builder minFresh(Duration minFresh) {
      return putDuration(MIN_FRESH, minFresh);
    }

    /**
     * Sets the {@code max-stale} directive to the given duration.
     *
     * @throws IllegalArgumentException If {@code maxStale} doesn't contain a positive number of
     *     seconds
     */
    public Builder maxStale(Duration maxStale) {
      return putDuration(MAX_STALE, maxStale);
    }

    /** Sets the {@code max-stale} directive to accept any stale response. */
    public Builder anyMaxStale() {
      return put(MAX_STALE);
    }

    /**
     * Sets the {@code stale-if-error} directive to the given duration.
     *
     * @throws IllegalArgumentException If {@code staleIfError} doesn't contain a positive number of
     *     seconds
     */
    public Builder staleIfError(Duration staleIfError) {
      return putDuration(STALE_IF_ERROR, staleIfError);
    }

    /** Sets the {@code no-cache} directive. */
    public Builder noCache() {
      return put(NO_CACHE);
    }

    /** Sets the {@code no-store} directive. */
    public Builder noStore() {
      return put(NO_STORE);
    }

    /** Sets the {@code no-transform} directive. */
    public Builder noTransform() {
      return put(NO_TRANSFORM);
    }

    /** Sets the {@code only-if-cached} directive. */
    public Builder onlyIfCached() {
      return put(ONLY_IF_CACHED);
    }

    private Builder putDuration(KnownDirective directive, Duration duration) {
      requireNonNull(duration);
      var truncated = duration.truncatedTo(ChronoUnit.SECONDS);
      requireNonNegativeDuration(truncated);
      knownDirectives.put(directive, truncated);
      directives.put(directive.token, Long.toString(truncated.toSeconds()));
      return this;
    }

    private Builder put(KnownDirective directive) {
      knownDirectives.put(directive, null);
      directives.put(directive.token, "");
      return this;
    }

    /** Builds a new {@code Cache-Control}. */
    public CacheControl build() {
      // Make a defensive directives copy
      return new CacheControl(new LinkedHashMap<>(directives), knownDirectives);
    }
  }
}
