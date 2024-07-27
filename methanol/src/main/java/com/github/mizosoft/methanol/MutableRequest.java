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

import static com.github.mizosoft.methanol.internal.Utils.isValidToken;
import static com.github.mizosoft.methanol.internal.Utils.requirePositiveDuration;
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.extensions.HeadersBuilder;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
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
 * A mutable {@link HttpRequest} that supports {@link TaggableRequest tags}, relative URIs {@code &}
 * setting arbitrary objects as request bodies. This class implements {@link HttpRequest.Builder}
 * for setting request fields. Querying a field before it's been set will return its default value.
 * Invoking {@link #toImmutableRequest()} will return an immutable copy that is independent of this
 * instance.
 *
 * <p>{@code MutableRequest} accepts an arbitrary object, referred to as payloads, as the request
 * body. The payload is resolved into a {@code BodyPublisher} only when {@link
 * MutableRequest#bodyPublisher() one is requested}. Resolution is done by this request's {@link
 * #adapterCodec(AdapterCodec) AdapterCodec}.
 *
 * <p>Additionally, this class allows setting a {@code URI} without a host or a scheme or not
 * setting a {@code URI} at all. This is for the case when the request is used with a {@link
 * Methanol} client that has a base URL, with which this request's URL is resolved.
 *
 * <p>{@code MutableRequest} also adds some convenience when the {@code HttpRequest} is used
 * immediately after creation:
 *
 * <pre>{@code
 * client.send(
 *     MutableRequest
 *         .GET("https://www.google.com/search?q=java")
 *         .header("Accept", "text/html"),
 *     BodyHandlers.ofString());
 * }</pre>
 *
 * It is recommended, however, to use {@link #toImmutableRequest()} if the request is stored
 * somewhere before it's sent in order to prevent accidental mutation, especially when the request
 * is sent asynchronously.
 */
public final class MutableRequest extends TaggableRequest implements TaggableRequest.Builder {
  private static final URI EMPTY_URI = URI.create("");

  private final Map<TypeRef<?>, Object> tags = new HashMap<>();
  private final HeadersBuilder headersBuilder = new HeadersBuilder();

  private String method;
  private URI uri;

  /**
   * This request's body, which is either a {@link BodyPublisher} or an {@link UnresolvedBody}. The
   * latter is lazily resolved into the former on calling {@link #bodyPublisher()}.
   */
  private @Nullable Object body;

  private @MonotonicNonNull Duration timeout;
  private @MonotonicNonNull Version version;
  private boolean expectContinue;
  private @Nullable HttpHeaders cachedHeaders;

  /** The {@code AdapterCodec} for resolving this request's payload. */
  private @MonotonicNonNull AdapterCodec adapterCodec;

  private MutableRequest() {
    method = "GET";
    uri = EMPTY_URI;
  }

  private MutableRequest(MutableRequest other) {
    method = other.method;
    uri = other.uri;
    body = other.body;
    expectContinue = other.expectContinue;
    adapterCodec = other.adapterCodec;
    cachedHeaders = other.cachedHeaders;
    tags.putAll(other.tags);
    headersBuilder.addAll(other.headersBuilder);

    // Unnecessary checks to respect MonotonicNonNull's contract.
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
  @CanIgnoreReturnValue
  public MutableRequest uri(String uri) {
    return uri(URI.create(uri));
  }

  /** Removes all headers added so far. */
  @CanIgnoreReturnValue
  public MutableRequest removeHeaders() {
    headersBuilder.clear();
    cachedHeaders = null;
    return this;
  }

  /** Removes any header associated with the given name. */
  @CanIgnoreReturnValue
  public MutableRequest removeHeader(String name) {
    if (headersBuilder.remove(name)) {
      cachedHeaders = null;
    }
    return this;
  }

  /** Removes all headers matched by the given predicate. */
  @CanIgnoreReturnValue
  public MutableRequest removeHeadersIf(BiPredicate<String, String> filter) {
    if (headersBuilder.removeIf(filter)) {
      cachedHeaders = null;
    }
    return this;
  }

  /** Adds each of the given {@code HttpHeaders}. */
  @CanIgnoreReturnValue
  public MutableRequest headers(HttpHeaders headers) {
    headersBuilder.addAll(headers);
    cachedHeaders = null;
    return this;
  }

  /** Sets the {@code Cache-Control} header to the given value. */
  @CanIgnoreReturnValue
  public MutableRequest cacheControl(CacheControl cacheControl) {
    return setHeader("Cache-Control", cacheControl.toString());
  }

  /** Calls the given consumer against this request. */
  @CanIgnoreReturnValue
  public MutableRequest apply(Consumer<? super MutableRequest> consumer) {
    consumer.accept(this);
    return this;
  }

  @Override
  Map<TypeRef<?>, Object> tags() {
    return Map.copyOf(tags); // Make a defensive copy.
  }

  @SuppressWarnings("unchecked")
  @Override
  @CanIgnoreReturnValue
  public MutableRequest tag(Object value) {
    return tag((Class<Object>) value.getClass(), value);
  }

  @Override
  @CanIgnoreReturnValue
  public <T> MutableRequest tag(Class<T> type, T value) {
    return tag(TypeRef.of(type), value);
  }

  @Override
  @CanIgnoreReturnValue
  public <T> MutableRequest tag(TypeRef<T> typeRef, T value) {
    tags.put(requireNonNull(typeRef), requireNonNull(value));
    return this;
  }

  @Override
  @CanIgnoreReturnValue
  public MutableRequest removeTag(Class<?> type) {
    return removeTag(TypeRef.of(type));
  }

  @Override
  @CanIgnoreReturnValue
  public MutableRequest removeTag(TypeRef<?> typeRef) {
    tags.remove(requireNonNull(typeRef));
    return this;
  }

  @Override
  public Optional<BodyPublisher> bodyPublisher() {
    return Optional.ofNullable(body)
        .map(body -> resolve(body, adapterCodec != null ? adapterCodec : AdapterCodec.installed()));
  }

  /** Set's the {@link AdapterCodec} to be used for resolving this request's payload. */
  @CanIgnoreReturnValue
  public MutableRequest adapterCodec(AdapterCodec adapterCodec) {
    this.adapterCodec = requireNonNull(adapterCodec);
    if (body instanceof UnresolvedBody) {
      // Invalidate resolved BodyPublisher, if any, to be resolved by the new codec.
      ((UnresolvedBody) body).unresolve();
    }
    return this;
  }

  /** Returns the {@link AdapterCodec} to be used for resolving this request's payload. */
  public Optional<AdapterCodec> adapterCodec() {
    return Optional.ofNullable(adapterCodec);
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
  @CanIgnoreReturnValue
  public MutableRequest uri(URI uri) {
    this.uri = requireNonNull(uri);
    return this;
  }

  @Override
  @CanIgnoreReturnValue
  public MutableRequest expectContinue(boolean enable) {
    expectContinue = enable;
    return this;
  }

  @Override
  @CanIgnoreReturnValue
  public MutableRequest version(Version version) {
    this.version = requireNonNull(version);
    return this;
  }

  @Override
  @CanIgnoreReturnValue
  public MutableRequest header(String name, String value) {
    headersBuilder.add(name, value);
    cachedHeaders = null;
    return this;
  }

  @Override
  @CanIgnoreReturnValue
  public MutableRequest headers(String... headers) {
    requireArgument(
        headers.length > 0 && headers.length % 2 == 0,
        "illegal number of headers: %d",
        headers.length);
    for (int i = 0; i < headers.length; i += 2) {
      headersBuilder.add(headers[i], headers[i + 1]);
    }
    cachedHeaders = null;
    return this;
  }

  @Override
  @CanIgnoreReturnValue
  public MutableRequest timeout(Duration timeout) {
    this.timeout = requirePositiveDuration(timeout);
    return this;
  }

  @Override
  @CanIgnoreReturnValue
  public MutableRequest setHeader(String name, String value) {
    headersBuilder.set(name, value);
    cachedHeaders = null;
    return this;
  }

  @CanIgnoreReturnValue
  public MutableRequest setHeaders(HttpHeaders headers) {
    headersBuilder.setAll(headers);
    cachedHeaders = null;
    return this;
  }

  @Override
  @CanIgnoreReturnValue
  public MutableRequest GET() {
    return setMethod("GET", null);
  }

  @Override
  @CanIgnoreReturnValue
  public MutableRequest POST(BodyPublisher bodyPublisher) {
    return setMethod("POST", requireNonNull(bodyPublisher));
  }

  @CanIgnoreReturnValue
  public MutableRequest POST(Object payload, MediaType mediaType) {
    return setMethod("POST", toBody(payload, mediaType));
  }

  @Override
  @CanIgnoreReturnValue
  public MutableRequest PUT(BodyPublisher bodyPublisher) {
    return setMethod("PUT", requireNonNull(bodyPublisher));
  }

  /**
   * Sets the request method to PUT and sets the payload to the given value. The media type defines
   * the format used for resolving the payload.
   */
  @CanIgnoreReturnValue
  public MutableRequest PUT(Object payload, MediaType mediaType) {
    return setMethod("PUT", toBody(payload, mediaType));
  }

  /** Sets the request method to PATCH and sets the body publisher to the given value. */
  @CanIgnoreReturnValue
  public MutableRequest PATCH(BodyPublisher bodyPublisher) {
    return setMethod("PATCH", bodyPublisher);
  }

  /**
   * Sets the request method to PATCH and sets the payload to the given value. The media type
   * defines the format used for resolving the payload.
   */
  @CanIgnoreReturnValue
  public MutableRequest PATCH(Object payload, MediaType mediaType) {
    return setMethod("PATCH", toBody(payload, mediaType));
  }

  @Override
  @CanIgnoreReturnValue
  public MutableRequest DELETE() {
    return setMethod("DELETE", null);
  }

  @Override
  @CanIgnoreReturnValue
  public MutableRequest method(String method, BodyPublisher bodyPublisher) {
    requireArgument(isValidToken(method), "illegal method name: '%s'", method);
    return setMethod(method, requireNonNull(bodyPublisher));
  }

  /**
   * Sets the request method and sets the payload to the given value. The media type defines the
   * format used for resolving this payload.
   */
  @CanIgnoreReturnValue
  public MutableRequest method(String method, Object payload, MediaType mediaType) {
    requireArgument(isValidToken(method), "illegal method name: '%s'", method);
    return setMethod(method, toBody(payload, mediaType));
  }

  private Object toBody(Object payload, MediaType mediaType) {
    requireNonNull(payload);
    requireNonNull(mediaType);
    return payload instanceof BodyPublisher
        ? MoreBodyPublishers.ofMediaType((BodyPublisher) payload, mediaType)
        : new UnresolvedBody(payload, mediaType);
  }

  /** Returns an immutable copy of this request. Prefer using {@link #toImmutableRequest()}. */
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

  Optional<MediaType> bodyMediaType() {
    return Optional.ofNullable(body)
        .filter(MimeAware.class::isInstance)
        .map(body -> ((MimeAware) body).mediaType());
  }

  @CanIgnoreReturnValue
  private MutableRequest setMethod(String method, @Nullable Object body) {
    this.method = method;
    this.body = body;
    return this;
  }

  private static BodyPublisher resolve(Object body, AdapterCodec adapterCodec) {
    if (body instanceof BodyPublisher) {
      return (BodyPublisher) body;
    } else if (body instanceof UnresolvedBody) {
      return ((UnresolvedBody) body).resolve(adapterCodec);
    } else {
      throw new IllegalStateException("Unexpected request body: " + body);
    }
  }

  /** Returns a new {@code MutableRequest} that is a copy of the given request. */
  public static MutableRequest copyOf(HttpRequest other) {
    return other instanceof MutableRequest
        ? ((MutableRequest) other).copy()
        : copyOfForeignRequest(other);
  }

  private static MutableRequest copyOfForeignRequest(HttpRequest other) {
    assert !(other instanceof MutableRequest);
    return new MutableRequest()
        .uri(other.uri())
        .headers(other.headers())
        .expectContinue(other.expectContinue())
        .apply(
            self -> {
              other.timeout().ifPresent(self::timeout);
              other.version().ifPresent(self::version);

              if (other instanceof TaggableRequest) {
                self.tags.putAll(((TaggableRequest) other).tags());
              }

              if (other instanceof ImmutableRequest) {
                var immutableOther = (ImmutableRequest) other;
                immutableOther.adapterCodec().ifPresent(self::adapterCodec);
                immutableOther
                    .body()
                    .ifPresent(body -> self.setMethod(immutableOther.method(), body));
              } else {
                self.setMethod(other.method(), other.bodyPublisher().orElse(null));
              }
            });
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

  /** Returns a new {@code MutableRequest} with the given {@code URI} and a POST method. */
  public static MutableRequest POST(String uri, Object payload, MediaType mediaType) {
    return new MutableRequest().uri(uri).POST(payload, mediaType);
  }

  /** Returns a new {@code MutableRequest} with the given {@code URI} and a POST method. */
  public static MutableRequest POST(URI uri, Object payload, MediaType mediaType) {
    return new MutableRequest().uri(uri).POST(payload, mediaType);
  }

  /** Returns a new {@code MutableRequest} with the given {@code URI} and a PUT method. */
  public static MutableRequest PUT(String uri, BodyPublisher bodyPublisher) {
    return new MutableRequest().uri(uri).PUT(bodyPublisher);
  }

  /** Returns a new {@code MutableRequest} with the given {@code URI} and a PUT method. */
  public static MutableRequest PUT(URI uri, BodyPublisher bodyPublisher) {
    return new MutableRequest().uri(uri).PUT(bodyPublisher);
  }

  /** Returns a new {@code MutableRequest} with the given {@code URI} and a PUT method. */
  public static MutableRequest PUT(String uri, Object payload, MediaType mediaType) {
    return new MutableRequest().uri(uri).PUT(payload, mediaType);
  }

  /** Returns a new {@code MutableRequest} with the given {@code URI} and a PUT method. */
  public static MutableRequest PUT(URI uri, Object payload, MediaType mediaType) {
    return new MutableRequest().uri(uri).PUT(payload, mediaType);
  }

  /** Returns a new {@code MutableRequest} with the given {@code URI} and a PATCH method. */
  public static MutableRequest PATCH(String uri, BodyPublisher bodyPublisher) {
    return new MutableRequest().uri(uri).PATCH(bodyPublisher);
  }

  /** Returns a new {@code MutableRequest} with the given {@code URI} and a PATCH method. */
  public static MutableRequest PATCH(URI uri, BodyPublisher bodyPublisher) {
    return new MutableRequest().uri(uri).PATCH(bodyPublisher);
  }

  /** Returns a new {@code MutableRequest} with the given {@code URI} and a PATCH method. */
  public static MutableRequest PATCH(String uri, Object payload, MediaType mediaType) {
    return new MutableRequest().uri(uri).PATCH(payload, mediaType);
  }

  /** Returns a new {@code MutableRequest} with the given {@code URI} and a PATCH method. */
  public static MutableRequest PATCH(URI uri, Object payload, MediaType mediaType) {
    return new MutableRequest().uri(uri).PATCH(payload, mediaType);
  }

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  private static final class ImmutableRequest extends TaggableRequest {
    private final String method;
    private final URI uri;
    private final HttpHeaders headers;

    /** See {@link MutableRequest#body}. */
    private final Optional<Object> body;

    private final Optional<Duration> timeout;
    private final Optional<Version> version;
    private final Optional<AdapterCodec> adapterCodec;
    private final boolean expectContinue;
    private final Map<TypeRef<?>, Object> tags;

    ImmutableRequest(MutableRequest other) {
      method = other.method;
      uri = other.uri;
      headers = other.headers();
      body = Optional.ofNullable(other.body);
      timeout = Optional.ofNullable(other.timeout);
      version = Optional.ofNullable(other.version);
      adapterCodec = Optional.ofNullable(other.adapterCodec);
      expectContinue = other.expectContinue;
      tags = Map.copyOf(other.tags);
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
      return body.map(body -> resolve(body, adapterCodec.orElseGet(AdapterCodec::installed)));
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

    Optional<Object> body() {
      return body;
    }

    Optional<AdapterCodec> adapterCodec() {
      return adapterCodec;
    }
  }

  private static final class UnresolvedBody implements MimeAware {
    private final Object payload;
    private final MediaType mediaType;

    private @Nullable BodyPublisher resolvedBodyPublisher;

    UnresolvedBody(Object payload, MediaType mediaType) {
      this.payload = requireNonNull(payload);
      this.mediaType = requireNonNull(mediaType);
    }

    BodyPublisher resolve(AdapterCodec adapterCodec) {
      var bodyPublisher = resolvedBodyPublisher;
      if (bodyPublisher == null) {
        bodyPublisher = adapterCodec.publisherOf(payload, mediaType);
        resolvedBodyPublisher = bodyPublisher;
      }
      return bodyPublisher;
    }

    void unresolve() {
      resolvedBodyPublisher = null;
    }

    @Override
    public MediaType mediaType() {
      return mediaType;
    }
  }
}
