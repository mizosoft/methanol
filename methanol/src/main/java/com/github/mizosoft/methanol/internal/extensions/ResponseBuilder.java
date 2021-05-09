package com.github.mizosoft.methanol.internal.extensions;

import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static com.github.mizosoft.methanol.internal.Validate.requireState;

import com.github.mizosoft.methanol.CacheAwareResponse;
import com.github.mizosoft.methanol.CacheAwareResponse.CacheStatus;
import com.github.mizosoft.methanol.TrackedResponse;
import java.net.URI;
import java.net.http.HttpClient.Version;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import javax.net.ssl.SSLSession;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class ResponseBuilder<T> {
  private final HeadersBuilder headersBuilder = new HeadersBuilder();
  private int statusCode = -1;
  private @MonotonicNonNull URI uri;
  private @MonotonicNonNull Version version;
  private @MonotonicNonNull HttpRequest request;
  private @MonotonicNonNull Instant timeRequestSent;
  private @MonotonicNonNull Instant timeResponseReceived;
  private @Nullable Object body;
  private @Nullable SSLSession sslSession;
  private @Nullable HttpResponse<T> previousResponse;
  private @Nullable TrackedResponse<?> networkResponse;
  private @Nullable TrackedResponse<?> cacheResponse;
  private @MonotonicNonNull CacheStatus cacheStatus;

  public ResponseBuilder() {}

  public ResponseBuilder<T> statusCode(int statusCode) {
    this.statusCode = statusCode;
    return this;
  }

  public ResponseBuilder<T> uri(URI uri) {
    this.uri = uri;
    return this;
  }

  public ResponseBuilder<T> version(Version version) {
    this.version = version;
    return this;
  }

  public ResponseBuilder<T> header(String name, String value) {
    headersBuilder.add(name, value);
    return this;
  }

  public ResponseBuilder<T> setHeader(String name, String value) {
    headersBuilder.set(name, value);
    return this;
  }

  public ResponseBuilder<T> headers(HttpHeaders headers) {
    headersBuilder.addAll(headers.map());
    return this;
  }

  public ResponseBuilder<T> setHeaders(HttpHeaders headers) {
    headersBuilder.clear();
    headersBuilder.addAll(headers.map());
    return this;
  }

  public ResponseBuilder<T> removeHeader(String name) {
    headersBuilder.remove(name);
    return this;
  }

  public ResponseBuilder<T> request(HttpRequest request) {
    this.request = request;
    return this;
  }

  public ResponseBuilder<T> timeRequestSent(Instant timeRequestSent) {
    this.timeRequestSent = timeRequestSent;
    return this;
  }

  public ResponseBuilder<T> timeResponseReceived(Instant timeResponseReceived) {
    this.timeResponseReceived = timeResponseReceived;
    return this;
  }

  @SuppressWarnings("unchecked")
  public <U> ResponseBuilder<U> body(@Nullable U body) {
    this.body = body;
    return (ResponseBuilder<U>) this;
  }

  public ResponseBuilder<T> dropBody() {
    return body(null);
  }

  public ResponseBuilder<T> sslSession(@Nullable SSLSession sslSession) {
    this.sslSession = sslSession;
    return this;
  }

  public ResponseBuilder<T> previousResponse(@Nullable HttpResponse<T> previousResponse) {
    this.previousResponse = previousResponse;
    return this;
  }

  public ResponseBuilder<T> networkResponse(@Nullable TrackedResponse<?> networkResponse) {
    this.networkResponse = networkResponse;
    return this;
  }

  public ResponseBuilder<T> cacheResponse(@Nullable TrackedResponse<?> cacheResponse) {
    this.cacheResponse = cacheResponse;
    return this;
  }

  public ResponseBuilder<T> cacheStatus(CacheStatus cacheStatus) {
    this.cacheStatus = cacheStatus;
    return this;
  }

  public ResponseBuilder<T> apply(Consumer<ResponseBuilder<?>> consumer) {
    consumer.accept(this);
    return this;
  }

  @SuppressWarnings("unchecked")
  public HttpResponse<T> build() {
    requireState(statusCode > 0, "statusCode is required");
    if (cacheStatus != null) {
      return buildCacheAware();
    }
    if (timeRequestSent != null && timeResponseReceived != null) {
      return buildTracked();
    }
    return new HttpResponseImpl<>(
        statusCode,
        ensureSet(uri, "uri"),
        ensureSet(version, "version"),
        headersBuilder.build(),
        ensureSet(request, "request"),
        (T) body,
        sslSession,
        previousResponse);
  }

  @SuppressWarnings("unchecked")
  public TrackedResponse<T> buildTracked() {
    if (cacheStatus != null) {
      return buildCacheAware();
    }
    return new TrackedResponseImpl<>(
        statusCode,
        ensureSet(uri, "uri"),
        ensureSet(version, "version"),
        headersBuilder.build(),
        ensureSet(request, "request"),
        (T) body,
        sslSession,
        previousResponse,
        ensureSet(timeRequestSent, "timeRequestSent"),
        ensureSet(timeResponseReceived, "timeResponseReceived"));
  }

  @SuppressWarnings("unchecked")
  private CacheAwareResponse<T> buildCacheAware() {
    requireState(statusCode > 0, "statusCode is required");
    return new CacheAwareResponseImpl<>(
        statusCode,
        ensureSet(uri, "uri"),
        ensureSet(version, "version"),
        headersBuilder.build(),
        ensureSet(request, "request"),
        (T) body,
        sslSession,
        previousResponse,
        ensureSet(timeRequestSent, "timeRequestSent"),
        ensureSet(timeResponseReceived, "timeResponseReceived"),
        networkResponse,
        cacheResponse,
        ensureSet(cacheStatus, "cacheStatus"));
  }

  public static <T> ResponseBuilder<T> newBuilder(HttpResponse<T> response) {
    var builder =
        new ResponseBuilder<T>()
            .statusCode(response.statusCode())
            .uri(response.uri())
            .version(response.version())
            .headers(response.headers())
            .request(response.request())
            .body(response.body());
    response.previousResponse().ifPresent(builder::previousResponse);
    response.sslSession().ifPresent(builder::sslSession);
    if (response instanceof TrackedResponse<?>) {
      var trackedResponse = ((TrackedResponse<?>) response);
      builder
          .timeRequestSent(trackedResponse.timeRequestSent())
          .timeResponseReceived(trackedResponse.timeResponseReceived());
    }
    if (response instanceof CacheAwareResponse<?>) {
      var cacheAwareResponse = (CacheAwareResponse<?>) response;
      builder
          .networkResponse(cacheAwareResponse.networkResponse().orElse(null))
          .cacheResponse(cacheAwareResponse.cacheResponse().orElse(null))
          .cacheStatus(cacheAwareResponse.cacheStatus());
    }
    return builder;
  }

  private static <T> T ensureSet(T property, String name) {
    requireState(property != null, "%s is required", name);
    return castNonNull(property);
  }

  private static class HttpResponseImpl<T> implements HttpResponse<T> {
    private final int statusCode;
    private final URI uri;
    private final Version version;
    private final HttpHeaders headers;
    private final HttpRequest request;
    private final @Nullable T body;
    private final @Nullable SSLSession sslSession;
    private final @Nullable HttpResponse<T> previousResponse;

    HttpResponseImpl(
        int statusCode,
        URI uri,
        Version version,
        HttpHeaders headers,
        HttpRequest request,
        @Nullable T body,
        @Nullable SSLSession sslSession,
        @Nullable HttpResponse<T> previousResponse) {
      this.statusCode = statusCode;
      this.uri = uri;
      this.version = version;
      this.headers = headers;
      this.request = request;
      this.body = body;
      this.sslSession = sslSession;
      this.previousResponse = previousResponse;
    }

    @Override
    public int statusCode() {
      return statusCode;
    }

    @Override
    public HttpRequest request() {
      return request;
    }

    @Override
    public Optional<HttpResponse<T>> previousResponse() {
      return Optional.ofNullable(previousResponse);
    }

    @Override
    public HttpHeaders headers() {
      return headers;
    }

    @Override
    public T body() {
      return body;
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.ofNullable(sslSession);
    }

    @Override
    public URI uri() {
      return uri;
    }

    @Override
    public Version version() {
      return version;
    }

    @Override
    public String toString() {
      return '(' + request.method() + " " + request.uri() + ") " + statusCode;
    }
  }

  private static class TrackedResponseImpl<T> extends HttpResponseImpl<T>
      implements TrackedResponse<T> {
    private final Instant timeRequestSent;
    private final Instant timeResponseReceived;

    TrackedResponseImpl(
        int statusCode,
        URI uri,
        Version version,
        HttpHeaders headers,
        HttpRequest request,
        @Nullable T body,
        @Nullable SSLSession sslSession,
        @Nullable HttpResponse<T> previousResponse,
        Instant timeRequestSent,
        Instant timeResponseReceived) {
      super(statusCode, uri, version, headers, request, body, sslSession, previousResponse);
      this.timeRequestSent = timeRequestSent;
      this.timeResponseReceived = timeResponseReceived;
    }

    @Override
    public Instant timeRequestSent() {
      return timeRequestSent;
    }

    @Override
    public Instant timeResponseReceived() {
      return timeResponseReceived;
    }
  }

  private static final class CacheAwareResponseImpl<T> extends TrackedResponseImpl<T>
      implements CacheAwareResponse<T> {
    private final @Nullable TrackedResponse<?> networkResponse;
    private final @Nullable TrackedResponse<?> cacheResponse;
    private final CacheStatus cacheStatus;

    CacheAwareResponseImpl(
        int statusCode,
        URI uri,
        Version version,
        HttpHeaders headers,
        HttpRequest request,
        @Nullable T body,
        @Nullable SSLSession sslSession,
        @Nullable HttpResponse<T> previousResponse,
        Instant timeRequestSent,
        Instant timeResponseReceived,
        @Nullable TrackedResponse<?> networkResponse,
        @Nullable TrackedResponse<?> cacheResponse,
        CacheStatus cacheStatus) {
      super(
          statusCode,
          uri,
          version,
          headers,
          request,
          body,
          sslSession,
          previousResponse,
          timeRequestSent,
          timeResponseReceived);
      this.networkResponse = networkResponse;
      this.cacheResponse = cacheResponse;
      this.cacheStatus = cacheStatus;
    }

    @Override
    public Optional<TrackedResponse<?>> networkResponse() {
      return Optional.ofNullable(networkResponse);
    }

    @Override
    public Optional<TrackedResponse<?>> cacheResponse() {
      return Optional.ofNullable(cacheResponse);
    }

    @Override
    public CacheStatus cacheStatus() {
      return cacheStatus;
    }
  }
}
