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

import static com.github.mizosoft.methanol.internal.Utils.validateHeader;
import static com.github.mizosoft.methanol.internal.Utils.validateHeaderValue;
import static com.github.mizosoft.methanol.internal.Utils.validateTimeout;
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.BodyDecoder.Factory;
import com.github.mizosoft.methanol.MutableRequest.HeadersBuilder;
import com.github.mizosoft.methanol.internal.extensions.HttpResponsePublisher;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An {@code HttpClient} with request decoration and reactive extensions.
 *
 * <p>In addition to implementing the {@link HttpClient} interface, this class also allows to:
 *
 * <ul>
 *   <li>Specify a {@link BaseBuilder#baseUri(URI) base URI}.
 *   <li>Specify a default {@link HttpRequest#timeout() request timeout}.
 *   <li>Add a set of default HTTP headers for inclusion in requests if absent.
 *   <li>{@link BaseBuilder#autoAcceptEncoding(boolean) Transparent} response decompression.
 *   <li>Get {@code Publisher<HttpResponse<T>>} for asynchronous requests.
 * </ul>
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public final class Methanol extends HttpClient {

  private final HttpClient client;
  private final Optional<String> userAgent;
  private final Optional<URI> baseUri;
  private final Optional<Duration> requestTimeout;
  private final HttpHeaders defaultHeaders;
  private final boolean autoAcceptEncoding;

  private Methanol(BaseBuilder<?> builder) {
    client = builder.buildDelegateClient();
    userAgent = Optional.ofNullable(builder.userAgent);
    baseUri = Optional.ofNullable(builder.baseUri);
    requestTimeout = Optional.ofNullable(builder.requestTimeout);
    defaultHeaders = builder.headersBuilder.build();
    autoAcceptEncoding = builder.autoAcceptEncoding;
  }

  /**
   * Returns a {@code Publisher} for the {@code HttpResponse<T>} resulting from asynchronously
   * sending the given request.
   */
  public <T> Publisher<HttpResponse<T>> exchange(HttpRequest request, BodyHandler<T> handler) {
    requireNonNull(request, "request");
    requireNonNull(handler, "handler");
    return new HttpResponsePublisher<>(
        client,
        decorateRequest(request),
        decorateHandler(handler),
        null,
        this::decorateHandler,
        executor().orElse(FlowSupport.SYNC_EXECUTOR));
  }

  /**
   * Returns a {@code Publisher} for the sequence of {@code HttpResponse<T>} resulting from
   * asynchronously sending the given request along with accepting incoming {@link
   * PushPromiseHandler push promises} using the given {@code Function}. The function accepts an
   * incoming push promise by returning a non-{@code null} {@code BodyHandler<T>} for handling the
   * pushed response body. If a {@code null} handler is returned, the push promise will be rejected.
   *
   * <p>Note that the published sequence has no specific order, and hence the main response is not
   * guaranteed to be the first and may appear anywhere in the sequence.
   */
  public <T> Publisher<HttpResponse<T>> exchange(
      HttpRequest request,
      BodyHandler<T> handler,
      Function<HttpRequest, @Nullable BodyHandler<T>> pushPromiseAcceptor) {
    requireNonNull(request, "request");
    requireNonNull(handler, "handler");
    requireNonNull(pushPromiseAcceptor, "pushPromiseAcceptor");
    return new HttpResponsePublisher<>(
        client,
        decorateRequest(request),
        decorateHandler(handler),
        pushPromiseAcceptor,
        this::decorateHandler,
        executor().orElse(FlowSupport.SYNC_EXECUTOR));
  }

  /** Returns the underlying {@code HttpClient} used for sending requests. */
  public HttpClient underlyingClient() {
    return client;
  }

  /** Returns this client's {@code User-Agent}. */
  public Optional<String> userAgent() {
    return userAgent;
  }

  /** Returns this client's base {@code URI}. */
  public Optional<URI> baseUri() {
    return baseUri;
  }

  /** Returns the default request timeout used when not set in an {@code HttpRequest}. */
  public Optional<Duration> requestTimeout() {
    return requestTimeout;
  }

  /** Returns this client's default headers. */
  public HttpHeaders defaultHeaders() {
    return defaultHeaders;
  }

  /** Returns this client's {@link Builder#autoAcceptEncoding auto Accept-Encoding} setting. */
  public boolean autoAcceptEncoding() {
    return autoAcceptEncoding;
  }

  @Override
  public Optional<CookieHandler> cookieHandler() {
    return client.cookieHandler();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    return client.connectTimeout();
  }

  @Override
  public Redirect followRedirects() {
    return client.followRedirects();
  }

  @Override
  public Optional<ProxySelector> proxy() {
    return client.proxy();
  }

  @Override
  public SSLContext sslContext() {
    return client.sslContext();
  }

  @Override
  public SSLParameters sslParameters() {
    return client.sslParameters();
  }

  @Override
  public Optional<Authenticator> authenticator() {
    return client.authenticator();
  }

  @Override
  public Version version() {
    return client.version();
  }

  @Override
  public Optional<Executor> executor() {
    return client.executor();
  }

  @Override
  public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> handler)
      throws IOException, InterruptedException {
    requireNonNull(request, "request");
    requireNonNull(handler, "handler");
    return client.send(decorateRequest(request), decorateHandler(handler));
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, BodyHandler<T> handler) {
    requireNonNull(request, "request");
    requireNonNull(handler, "handler");
    return client.sendAsync(decorateRequest(request), decorateHandler(handler));
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      BodyHandler<T> handler,
      @Nullable PushPromiseHandler<T> pushPromiseHandler) {
    requireNonNull(request, "request");
    requireNonNull(handler, "responseBodyHandler");
    // HttpClient allows null pushPromiseHandler
    return client.sendAsync(
        decorateRequest(request),
        decorateHandler(handler),
        pushPromiseHandler != null ? decoratePushPromiseHandler(pushPromiseHandler) : null);
  }

  private HttpRequest decorateRequest(HttpRequest request) {
    MutableRequest decoratedRequest = MutableRequest.copyOf(request);
    HttpRequest originalRequest =
        request instanceof MutableRequest ? ((MutableRequest) request).build() : request;
    addRequestDecorations(originalRequest, decoratedRequest);
    return decoratedRequest;
  }

  private void addRequestDecorations(HttpRequest original, HttpRequest.Builder builder) {
    // resolve with base URI
    if (baseUri.isPresent()) {
      URI resolvedUri = baseUri.get().resolve(original.uri());
      validateUri(resolvedUri);
      builder.uri(resolvedUri);
    }

    // add default headers without overwriting
    var originalHeadersMap = original.headers().map();
    var defaultHeadersMap = defaultHeaders.map();
    for (var entry : defaultHeadersMap.entrySet()) {
      String headerName = entry.getKey();
      if (!originalHeadersMap.containsKey(headerName)) {
        entry.getValue().forEach(v -> builder.header(headerName, v));
      }
    }

    // add Accept-Encoding without overwriting if enabled
    if (autoAcceptEncoding
        && !originalHeadersMap.containsKey("Accept-Encoding")
        && !defaultHeadersMap.containsKey("Accept-Encoding")) {
      Set<String> supportedEncodings = BodyDecoder.Factory.installedBindings().keySet();
      if (!supportedEncodings.isEmpty()) {
        builder.header("Accept-Encoding", String.join(", ", supportedEncodings));
      }
    }

    // overwrite Content-Type if request body is MimeBodyPublisher
    original
        .bodyPublisher()
        .filter(MimeBodyPublisher.class::isInstance)
        .map(body -> ((MimeBodyPublisher) body).mediaType())
        .ifPresent(mt -> builder.setHeader("Content-Type", mt.toString()));

    // add default timeout if not already present
    if (original.timeout().isEmpty()) {
      requestTimeout.ifPresent(builder::timeout);
    }
  }

  private <T> BodyHandler<T> decorateHandler(BodyHandler<T> baseHandler) {
    return autoAcceptEncoding ? MoreBodyHandlers.decoding(baseHandler) : baseHandler;
  }

  /** Uses {@link #decorateHandler(BodyHandler)} for each accepted push promise. */
  private <T> PushPromiseHandler<T> decoratePushPromiseHandler(PushPromiseHandler<T> base) {
    return (initial, push, acceptor) ->
        base.applyPushPromise(initial, push, handler -> acceptor.apply(decorateHandler(handler)));
  }

  private static void validateUri(URI uri) {
    String scheme = uri.getScheme();
    requireArgument(scheme != null, "uri has no scheme: %s", uri);
    scheme = scheme.toLowerCase(Locale.ENGLISH);
    requireArgument(
        "http".equals(scheme) || "https".equals(scheme), "unsupported scheme: %s", scheme);
    requireArgument(uri.getHost() != null, "uri has no host: %s", uri);
  }

  /** Returns a new {@link Methanol.Builder}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** Returns a new {@link Methanol.WithClientBuilder} that uses the given client. */
  public static WithClientBuilder newBuilder(HttpClient client) {
    return new WithClientBuilder(client);
  }

  /** Creates a default {@code Methanol} instance. */
  public static Methanol create() {
    return newBuilder().build();
  }

  /** A base {@code Methanol} builder allowing to set the non-standard properties. */
  public abstract static class BaseBuilder<B extends BaseBuilder<B>> {

    final HeadersBuilder headersBuilder;
    @Nullable String userAgent;
    @Nullable URI baseUri;
    @Nullable Duration requestTimeout;
    boolean autoAcceptEncoding;

    BaseBuilder() {
      headersBuilder = new HeadersBuilder();
      autoAcceptEncoding = true;
    }

    /** Calls the given consumer against this builder. */
    public final B apply(Consumer<? super B> consumer) {
      consumer.accept(self());
      return self();
    }

    /** Sets a default {@code User-Agent} header to use when sending requests. */
    public B userAgent(String userAgent) {
      validateHeaderValue(userAgent);
      this.userAgent = userAgent;
      headersBuilder.setHeader("User-Agent", userAgent); // overwrite previous if any
      return self();
    }

    /**
     * Sets the base {@code URI} with which each outgoing requests' {@code URI} is {@link
     * URI#resolve(URI) resolved. */
    public B baseUri(String uri) {
      return baseUri(URI.create(uri));
    }

    /**
     * Sets the base {@code URI} with which each outgoing requests' {@code URI} is {@link
     * URI#resolve(URI) resolved}.
     */
    public B baseUri(URI uri) {
      requireNonNull(uri);
      validateUri(uri);
      this.baseUri = uri;
      return self();
    }

    /** Adds the given header as default. */
    public B defaultHeader(String name, String value) {
      validateHeader(name, value);
      if (name.equalsIgnoreCase("User-Agent")) {
        userAgent = value;
      }
      headersBuilder.addHeader(name, value);
      return self();
    }

    /** Adds each of the given headers as default. */
    public B defaultHeaders(String... headers) {
      requireNonNull(headers, "headers");
      int len = headers.length;
      requireArgument(len > 0 && len % 2 == 0, "illegal number of headers: %d", len);
      for (int i = 0; i < len; i += 2) {
        defaultHeader(headers[i], headers[i + 1]);
      }
      return self();
    }

    /** Sets a default request timeout to use when not explicitly by an {@code HttpRequest}. */
    public B requestTimeout(Duration requestTimeout) {
      validateTimeout(requestTimeout);
      this.requestTimeout = requestTimeout;
      return self();
    }

    /**
     * If enabled, each request will have an {@code Accept-Encoding} header appended the value of
     * which is the set of {@link Factory#installedBindings() supported encodings}. Additionally,
     * each received response will be transparently decompressed by wrapping it's {@code
     * BodyHandler} with {@link MoreBodyHandlers#decoding(BodyHandler)}.
     *
     * <p>The default value of this setting is {@code true}.
     */
    public B autoAcceptEncoding(boolean autoAcceptEncoding) {
      this.autoAcceptEncoding = autoAcceptEncoding;
      return self();
    }

    /** Returns a new {@code Methanol} with a snapshot of the current builder's state. */
    public Methanol build() {
      return new Methanol(this);
    }

    abstract B self();

    abstract HttpClient buildDelegateClient();
  }

  /** A builder for {@code Methanol} instances with a predefined {@code HttpClient}. */
  public static final class WithClientBuilder extends BaseBuilder<WithClientBuilder> {

    private final HttpClient delegate;

    WithClientBuilder(HttpClient delegate) {
      this.delegate = requireNonNull(delegate);
    }

    @Override
    WithClientBuilder self() {
      return this;
    }

    @Override
    HttpClient buildDelegateClient() {
      return delegate;
    }
  }

  /**
   * A builder of {@code Methanol} instances allowing to configure the underlying {@code
   * HttpClient}.
   */
  public static final class Builder extends BaseBuilder<Builder> implements HttpClient.Builder {

    private final HttpClient.Builder delegateBuilder;

    Builder() {
      delegateBuilder = HttpClient.newBuilder();
    }

    @Override
    public Builder cookieHandler(CookieHandler cookieHandler) {
      delegateBuilder.cookieHandler(cookieHandler);
      return this;
    }

    @Override
    public Builder connectTimeout(Duration duration) {
      delegateBuilder.connectTimeout(duration);
      return this;
    }

    @Override
    public Builder sslContext(SSLContext sslContext) {
      delegateBuilder.sslContext(sslContext);
      return this;
    }

    @Override
    public Builder sslParameters(SSLParameters sslParameters) {
      delegateBuilder.sslParameters(sslParameters);
      return this;
    }

    @Override
    public Builder executor(Executor executor) {
      delegateBuilder.executor(executor);
      return this;
    }

    @Override
    public Builder followRedirects(Redirect policy) {
      delegateBuilder.followRedirects(policy);
      return this;
    }

    @Override
    public Builder version(Version version) {
      delegateBuilder.version(version);
      return this;
    }

    @Override
    public Builder priority(int priority) {
      delegateBuilder.priority(priority);
      return this;
    }

    @Override
    public Builder proxy(ProxySelector proxySelector) {
      delegateBuilder.proxy(proxySelector);
      return this;
    }

    @Override
    public Builder authenticator(Authenticator authenticator) {
      delegateBuilder.authenticator(authenticator);
      return this;
    }

    @Override
    Builder self() {
      return this;
    }

    @Override
    HttpClient buildDelegateClient() {
      return delegateBuilder.build();
    }
  }
}
