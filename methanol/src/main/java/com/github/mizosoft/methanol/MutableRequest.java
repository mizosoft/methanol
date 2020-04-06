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

import static com.github.mizosoft.methanol.internal.Utils.TOKEN_MATCHER;
import static com.github.mizosoft.methanol.internal.Utils.validateHeader;
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.net.http.HttpClient.Version;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A mutable {@code HttpRequest}. This class implements {@link HttpRequest.Builder} for setting the
 * request's fields. Querying a field before it's been set will return it's default value. Invoking
 * the {@link #build} method will return an immutable {@code HttpRequest} copy that is independent
 * from this instance.
 *
 * <p>{@code MutableRequest} adds some convenience when the {@code HttpRequest} is used immediately
 * after creation:
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
public final class MutableRequest extends HttpRequest
    implements HttpRequest.Builder, Consumable<MutableRequest> {

  private static final String GET = "GET";
  private static final String POST = "POST";
  private static final String PUT = "PUT";
  private static final String DELETE = "DELETE";

  private static final URI EMPTY_URI = URI.create("");

  private final HeadersBuilder headersBuilder;
  private String method;
  private URI uri;
  private @Nullable HttpHeaders cachedHeaders;
  private @Nullable BodyPublisher bodyPublisher;
  private @MonotonicNonNull Duration timeout;
  private @MonotonicNonNull Version version;
  private boolean expectContinue;

  private MutableRequest() {
    headersBuilder = new HeadersBuilder();
    method = GET;
    uri = EMPTY_URI;
  }

  // for copy()
  private MutableRequest(MutableRequest other) {
    headersBuilder = other.headersBuilder.deepCopy();
    method = other.method;
    uri = other.uri;
    cachedHeaders = other.cachedHeaders;
    bodyPublisher = other.bodyPublisher;
    timeout = other.timeout;
    version = other.version;
    expectContinue = other.expectContinue;
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
    cachedHeaders = null; // invalidated
    headersBuilder.removeAll();
    return this;
  }

  /** Removes any header associated with the given name. */
  public MutableRequest removeHeader(String name) {
    if (headersBuilder.removeHeader(name)) {
      cachedHeaders = null; // invalidated
    }
    return this;
  }

  @Override
  public MutableRequest apply(Consumer<? super MutableRequest> consumer) {
    consumer.accept(this);
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
   * An empty {@code URI} (without a scheme, path or a host) is returned if no {@code URI} was
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
    HttpHeaders headers = cachedHeaders;
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
    validateHeader(name, value);
    cachedHeaders = null; // invalidated
    headersBuilder.addHeader(name, value);
    return this;
  }

  @Override
  public MutableRequest headers(String... headers) {
    requireNonNull(headers, "headers");
    int len = headers.length;
    requireArgument(len > 0 && len % 2 == 0, "illegal number of headers: %d", len);
    cachedHeaders = null; // invalidated
    for (int i = 0; i < len; i += 2) {
      String name = headers[i];
      String value = headers[i + 1];
      validateHeader(name, value);
      headersBuilder.addHeader(name, value);
    }
    return this;
  }

  @Override
  public MutableRequest timeout(Duration timeout) {
    requireNonNull(timeout);
    requireArgument(
        !(timeout.isNegative() || timeout.isZero()),
        "non-positive duration: %s", timeout);
    this.timeout = timeout;
    return this;
  }

  @Override
  public MutableRequest setHeader(String name, String value) {
    validateHeader(name, value);
    cachedHeaders = null; // invalidated
    headersBuilder.setHeader(name, value);
    return this;
  }

  @Override
  public MutableRequest GET() {
    method = "GET";
    bodyPublisher = null; // invalidated
    return this;
  }

  @Override
  public MutableRequest POST(BodyPublisher bodyPublisher) {
    return method(POST, bodyPublisher);
  }

  @Override
  public MutableRequest PUT(BodyPublisher bodyPublisher) {
    return method(PUT, bodyPublisher);
  }

  @Override
  public MutableRequest DELETE() {
    method = DELETE;
    bodyPublisher = null; // invalidated
    return this;
  }

  @Override
  public MutableRequest method(String method, BodyPublisher bodyPublisher) {
    requireNonNull(method, "method");
    requireNonNull(bodyPublisher, "bodyPublisher");
    requireArgument(
        !method.isEmpty() && TOKEN_MATCHER.allMatch(method),
        "illegal method string: '%s'", method);
    this.method = method;
    this.bodyPublisher = bodyPublisher;
    return this;
  }

  @Override
  public HttpRequest build() {
    return new ImmutableHttpRequest(this);
  }

  /** Returns a copy of this request that is independent from this instance. */
  @Override
  public MutableRequest copy() {
    return new MutableRequest(this);
  }

  @Override
  public String toString() {
    return uri.toString() + " " + method;
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

  static final class HeadersBuilder {

    private final Map<String, List<String>> headersMap;

    HeadersBuilder() {
      headersMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    }

    void addHeader(String name, String value) {
      headersMap.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
    }

    void setHeader(String name, String value) {
      headersMap.replace(name, new ArrayList<>(List.of(value)));
    }

    boolean removeHeader(String name) {
      return headersMap.remove(name) != null;
    }

    void removeAll() {
      headersMap.clear();
    }

    HeadersBuilder deepCopy() {
      var copy = new HeadersBuilder();
      headersMap.forEach((n, vs) -> copy.headersMap.put(n, new ArrayList<>(vs)));
      return copy;
    }

    HttpHeaders build() {
      return HttpHeaders.of(headersMap, (n, v) -> true);
    }
  }

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  private static final class ImmutableHttpRequest extends HttpRequest {

    private final String method;
    private final URI uri;
    private final HttpHeaders headers;
    private final Optional<BodyPublisher> bodyPublisher;
    private final Optional<Duration> timeout;
    private final Optional<Version> version;
    private final boolean expectContinue;

    ImmutableHttpRequest(MutableRequest other) {
      method = other.method;
      uri = other.uri;
      headers = other.headers();
      bodyPublisher = Optional.ofNullable(other.bodyPublisher);
      timeout = Optional.ofNullable(other.timeout);
      version = Optional.ofNullable(other.version);
      expectContinue = other.expectContinue;
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
      return uri.toString() + " " + method;
    }
  }
}
