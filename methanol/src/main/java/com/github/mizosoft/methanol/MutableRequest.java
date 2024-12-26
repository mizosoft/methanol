/*
 * Copyright (c) 2024 Moataz Hussein
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

import com.github.mizosoft.methanol.BodyAdapter.Hints;
import com.github.mizosoft.methanol.internal.extensions.HeadersBuilder;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.net.URI;
import java.net.http.HttpClient.Version;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
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
 * <p>{@code MutableRequest} accepts an arbitrary object as the request body, referred to as the
 * payload. The payload is resolved into a {@code BodyPublisher} only when {@link
 * MutableRequest#bodyPublisher() one is requested}. Resolution is done by this request's {@link
 * #adapterCodec(AdapterCodec) AdapterCodec}. Sending the request through a {@link Methanol} client
 * with an {@code AdapterCodec} sets the request's {@code AdapterCodec} automatically if one is not
 * already present. Note that a request with an {@code AdapterCodec} overrides the client's {@code
 * AdapterCodec} both for encoding the request body and decoding the response body.
 *
 * <p>Additionally, this class allows setting a {@code URI} without a host or a scheme or not
 * setting a {@code URI} at all. This is for the case when the request is used with a {@link
 * Methanol} client that has a base URL, against which this request's URL is resolved.
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
public final class MutableRequest extends TaggableRequest
    implements TaggableRequest.Builder, HeadersAccumulator<MutableRequest>, MimeAwareRequest {
  private static final URI EMPTY_URI = URI.create("");

  private final Map<TypeRef<?>, Object> tags = new HashMap<>();
  private final HeadersBuilder headersBuilder = new HeadersBuilder();
  private final Hints.Builder hintsBuilder = Hints.newBuilder();

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
    headersBuilder.addAll(other.headersBuilder);
    tags.putAll(other.tags);
    hintsBuilder.putAll(other.hintsBuilder);

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
    // Make a defensive copy.
    return Map.copyOf(tags);
  }

  @Override
  @CanIgnoreReturnValue
  public MutableRequest tag(Object value) {
    return tag(TypeRef.ofRuntimeType(value), value);
  }

  @Override
  @CanIgnoreReturnValue
  public <T> MutableRequest tag(Class<T> type, T value) {
    return tag(TypeRef.of(type), value);
  }

  @Override
  @CanIgnoreReturnValue
  public <T> MutableRequest tag(TypeRef<T> typeRef, T value) {
    tags.put(typeRef, requireNonNull(value));
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

  /** Modifies this request's {@link Hints} by mutating a {@link Hints.Builder}. */
  public MutableRequest hints(Consumer<Hints.Builder> hintsMutator) {
    hintsMutator.accept(hintsBuilder);
    return this;
  }

  /** Adds the given value to this request's {@link Hints}. */
  public <T> MutableRequest hint(Class<T> type, T value) {
    hintsBuilder.put(type, value);
    return this;
  }

  @Override
  public Optional<BodyPublisher> bodyPublisher() {
    return Optional.ofNullable(body)
        .map(
            body ->
                resolve(
                    this, body, adapterCodec != null ? adapterCodec : AdapterCodec.installed()));
  }

  /** Set's the {@link AdapterCodec} to be used for resolving this request's payload. */
  @CanIgnoreReturnValue
  public MutableRequest adapterCodec(AdapterCodec adapterCodec) {
    this.adapterCodec = requireNonNull(adapterCodec);
    if (body instanceof UnresolvedBody) {
      // Invalidate resolved BodyPublisher, if any, to be resolved by the new codec.
      ((UnresolvedBody<?>) body).unresolve();
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

  @Override
  public Hints hints() {
    return hintsBuilder.build();
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
    headersBuilder.addAll(headers);
    cachedHeaders = null;
    return this;
  }

  /** Adds each of the given {@code HttpHeaders}. */
  @Override
  @CanIgnoreReturnValue
  public MutableRequest headers(HttpHeaders headers) {
    headersBuilder.addAll(headers);
    cachedHeaders = null;
    return this;
  }

  @Override
  @CanIgnoreReturnValue
  public MutableRequest setHeader(String name, String value) {
    headersBuilder.set(name, value);
    cachedHeaders = null;
    return this;
  }

  @Override
  public MutableRequest setHeader(String name, List<String> values) {
    headersBuilder.set(name, values);
    cachedHeaders = null;
    return this;
  }

  @Override
  public MutableRequest setHeaderIfAbsent(String name, String value) {
    headersBuilder.setIfAbsent(name, value);
    cachedHeaders = null;
    return this;
  }

  @Override
  public MutableRequest setHeaderIfAbsent(String name, List<String> values) {
    headersBuilder.setIfAbsent(name, values);
    cachedHeaders = null;
    return this;
  }

  /** Removes all headers added so far. */
  @Override
  @CanIgnoreReturnValue
  public MutableRequest removeHeaders() {
    headersBuilder.clear();
    cachedHeaders = null;
    return this;
  }

  /** Removes any header associated with the given name. */
  @Override
  @CanIgnoreReturnValue
  public MutableRequest removeHeader(String name) {
    if (headersBuilder.remove(name)) {
      cachedHeaders = null;
    }
    return this;
  }

  /** Removes all headers matched by the given predicate. */
  @Override
  @CanIgnoreReturnValue
  public MutableRequest removeHeadersIf(BiPredicate<String, String> filter) {
    if (headersBuilder.removeIf(filter)) {
      cachedHeaders = null;
    }
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
  public MutableRequest GET() {
    return setMethod("GET", null);
  }

  // @Override
  @SuppressWarnings("Since15")
  @CanIgnoreReturnValue
  public MutableRequest HEAD() {
    return setMethod("HEAD", null);
  }

  @Override
  @CanIgnoreReturnValue
  public MutableRequest POST(BodyPublisher bodyPublisher) {
    return setMethod("POST", requireNonNull(bodyPublisher));
  }

  /**
   * Sets the request method to POST and sets the payload to the given value. The media type defines
   * the format used for resolving the payload into a {@code BodyPublisher}.
   */
  @CanIgnoreReturnValue
  public MutableRequest POST(Object payload, MediaType mediaType) {
    return POST(payload, TypeRef.ofRuntimeType(payload), mediaType);
  }

  /**
   * Sets the request method to POST and sets the payload to the given value with an explicitly
   * specified type. The media type defines the format used for resolving the payload into a {@code
   * BodyPublisher}.
   */
  @CanIgnoreReturnValue
  public <T> MutableRequest POST(T payload, TypeRef<T> typeRef, MediaType mediaType) {
    return setMethod("POST", toMimeBody(payload, typeRef, mediaType));
  }

  @Override
  @CanIgnoreReturnValue
  public MutableRequest PUT(BodyPublisher bodyPublisher) {
    return setMethod("PUT", requireNonNull(bodyPublisher));
  }

  /**
   * Sets the request method to PUT and sets the payload to the given value. The media type defines
   * the format used for resolving the payload into a {@code BodyPublisher}.
   */
  @CanIgnoreReturnValue
  public MutableRequest PUT(Object payload, MediaType mediaType) {
    return PUT(payload, TypeRef.ofRuntimeType(payload), mediaType);
  }

  /**
   * Sets the request method to PUT and sets the payload to the given value with an explicitly
   * specified type. The media type defines the format used for resolving the payload into a {@code
   * BodyPublisher}.
   */
  @CanIgnoreReturnValue
  public <T> MutableRequest PUT(T payload, TypeRef<T> typeRef, MediaType mediaType) {
    return setMethod("PUT", toMimeBody(payload, typeRef, mediaType));
  }

  /** Sets the request method to PATCH and sets the body publisher to the given value. */
  @CanIgnoreReturnValue
  public MutableRequest PATCH(BodyPublisher bodyPublisher) {
    return setMethod("PATCH", requireNonNull(bodyPublisher));
  }

  /**
   * Sets the request method to PATCH and sets the payload to the given value. The media type
   * defines the format used for resolving the payload into a {@code BodyPublisher}.
   */
  @CanIgnoreReturnValue
  public MutableRequest PATCH(Object payload, MediaType mediaType) {
    return PATCH(payload, TypeRef.ofRuntimeType(payload), mediaType);
  }

  /**
   * Sets the request method to PATCH and sets the payload to the given value with an explicitly
   * specified type. The media type defines the format used for resolving the payload into a {@code
   * BodyPublisher}.
   */
  @CanIgnoreReturnValue
  public <T> MutableRequest PATCH(T payload, TypeRef<T> typeRef, MediaType mediaType) {
    return setMethod("PATCH", toMimeBody(payload, typeRef, mediaType));
  }

  @Override
  @CanIgnoreReturnValue
  public MutableRequest DELETE() {
    return setMethod("DELETE", null);
  }

  @Override
  @CanIgnoreReturnValue
  public MutableRequest method(String method, BodyPublisher bodyPublisher) {
    requireArgument(isValidToken(method), "Illegal method name: '%s'", method);
    return setMethod(method, requireNonNull(bodyPublisher));
  }

  /**
   * Sets the request method and sets the payload to the given value. The media type defines the
   * format used for resolving this payload into a {@code BodyPublisher}.
   */
  @CanIgnoreReturnValue
  public MutableRequest method(String method, Object payload, MediaType mediaType) {
    return method(method, payload, TypeRef.ofRuntimeType(payload), mediaType);
  }

  /**
   * Sets the request method and sets the payload to the given value with an explicitly specified
   * type. The media type defines the format used for resolving this payload into a {@code
   * BodyPublisher}.
   */
  @CanIgnoreReturnValue
  public <T> MutableRequest method(
      String method, T payload, TypeRef<T> typeRef, MediaType mediaType) {
    requireArgument(isValidToken(method), "Illegal method name: '%s'", method);
    return setMethod(method, toMimeBody(payload, typeRef, mediaType));
  }

  private <T> MimeBody toMimeBody(T payload, TypeRef<T> typeRef, MediaType mediaType) {
    return payload instanceof BodyPublisher
        ? MoreBodyPublishers.ofMediaType((BodyPublisher) payload, mediaType)
        : new UnresolvedBody<>(payload, typeRef, mediaType);
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

  @SuppressWarnings("ClassEscapesDefinedScope") // Function belongs to a package-private interface.
  @Override
  public Optional<MimeBody> mimeBody() {
    return body instanceof MimeBody ? Optional.of((MimeBody) body) : Optional.empty();
  }

  @CanIgnoreReturnValue
  private MutableRequest setMethod(String method, @Nullable Object body) {
    this.method = requireNonNull(method);
    this.body = body;
    return this;
  }

  private static BodyPublisher resolve(
      TaggableRequest request, Object body, AdapterCodec adapterCodec) {
    if (body instanceof BodyPublisher) {
      return (BodyPublisher) body;
    } else if (body instanceof UnresolvedBody) {
      return ((UnresolvedBody<?>) body).resolve(request, adapterCodec);
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
                var taggableOther = (TaggableRequest) other;
                self.tags.putAll(taggableOther.tags());
                self.hintsBuilder.putAll(taggableOther.hints());
              }

              if (other instanceof ImmutableRequest) {
                var immutableOther = (ImmutableRequest) other;
                immutableOther.adapterCodec.ifPresent(self::adapterCodec);
                self.setMethod(immutableOther.method, immutableOther.body.orElse(null));
              } else {
                self.setMethod(other.method(), other.bodyPublisher().orElse(null));
              }
            });
  }

  static Optional<AdapterCodec> adapterCodecOf(HttpRequest request) {
    if (request instanceof MutableRequest) {
      return ((MutableRequest) request).adapterCodec();
    } else if (request instanceof ImmutableRequest) {
      return ((ImmutableRequest) request).adapterCodec;
    } else {
      return Optional.empty();
    }
  }

  /** Returns a new {@code MutableRequest}. */
  public static MutableRequest create() {
    return new MutableRequest();
  }

  /** Returns a new {@code MutableRequest} with the given URI and a default GET method. */
  public static MutableRequest create(String uri) {
    return new MutableRequest().uri(uri);
  }

  /** Returns a new {@code MutableRequest} with the given URI and a default GET method. */
  public static MutableRequest create(URI uri) {
    return new MutableRequest().uri(uri);
  }

  /** Returns a new {@code MutableRequest} with the given URI and a GET method. */
  public static MutableRequest GET(String uri) {
    return new MutableRequest().uri(uri); // default is GET
  }

  /** Returns a new {@code MutableRequest} with the given URI and a GET method. */
  public static MutableRequest GET(URI uri) {
    return new MutableRequest().uri(uri); // default is GET
  }

  /** Returns a new {@code MutableRequest} with the given URI and a HEAD method. */
  public static MutableRequest HEAD(String uri) {
    return new MutableRequest().uri(uri).HEAD();
  }

  /** Returns a new {@code MutableRequest} with the given URI and a HEAD method. */
  public static MutableRequest HEAD(URI uri) {
    return new MutableRequest().uri(uri).HEAD();
  }

  /** Returns a new {@code MutableRequest} with the given URI and a DELETE method. */
  public static MutableRequest DELETE(String uri) {
    return new MutableRequest().uri(uri).DELETE();
  }

  /** Returns a new {@code MutableRequest} with the given URI and a DELETE method. */
  public static MutableRequest DELETE(URI uri) {
    return new MutableRequest().uri(uri).DELETE();
  }

  /** Returns a new {@code MutableRequest} with the given URI and a POST method. */
  public static MutableRequest POST(String uri, BodyPublisher bodyPublisher) {
    return new MutableRequest().uri(uri).POST(bodyPublisher);
  }

  /** Returns a new {@code MutableRequest} with the given URI and a POST method. */
  public static MutableRequest POST(URI uri, BodyPublisher bodyPublisher) {
    return new MutableRequest().uri(uri).POST(bodyPublisher);
  }

  /** Returns a new {@code MutableRequest} with the given URI and a POST method. */
  public static MutableRequest POST(String uri, Object payload, MediaType mediaType) {
    return new MutableRequest().uri(uri).POST(payload, mediaType);
  }

  /** Returns a new {@code MutableRequest} with the given URI and a POST method. */
  public static MutableRequest POST(URI uri, Object payload, MediaType mediaType) {
    return new MutableRequest().uri(uri).POST(payload, mediaType);
  }

  /** Returns a new {@code MutableRequest} with the given URI and a POST method. */
  public static <T> MutableRequest POST(
      String uri, T payload, TypeRef<T> typeRef, MediaType mediaType) {
    return new MutableRequest().uri(uri).POST(payload, typeRef, mediaType);
  }

  /** Returns a new {@code MutableRequest} with the given URI and a POST method. */
  public static <T> MutableRequest POST(
      URI uri, T payload, TypeRef<T> typeRef, MediaType mediaType) {
    return new MutableRequest().uri(uri).POST(payload, typeRef, mediaType);
  }

  /** Returns a new {@code MutableRequest} with the given URI and a PUT method. */
  public static MutableRequest PUT(String uri, BodyPublisher bodyPublisher) {
    return new MutableRequest().uri(uri).PUT(bodyPublisher);
  }

  /** Returns a new {@code MutableRequest} with the given URI and a PUT method. */
  public static MutableRequest PUT(URI uri, BodyPublisher bodyPublisher) {
    return new MutableRequest().uri(uri).PUT(bodyPublisher);
  }

  /** Returns a new {@code MutableRequest} with the given URI and a PUT method. */
  public static MutableRequest PUT(String uri, Object payload, MediaType mediaType) {
    return new MutableRequest().uri(uri).PUT(payload, mediaType);
  }

  /** Returns a new {@code MutableRequest} with the given URI and a PUT method. */
  public static MutableRequest PUT(URI uri, Object payload, MediaType mediaType) {
    return new MutableRequest().uri(uri).PUT(payload, mediaType);
  }

  /** Returns a new {@code MutableRequest} with the given URI and a PUT method. */
  public static <T> MutableRequest PUT(
      String uri, T payload, TypeRef<T> typeRef, MediaType mediaType) {
    return new MutableRequest().uri(uri).PUT(payload, typeRef, mediaType);
  }

  /** Returns a new {@code MutableRequest} with the given URI and a PUT method. */
  public static <T> MutableRequest PUT(
      URI uri, T payload, TypeRef<T> typeRef, MediaType mediaType) {
    return new MutableRequest().uri(uri).PUT(payload, typeRef, mediaType);
  }

  /** Returns a new {@code MutableRequest} with the given URI and a PATCH method. */
  public static MutableRequest PATCH(String uri, BodyPublisher bodyPublisher) {
    return new MutableRequest().uri(uri).PATCH(bodyPublisher);
  }

  /** Returns a new {@code MutableRequest} with the given URI and a PATCH method. */
  public static MutableRequest PATCH(URI uri, BodyPublisher bodyPublisher) {
    return new MutableRequest().uri(uri).PATCH(bodyPublisher);
  }

  /** Returns a new {@code MutableRequest} with the given URI and a PATCH method. */
  public static MutableRequest PATCH(String uri, Object payload, MediaType mediaType) {
    return new MutableRequest().uri(uri).PATCH(payload, mediaType);
  }

  /** Returns a new {@code MutableRequest} with the given URI and a PATCH method. */
  public static MutableRequest PATCH(URI uri, Object payload, MediaType mediaType) {
    return new MutableRequest().uri(uri).PATCH(payload, mediaType);
  }

  /** Returns a new {@code MutableRequest} with the given URI and a PATCH method. */
  public static <T> MutableRequest PATCH(
      String uri, T payload, TypeRef<T> typeRef, MediaType mediaType) {
    return new MutableRequest().uri(uri).PATCH(payload, typeRef, mediaType);
  }

  /** Returns a new {@code MutableRequest} with the given URI and a PATCH method. */
  public static <T> MutableRequest PATCH(
      URI uri, T payload, TypeRef<T> typeRef, MediaType mediaType) {
    return new MutableRequest().uri(uri).PATCH(payload, typeRef, mediaType);
  }

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  private static final class ImmutableRequest extends TaggableRequest implements MimeAwareRequest {
    final String method;
    final URI uri;
    final HttpHeaders headers;

    /** See {@link MutableRequest#body}. */
    final Optional<?> body;

    final Optional<Duration> timeout;
    final Optional<Version> version;
    final Optional<AdapterCodec> adapterCodec;
    final boolean expectContinue;
    final Map<TypeRef<?>, Object> tags;
    final Hints hints;

    ImmutableRequest(MutableRequest other) {
      method = other.method;
      uri = other.uri;
      headers = other.headers();
      body = Optional.ofNullable(other.body);
      timeout = Optional.ofNullable(other.timeout);
      version = Optional.ofNullable(other.version);
      adapterCodec = Optional.ofNullable(other.adapterCodec);
      expectContinue = other.expectContinue;
      tags = other.tags();
      hints = other.hints();
    }

    @Override
    Map<TypeRef<?>, Object> tags() {
      return tags;
    }

    @Override
    public Hints hints() {
      return hints;
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
      return body.map(body -> resolve(this, body, adapterCodec.orElseGet(AdapterCodec::installed)));
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

    @SuppressWarnings("unchecked")
    @Override
    public Optional<MimeBody> mimeBody() {
      return (Optional<MimeBody>) body.filter(MimeBody.class::isInstance);
    }
  }

  private static final class UnresolvedBody<T> implements MimeBody {
    private final T payload;
    private final TypeRef<T> typeRef;
    private final MediaType mediaType;
    private @Nullable BodyPublisher resolvedBodyPublisher;

    UnresolvedBody(T payload, TypeRef<T> typeRef, MediaType mediaType) {
      this.typeRef = requireNonNull(typeRef);
      this.payload = requireNonNull(payload);
      this.mediaType = requireNonNull(mediaType);
    }

    BodyPublisher resolve(TaggableRequest request, AdapterCodec adapterCodec) {
      var bodyPublisher = resolvedBodyPublisher;
      if (bodyPublisher == null) {
        bodyPublisher =
            adapterCodec.publisherOf(
                payload, typeRef, request.hints().mutate().forEncoder(request).build());
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
