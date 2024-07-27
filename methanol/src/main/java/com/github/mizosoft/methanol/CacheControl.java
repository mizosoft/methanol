/*
 * Copyright (c) 2024 Moataz Abdelnasser
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

import static com.github.mizosoft.methanol.internal.Utils.escapeAndQuoteValueIfNeeded;
import static com.github.mizosoft.methanol.internal.Utils.requireNonNegativeDuration;
import static com.github.mizosoft.methanol.internal.Utils.requireValidHeaderValue;
import static com.github.mizosoft.methanol.internal.Utils.requireValidToken;
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static com.github.mizosoft.methanol.internal.cache.HttpDates.parseDeltaSeconds;

import com.github.mizosoft.methanol.internal.text.HeaderValueTokenizer;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
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
 *
 * @see <a href="https://mizosoft.github.io/methanol/caching/#cachecontrol">Caching -
 *     CacheControl</a> for an overview of the semantics of supported cache directives.
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public final class CacheControl {
  private static final CacheControl EMPTY = new Builder().build();

  private final Optional<Duration> maxAge;
  private final Optional<Duration> minFresh;
  private final Optional<Duration> sMaxAge;
  private final Optional<Duration> maxStale;
  private final Optional<Duration> staleWhileRevalidate;
  private final Optional<Duration> staleIfError;

  // TODO we might want to indicate that by just having Integer.MAX_VALUE as maxStale, which is
  //      considered by the RFC as effectively infinite.
  private final boolean anyMaxStale;

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

  /** The map of all directives, lazily initialized if this instance wasn't parsed from a string. */
  private @MonotonicNonNull Map<String, String> lazyDirectives;

  /**
   * Map that contains unrecognized (non-standard) directives added via {@link Builder#directive}.
   * Empty if this instance was parsed from a string or no unrecognized fields were added.
   */
  private final Map<String, String> unrecognizedAddedDirectives;

  private @MonotonicNonNull String lazyToString;

  private CacheControl(Builder builder) {
    maxAge = Optional.ofNullable(builder.maxAge);
    minFresh = Optional.ofNullable(builder.minFresh);
    sMaxAge = Optional.ofNullable(builder.sMaxAge);
    maxStale = Optional.ofNullable(builder.maxStale);
    staleWhileRevalidate = Optional.ofNullable(builder.staleWhileRevalidate);
    staleIfError = Optional.ofNullable(builder.staleIfError);
    anyMaxStale = builder.anyMaxStale;
    noCache = builder.noCache;
    noStore = builder.noStore;
    noTransform = builder.noTransform;
    isPublic = builder.isPublic;
    isPrivate = builder.isPrivate;
    onlyIfCached = builder.onlyIfCached;
    mustRevalidate = builder.mustRevalidate;
    proxyRevalidate = builder.proxyRevalidate;
    noCacheFields = builder.noCacheFields;
    noStoreFields = builder.noStoreFields;
    privateFields = builder.privateFields;

    var parsedDirectives = builder.parsedDirectives;
    if (parsedDirectives != null) {
      // Safe to retain a reference as parsedDirectives is never modified again when parsed.
      lazyDirectives = Collections.unmodifiableMap(parsedDirectives);
      unrecognizedAddedDirectives = Map.of();
    } else {
      unrecognizedAddedDirectives = Map.copyOf(builder.unrecognizedDirectives);
    }
  }

  /**
   * Returns a map of all directives and their arguments. Directives that don't have arguments are
   * mapped to an empty string.
   */
  public Map<String, String> directives() {
    var directives = lazyDirectives;
    if (directives == null) {
      directives = computeDirectives();
      lazyDirectives = directives;
    }
    return directives;
  }

  private Map<String, String> computeDirectives() {
    var directives = new LinkedHashMap<String, String>();

    maxAge.ifPresent(maxAge -> directives.put("max-age", Long.toString(maxAge.toSeconds())));
    minFresh.ifPresent(
        minFresh -> directives.put("min-fresh", Long.toString(minFresh.toSeconds())));
    sMaxAge.ifPresent(sMaxAge -> directives.put("s-maxage", Long.toString(sMaxAge.toSeconds())));
    maxStale.ifPresentOrElse(
        maxStale -> directives.put("max-stale", Long.toString(maxStale.toSeconds())),
        () -> {
          if (anyMaxStale) {
            directives.put("max-stale", ""); // Any max-stale is indicated by an empty argument
          }
        });
    staleWhileRevalidate.ifPresent(
        staleWhileRevalidate ->
            directives.put(
                "stale-while-revalidate", Long.toString(staleWhileRevalidate.toSeconds())));
    staleIfError.ifPresent(
        staleIfError -> directives.put("stale-if-error", Long.toString(staleIfError.toSeconds())));

    if (noCache) {
      directives.put("no-cache", joinFields(noCacheFields));
    }
    if (noStore) {
      directives.put("no-store", joinFields(noStoreFields));
    }
    if (noTransform) {
      directives.put("no-transform", "");
    }
    if (isPublic) {
      directives.put("public", "");
    }
    if (isPrivate) {
      directives.put("private", joinFields(privateFields));
    }
    if (onlyIfCached) {
      directives.put("only-if-cached", "");
    }
    if (mustRevalidate) {
      directives.put("must-revalidate", "");
    }
    if (proxyRevalidate) {
      directives.put("proxy-revalidate", "");
    }

    directives.putAll(unrecognizedAddedDirectives);
    return Collections.unmodifiableMap(directives);
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
    return anyMaxStale;
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
  public boolean equals(@Nullable Object obj) {
    if (obj == this) {
      return true;
    }
    if (!(obj instanceof CacheControl)) {
      return false;
    }
    return directives().equals(((CacheControl) obj).directives());
  }

  @Override
  public int hashCode() {
    return 31 * directives().hashCode();
  }

  @Override
  public String toString() {
    var toString = lazyToString;
    if (toString == null) {
      toString = computeToString();
      lazyToString = toString;
    }
    return toString;
  }

  private String computeToString() {
    return directives().entrySet().stream()
        .map(
            entry ->
                entry.getValue().isEmpty() // Directive has no value?
                    ? entry.getKey()
                    : entry.getKey() + "=" + escapeAndQuoteValueIfNeeded(entry.getValue()))
        .collect(Collectors.joining(", "));
  }

  /**
   * Parses the cache directives specified by the given value.
   *
   * @throws IllegalArgumentException if the given value has invalid cache directives
   */
  public static CacheControl parse(String value) {
    return parse(List.of(value));
  }

  /**
   * Parses the cache directives specified by each of the given values.
   *
   * @throws IllegalArgumentException if any of the given values has invalid cache directives
   */
  public static CacheControl parse(List<String> values) {
    if (values.isEmpty()) {
      return empty();
    }

    try {
      var directives = new LinkedHashMap<String, String>();
      values.forEach(value -> parseDirectives(value, directives));
      return new Builder(directives).build();
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new IllegalArgumentException("Couldn't parse: '" + String.join(", ", values) + "'", e);
    }
  }

  /**
   * Parses the cache directives specified by the given headers.
   *
   * @throws IllegalArgumentException if the given headers have any invalid cache directives
   */
  public static CacheControl parse(HttpHeaders headers) {
    return parse(headers.allValues("Cache-Control"));
  }

  /** Returns a new {@code Builder}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** Returns a {@code CacheControl} with no directives. */
  public static CacheControl empty() {
    return EMPTY;
  }

  private static void parseDirectives(String value, Map<String, String> directives) {
    // Cache-Control   = *( "," OWS ) cache-directive *( OWS "," [ OWS cache-directive ] )
    // cache-directive = token [ "=" ( token / quoted-string ) ]
    var tokenizer = new HeaderValueTokenizer(value);
    tokenizer.consumeDelimiter(',', false); // First delimiter is optional
    do {
      var normalizedDirective = tokenizer.nextToken().toLowerCase(Locale.ROOT);
      var argument = "";
      if (tokenizer.consumeCharIfPresent('=')) {
        argument = tokenizer.nextTokenOrQuotedString();
      }
      boolean duplicateDirective = directives.put(normalizedDirective, argument) != null;
      requireArgument(!duplicateDirective, "Duplicate directive: '%s'", normalizedDirective);
    } while (tokenizer.consumeDelimiter(','));
  }

  private static String joinFields(Set<String> fields) {
    return fields.isEmpty() ? "" : String.join(", ", fields);
  }

  /**
   * A builder of {@code CacheControl} instances, with explicit directive setters for request cache
   * directives.
   *
   * <p>Methods that accept a {@code Duration} drop any precision finer than that of a second, which
   * is the only precision allowed by cache directives representing durations. Additionally, any
   * duration with number of seconds that's larger than {@link Integer#MAX_VALUE} is truncated to
   * that value.
   */
  public static final class Builder {
    private static final Duration MAX_DELTA_SECONDS = Duration.ofSeconds(Integer.MAX_VALUE);

    final @Nullable Map<String, String> parsedDirectives;
    final Map<String, String> unrecognizedDirectives = new LinkedHashMap<>();

    @MonotonicNonNull Duration maxAge;
    @MonotonicNonNull Duration minFresh;
    @MonotonicNonNull Duration sMaxAge;
    @Nullable Duration maxStale; // This can set to null by anyMaxStale()
    @MonotonicNonNull Duration staleWhileRevalidate;
    @MonotonicNonNull Duration staleIfError;
    boolean anyMaxStale;
    boolean noCache;
    boolean noStore;
    boolean noTransform;
    boolean isPublic;
    boolean isPrivate;
    boolean onlyIfCached;
    boolean mustRevalidate;
    boolean proxyRevalidate;
    Set<String> noCacheFields = Set.of();
    Set<String> noStoreFields = Set.of();
    Set<String> privateFields = Set.of();

    Builder() {
      this.parsedDirectives = null;
    }

    Builder(Map<String, String> parsedDirectives) {
      this.parsedDirectives = Collections.unmodifiableMap(parsedDirectives);
      parsedDirectives.forEach(this::setNormalizedDirective);
    }

    /**
     * Sets the given directive with no argument.
     *
     * @throws IllegalArgumentException if {@code directive} is invalid
     */
    @CanIgnoreReturnValue
    public Builder directive(String directive) {
      return directive(directive, "");
    }

    /**
     * Sets the given directive to the given argument. If {@code argument} is an empty string, the
     * directive is considered one without argument.
     *
     * @throws IllegalArgumentException if either of {@code directive} or {@code argument} is
     *     invalid
     */
    @CanIgnoreReturnValue
    public Builder directive(String directive, String argument) {
      setNormalizedDirective(
          requireValidToken(directive).toLowerCase(Locale.ROOT), requireValidHeaderValue(argument));
      return this;
    }

    /** Sets directive directly without validation (directive is expected to be normalized). */
    private void setNormalizedDirective(String normalizedDirective, String argument) {
      if (!setStandardDirective(normalizedDirective, argument)) {
        unrecognizedDirectives.put(normalizedDirective, argument);
      }
    }

    /**
     * Sets the {@code max-age} directive to the given duration.
     *
     * @throws IllegalArgumentException If {@code maxAge} doesn't contain a positive number of
     *     seconds
     */
    @CanIgnoreReturnValue
    public Builder maxAge(Duration maxAge) {
      this.maxAge = checkAndTruncateDeltaSeconds(maxAge);
      return this;
    }

    /**
     * Sets the {@code min-fresh} directive to the given duration.
     *
     * @throws IllegalArgumentException If {@code minFresh} doesn't contain a positive number of
     *     seconds
     */
    @CanIgnoreReturnValue
    public Builder minFresh(Duration minFresh) {
      this.minFresh = checkAndTruncateDeltaSeconds(minFresh);
      return this;
    }

    /**
     * Sets the {@code max-stale} directive to the given duration.
     *
     * @throws IllegalArgumentException If {@code maxStale} doesn't contain a positive number of
     *     seconds
     */
    @CanIgnoreReturnValue
    public Builder maxStale(Duration maxStale) {
      this.maxStale = checkAndTruncateDeltaSeconds(maxStale);
      anyMaxStale = false; // Invalidate any max-stale (if previously set)
      return this;
    }

    /** Sets the {@code max-stale} directive to accept any stale response. */
    @CanIgnoreReturnValue
    public Builder anyMaxStale() {
      this.maxStale = null; // Invalidate current max stale, if any
      anyMaxStale = true;
      return this;
    }

    /**
     * Sets the {@code stale-if-error} directive to the given duration.
     *
     * @throws IllegalArgumentException If {@code staleIfError} doesn't contain a positive number of
     *     seconds
     */
    @CanIgnoreReturnValue
    public Builder staleIfError(Duration staleIfError) {
      this.staleIfError = checkAndTruncateDeltaSeconds(staleIfError);
      return this;
    }

    /** Sets the {@code no-cache} directive. */
    @CanIgnoreReturnValue
    public Builder noCache() {
      noCache = true;
      return this;
    }

    /** Sets the {@code no-store} directive. */
    @CanIgnoreReturnValue
    public Builder noStore() {
      noStore = true;
      return this;
    }

    /** Sets the {@code no-transform} directive. */
    @CanIgnoreReturnValue
    public Builder noTransform() {
      noTransform = true;
      return this;
    }

    /** Sets the {@code only-if-cached} directive. */
    @CanIgnoreReturnValue
    public Builder onlyIfCached() {
      onlyIfCached = true;
      return this;
    }

    /** Builds a new {@code Cache-Control}. */
    public CacheControl build() {
      return new CacheControl(this);
    }

    private Duration checkAndTruncateDeltaSeconds(Duration duration) {
      requireNonNegativeDuration(duration);
      var truncated = duration.truncatedTo(ChronoUnit.SECONDS);
      if (truncated.toSeconds() > Integer.MAX_VALUE) {
        truncated = MAX_DELTA_SECONDS;
      }
      return truncated;
    }

    private boolean mustHaveArgument(String normalizedDirective) {
      return normalizedDirective.equals("max-age")
          || normalizedDirective.equals("min-fresh")
          || normalizedDirective.equals("s-maxage")
          || normalizedDirective.equals("stale-while-revalidate")
          || normalizedDirective.equals("stale-if-error");
    }

    private boolean setStandardDirective(String normalizedDirective, String argument) {
      requireArgument(
          !(mustHaveArgument(normalizedDirective) && argument.isEmpty()),
          "Directive '%s' requires an argument",
          normalizedDirective);

      switch (normalizedDirective) {
        case "max-age":
          maxAge = parseDeltaSeconds(argument);
          break;
        case "min-fresh":
          minFresh = parseDeltaSeconds(argument);
          break;
        case "s-maxage":
          sMaxAge = parseDeltaSeconds(argument);
          break;
        case "max-stale":
          if (argument.isEmpty()) {
            anyMaxStale = true;
          } else {
            maxStale = parseDeltaSeconds(argument);
          }
          break;
        case "stale-while-revalidate":
          staleWhileRevalidate = parseDeltaSeconds(argument);
          break;
        case "stale-if-error":
          staleIfError = parseDeltaSeconds(argument);
          break;
        case "no-cache":
          noCache = true;
          if (!argument.isEmpty()) {
            noCacheFields = parseFieldNames(argument);
          }
          break;
        case "no-store":
          noStore = true;
          if (!argument.isEmpty()) {
            noStoreFields = parseFieldNames(argument);
          }
          break;
        case "no-transform":
          noTransform = true;
          break;
        case "public":
          isPublic = true;
          break;
        case "private":
          isPrivate = true;
          if (!argument.isEmpty()) {
            privateFields = parseFieldNames(argument);
          }
          break;
        case "only-if-cached":
          onlyIfCached = true;
          break;
        case "must-revalidate":
          mustRevalidate = true;
          break;
        case "proxy-revalidate":
          proxyRevalidate = true;
          break;
        default:
          return false; // Not a standard directive
      }
      return true;
    }

    private static Set<String> parseFieldNames(String value) {
      if (value.isEmpty()) {
        return Set.of();
      }

      var tokenizer = new HeaderValueTokenizer(value);
      var fields = new LinkedHashSet<String>();
      do {
        fields.add(tokenizer.nextToken().toLowerCase(Locale.ROOT));
      } while (tokenizer.consumeDelimiter(','));
      return Collections.unmodifiableSet(fields);
    }
  }
}
