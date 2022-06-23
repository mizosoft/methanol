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

import static com.github.mizosoft.methanol.internal.Utils.isValidToken;
import static com.github.mizosoft.methanol.internal.Utils.requirePositiveDuration;
import static com.github.mizosoft.methanol.internal.Utils.validateHeader;
import static com.github.mizosoft.methanol.internal.Utils.validateHeaderName;
import static com.github.mizosoft.methanol.internal.Utils.validateHeaderValue;
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.extensions.HeadersBuilder;
import java.net.URI;
import java.net.http.HttpClient.Version;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A mutable {@code HttpRequest}. This class implements {@link HttpRequest.Builder} for setting the
 * request's fields. Querying a field before it's been set will return its default value. Invoking
 * the {@link #toImmutableRequest()} method will return an immutable {@code HttpRequest} copy that
 * is independent of this instance.
 *
 * <p>{@code MutableRequest} adds some convenience when the {@code HttpRequest} is used immediately
 * after creation:
 *
 * <pre>{@code
 * client.send(
 *     MutableRequest
 *         .GET("https://www.google.com/search?q=java")
 *         .header("Accept", "text/html"),
 *     BodyHandlers.ofString());
 * }</pre>
 *
 * <p>Additionally, this class allows setting a {@code URI} without a host or a scheme or not
 * setting a {@code URI} entirely. This is for the case when the request is used with a {@link
 * Methanol} client that has a base URL, with which this request's URL is resolved.
 */
public final class MutableRequest extends TaggableRequest implements TaggableRequest.Builder {
  private static final URI EMPTY_URI = URI.create("");

  private final Map<TypeRef<?>, Object> tags = new HashMap<>();

  private final HeadersBuilder headersBuilder = new HeadersBuilder();
  private String method = "GET";
  private URI uri = EMPTY_URI;
  private @Nullable HttpHeaders cachedHeaders;
  private @Nullable BodyPublisher bodyPublisher;
  private @MonotonicNonNull Duration timeout;
  private @MonotonicNonNull Version version;
  private boolean expectContinue;

  private MutableRequest() {}

  private MutableRequest(MutableRequest other) {
    tags.putAll(other.tags);
    headersBuilder.addAll(other.headersBuilder);
    method = other.method;
    uri = other.uri;
    cachedHeaders = other.cachedHeaders;
    bodyPublisher = other.bodyPublisher;
    expectContinue = other.expectContinue;
    // unnecessary checks to respect MonotonicNonNull's contract
    if (other.timeout != null) {
      timeout = other.timeout;
    }
    if (other.version != null) {
      version = other.version;
    }
  }

  /**
   * Sets this request's {@code URI}. Can be relative or without a host or a scheme.
   *
   * @throws IllegalArgumentException if the uri's syntax is invalid
   */
  public MutableRequest uri(String uri) {
    requireNonNull(uri);
    return uri(URI.create(uri));
  }

  /** Removes all headers added so far. */
  public MutableRequest removeHeaders() {
    cachedHeaders = null;
    headersBuilder.clear();
    return this;
  }

  /** Removes any header associated with the given name. */
  public MutableRequest removeHeader(String name) {
    requireNonNull(name);
    if (headersBuilder.remove(name)) {
      cachedHeaders = null;
    }
    return this;
  }

  /** Removes all headers matched by the given predicate. */
  public MutableRequest removeHeadersIf(BiPredicate<String, String> filter) {
    requireNonNull(filter);
    if (headersBuilder.removeIf(filter)) {
      cachedHeaders = null;
    }
    return this;
  }

  /** Adds each of the given {@code HttpHeaders}. */
  public MutableRequest headers(HttpHeaders headers) {
    requireNonNull(headers);
    cachedHeaders = null;
    for (var entry : headers.map().entrySet()) {
      var name = entry.getKey();
      validateHeaderName(name);
      for (var value : entry.getValue()) {
        validateHeaderValue(value);
        headersBuilder.add(name, value);
      }
    }
    return this;
  }

  /** Sets the {@code Cache-Control} header. */
  public MutableRequest cacheControl(CacheControl cacheControl) {
    requireNonNull(cacheControl);
    return header("Cache-Control", cacheControl.toString());
  }

  /** Calls the given consumer against this request. */
  public MutableRequest apply(Consumer<? super MutableRequest> consumer) {
    requireNonNull(consumer);
    consumer.accept(this);
    return this;
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> Optional<T> tag(TypeRef<T> type) {
    requireNonNull(type);
    return Optional.ofNullable((T) tags.get(type));
  }

  @Override
  Map<TypeRef<?>, Object> tags() {
    return Map.copyOf(tags); // Make a defensive copy
  }

  @SuppressWarnings("unchecked")
  @Override
  public MutableRequest tag(Object value) {
    requireNonNull(value);
    return tag((Class<Object>) value.getClass(), value);
  }

  @Override
  public <T> MutableRequest tag(Class<T> type, T value) {
    return tag(TypeRef.from(type), value);
  }

  @Override
  public <T> MutableRequest tag(TypeRef<T> type, T value) {
    requireNonNull(type);
    requireNonNull(value);
    tags.put(type, value);
    return this;
  }

  @Override
  public MutableRequest removeTag(Class<?> type) {
    return removeTag(TypeRef.from(type));
  }

  @Override
  public MutableRequest removeTag(TypeRef<?> type) {
    requireNonNull(type);
    tags.remove(type);
    return this;
  }

  @Override
  public Optional<BodyPublisher> bodyPublisher() {
    return Optional.ofNullable(bodyPublisher);
  }

  @Override
  public String method() {
    return method;
  }

  @Override
  public Optional<Duration> timeout() {
    return Optional.ofNullable(timeout);
  }

  @Override
  public boolean expectContinue() {
    return expectContinue;
  }

  /**
   * {@inheritDoc}
   *
   * <p>An empty {@code URI} (without a scheme, path or a host) is returned if no {@code URI} was
   * previously set.
   */
  @Override
  public URI uri() {
    return uri;
  }

  @Override
  public Optional<Version> version() {
    return Optional.ofNullable(version);
  }

  @Override
  public HttpHeaders headers() {
    var headers = cachedHeaders;
    if (headers == null) {
      headers = headersBuilder.build();
      cachedHeaders = headers;
    }
    return headers;
  }

  /** Sets this request's {@code URI}. Can be relative or without a host or a scheme. */
  @Override
  public MutableRequest uri(URI uri) {
    this.uri = requireNonNull(uri);
    return this;
  }

  @Override
  public MutableRequest expectContinue(boolean enable) {
    expectContinue = enable;
    return this;
  }

  @Override
  public MutableRequest version(Version version) {
    this.version = requireNonNull(version);
    return this;
  }

  @Override
  public MutableRequest header(String name, String value) {
    requireNonNull(name);
    requireNonNull(value);
    validateHeader(name, value);
    cachedHeaders = null;
    headersBuilder.add(name, value);
    return this;
  }

  @Override
  public MutableRequest headers(String... headers) {
    requireNonNull(headers);
    int len = headers.length;
    requireArgument(len > 0 && len % 2 == 0, "illegal number of headers: %d", len);
    cachedHeaders = null;
    for (int i = 0; i < len; i += 2) {
      var name = headers[i];
      var value = headers[i + 1];
      requireNonNull(name);
      requireNonNull(value);
      validateHeader(name, value);
      headersBuilder.add(name, value);
    }
    return this;
  }

  @Override
  public MutableRequest timeout(Duration timeout) {
    requireNonNull(timeout);
    requirePositiveDuration(timeout);
    this.timeout = timeout;
    return this;
  }

  @Override
  public MutableRequest setHeader(String name, String value) {
    requireNonNull(name);
    requireNonNull(value);
    validateHeader(name, value);
    cachedHeaders = null;
    headersBuilder.set(name, value);
    return this;
  }

  @Override
  public MutableRequest GET() {
    return setMethod("GET", null);
  }

  @Override
  public MutableRequest POST(BodyPublisher bodyPublisher) {
    requireNonNull(bodyPublisher);
    return setMethod("POST", bodyPublisher);
  }

  @Override
  public MutableRequest PUT(BodyPublisher bodyPublisher) {
    requireNonNull(bodyPublisher);
    return setMethod("PUT", bodyPublisher);
  }

  @Override
  public MutableRequest DELETE() {
    return setMethod("DELETE", null);
  }

  @Override
  public MutableRequest method(String method, BodyPublisher bodyPublisher) {
    requireNonNull(method);
    requireNonNull(bodyPublisher);
    requireArgument(isValidToken(method), "illegal method name: '%s'", method);
    return setMethod(method, bodyPublisher);
  }

  /** Prefer {@link #toImmutableRequest()}. */
  @Override
  public TaggableRequest build() {
    return new ImmutableRequest(this);
  }

  /** Returns an immutable copy of this request. */
  public TaggableRequest toImmutableRequest() {
    return new ImmutableRequest(this);
  }

  /** Returns a copy of this request that is independent of this instance. */
  @Override
  public MutableRequest copy() {
    return new MutableRequest(this);
  }

  @Override
  public String toString() {
    return uri + " " + method;
  }

  private MutableRequest setMethod(String method, @Nullable BodyPublisher bodyPublisher) {
    this.method = method;
    this.bodyPublisher = bodyPublisher;
    return this;
  }

  private static MutableRequest createCopy(HttpRequest other) {
    return new MutableRequest()
        .uri(other.uri())
        .headers(other.headers())
        .setMethod(other.method(), other.bodyPublisher().orElse(null))
        .expectContinue(other.expectContinue())
        .apply(
            self -> {
              other.timeout().ifPresent(self::timeout);
              other.version().ifPresent(self::version);
              if (other instanceof TaggableRequest) {
                self.tags.putAll(((TaggableRequest) other).tags());
              }
            });
  }

  public static MutableRequest copyOf(HttpRequest other) {
    requireNonNull(other);
    return other instanceof MutableRequest ? ((MutableRequest) other).copy() : createCopy(other);
  }

  /** Returns a new {@code MutableRequest}. */
  public static MutableRequest create() {
    return new MutableRequest();
  }

  /** Returns a new {@code MutableRequest} with the given {@code URI}. */
  public static MutableRequest create(String uri) {
    return new MutableRequest().uri(uri);
  }

  /** Returns a new {@code MutableRequest} with the given {@code URI}. */
  public static MutableRequest create(URI uri) {
    return new MutableRequest().uri(uri);
  }

  /** Returns a new {@code MutableRequest} with the given {@code URI} and a GET method. */
  public static MutableRequest GET(String uri) {
    return new MutableRequest().uri(uri); // default is GET
  }

  /** Returns a new {@code MutableRequest} with the given {@code URI} and a GET method. */
  public static MutableRequest GET(URI uri) {
    return new MutableRequest().uri(uri); // default is GET
  }

  /** Returns a new {@code MutableRequest} with the given {@code URI} and a POST method. */
  public static MutableRequest POST(String uri, BodyPublisher bodyPublisher) {
    return new MutableRequest().uri(uri).POST(bodyPublisher);
  }

  /** Returns a new {@code MutableRequest} with the given {@code URI} and a POST method. */
  public static MutableRequest POST(URI uri, BodyPublisher bodyPublisher) {
    return new MutableRequest().uri(uri).POST(bodyPublisher);
  }

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  private static final class ImmutableRequest extends TaggableRequest {
    private final String method;
    private final URI uri;
    private final HttpHeaders headers;
    private final Optional<BodyPublisher> bodyPublisher;
    private final Optional<Duration> timeout;
    private final Optional<Version> version;
    private final boolean expectContinue;
    private final Map<TypeRef<?>, Object> tags;

    ImmutableRequest(MutableRequest other) {
      method = other.method;
      uri = other.uri;
      headers = other.headers();
      bodyPublisher = Optional.ofNullable(other.bodyPublisher);
      timeout = Optional.ofNullable(other.timeout);
      version = Optional.ofNullable(other.version);
      expectContinue = other.expectContinue;
      tags = Map.copyOf(other.tags); // Make an immutable/defensive copy
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> Optional<T> tag(TypeRef<T> type) {
      requireNonNull(type);
      return Optional.ofNullable((T) tags.get(type));
    }

    @Override
    Map<TypeRef<?>, Object> tags() {
      return tags;
    }

    @Override
    public String method() {
      return method;
    }

    @Override
    public URI uri() {
      return uri;
    }

    @Override
    public HttpHeaders headers() {
      return headers;
    }

    @Override
    public Optional<BodyPublisher> bodyPublisher() {
      return bodyPublisher;
    }

    @Override
    public boolean expectContinue() {
      return expectContinue;
    }

    @Override
    public Optional<Duration> timeout() {
      return timeout;
    }

    @Override
    public Optional<Version> version() {
      return version;
    }

    @Override
    public String toString() {
      return uri + " " + method;
    }
  }
}
