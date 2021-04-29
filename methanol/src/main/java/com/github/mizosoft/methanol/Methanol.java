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

import static com.github.mizosoft.methanol.internal.Utils.requirePositiveDuration;
import static com.github.mizosoft.methanol.internal.Utils.validateHeader;
import static com.github.mizosoft.methanol.internal.Utils.validateHeaderValue;
import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static java.util.Objects.requireNonNullElseGet;

import com.github.mizosoft.methanol.BodyDecoder.Factory;
import com.github.mizosoft.methanol.Methanol.Interceptor.Chain;
import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.extensions.HeadersBuilder;
import com.github.mizosoft.methanol.internal.extensions.HttpResponsePublisher;
import com.github.mizosoft.methanol.internal.extensions.ImmutableResponseInfo;
import com.github.mizosoft.methanol.internal.extensions.ResponseBuilder;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.function.Unchecked;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An {@code HttpClient} with interceptors, request decoration and reactive extensions.
 *
 * <p>In addition to implementing the {@link HttpClient} interface, this class allows to:
 *
 * <ul>
 *   <li>Specify a {@link BaseBuilder#baseUri(URI) base URI}.
 *   <li>Specify a default {@link HttpRequest#timeout() request timeout}.
 *   <li>Add a set of default HTTP headers for inclusion in requests if absent.
 *   <li>Add an {@link HttpCache HTTP caching} layer.
 *   <li>{@link BaseBuilder#autoAcceptEncoding(boolean) Transparent} response decompression.
 *   <li>Intercept requests and responses going through this client.
 *   <li>Get {@code Publisher<HttpResponse<T>>} for asynchronous requests.
 * </ul>
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public final class Methanol extends HttpClient {
  private final HttpClient baseClient;
  private final Redirect redirectPolicy;
  private final HttpHeaders defaultHeaders;
  private final Optional<HttpCache> cache;
  private final Optional<String> userAgent;
  private final Optional<URI> baseUri;
  private final Optional<Duration> requestTimeout;
  private final Optional<Duration> readTimeout;
  private final @Nullable ScheduledExecutorService readTimeoutScheduler;
  private final boolean autoAcceptEncoding;
  private final List<Interceptor> interceptors;
  private final List<Interceptor> networkInterceptors;

  private Methanol(BaseBuilder<?> builder) {
    baseClient = builder.buildBaseClient();
    redirectPolicy = requireNonNullElse(builder.redirectPolicy, baseClient.followRedirects());
    defaultHeaders = builder.headersBuilder.build();
    cache = Optional.ofNullable(builder.cache);
    userAgent = Optional.ofNullable(builder.userAgent);
    baseUri = Optional.ofNullable(builder.baseUri);
    requestTimeout = Optional.ofNullable(builder.requestTimeout);
    readTimeout = Optional.ofNullable(builder.readTimeout);
    readTimeoutScheduler = builder.readTimeoutScheduler;
    autoAcceptEncoding = builder.autoAcceptEncoding;
    interceptors = List.copyOf(builder.interceptors);
    networkInterceptors = List.copyOf(builder.networkInterceptors);
  }

  /**
   * Returns a {@code Publisher} for the {@code HttpResponse<T>} resulting from asynchronously
   * sending the given request.
   */
  public <T> Publisher<HttpResponse<T>> exchange(HttpRequest request, BodyHandler<T> bodyHandler) {
    requireNonNull(request, "request");
    requireNonNull(bodyHandler, "bodyHandler");
    return new HttpResponsePublisher<>(
        this, request, bodyHandler, null, executor().orElse(FlowSupport.SYNC_EXECUTOR));
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
      BodyHandler<T> bodyHandler,
      Function<HttpRequest, @Nullable BodyHandler<T>> pushPromiseAcceptor) {
    requireNonNull(request, "request");
    requireNonNull(bodyHandler, "bodyHandler");
    requireNonNull(pushPromiseAcceptor, "pushPromiseAcceptor");
    return new HttpResponsePublisher<>(
        this,
        request,
        bodyHandler,
        pushPromiseAcceptor,
        executor().orElse(FlowSupport.SYNC_EXECUTOR));
  }

  /** Returns the underlying {@code HttpClient} used for sending requests. */
  public HttpClient underlyingClient() {
    return baseClient;
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

  /**
   * Returns the {@link MoreBodySubscribers#withReadTimeout(BodySubscriber, Duration) read timeout}
   * used for each request.
   */
  public Optional<Duration> readTimeout() {
    return readTimeout;
  }

  /**
   * Returns an immutable list of this client's {@link BaseBuilder#interceptor(Interceptor)
   * interceptors}.
   */
  public List<Interceptor> interceptors() {
    return interceptors;
  }

  /**
   * Returns an immutable list of this client's {@link BaseBuilder#networkInterceptor(Interceptor)
   * network interceptors}.
   */
  public List<Interceptor> networkInterceptors() {
    return networkInterceptors;
  }

  /**
   * Returns the list of interceptors invoked after request decoration.
   *
   * @deprecated Use {@link #networkInterceptors()}
   */
  @Deprecated(since = "1.5.0")
  public List<Interceptor> postDecorationInterceptors() {
    return networkInterceptors;
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
    return baseClient.cookieHandler();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    return baseClient.connectTimeout();
  }

  @Override
  public Redirect followRedirects() {
    return redirectPolicy;
  }

  @Override
  public Optional<ProxySelector> proxy() {
    return baseClient.proxy();
  }

  @Override
  public SSLContext sslContext() {
    return baseClient.sslContext();
  }

  @Override
  public SSLParameters sslParameters() {
    return baseClient.sslParameters();
  }

  @Override
  public Optional<Authenticator> authenticator() {
    return baseClient.authenticator();
  }

  @Override
  public Version version() {
    return baseClient.version();
  }

  @Override
  public Optional<Executor> executor() {
    return baseClient.executor();
  }

  @Override
  public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> bodyHandler)
      throws IOException, InterruptedException {
    requireNonNull(request, "request");
    requireNonNull(bodyHandler, "bodyHandler");
    return InterceptorChain.sendWithInterceptors(
        baseClient, request, bodyHandler, buildInterceptorQueue());
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, BodyHandler<T> bodyHandler) {
    requireNonNull(request, "request");
    requireNonNull(bodyHandler, "bodyHandler");
    return InterceptorChain.sendAsyncWithInterceptors(
        baseClient, request, bodyHandler, null, buildInterceptorQueue());
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      BodyHandler<T> bodyHandler,
      @Nullable PushPromiseHandler<T> pushPromiseHandler) {
    requireNonNull(request, "request");
    requireNonNull(bodyHandler, "bodyHandler");
    return InterceptorChain.sendAsyncWithInterceptors(
        baseClient, request, bodyHandler, pushPromiseHandler, buildInterceptorQueue());
  }

  private List<Interceptor> buildInterceptorQueue() {
    var interceptors = new ArrayList<>(this.interceptors);
    interceptors.add(new RequestDecorationInterceptor(this));
    // Add redirect & cache interceptors if a cache is installed
    cache.ifPresent(
        cache -> {
          var executor = executor().orElse(null);
          interceptors.add(new RedirectingInterceptor(redirectPolicy, executor));
          interceptors.add(cache.interceptor(executor));
        });
    interceptors.addAll(networkInterceptors);
    return Collections.unmodifiableList(interceptors);
  }

  private static void validateUri(URI uri) {
    var scheme = uri.getScheme();
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

  /** An object that intercepts requests being sent over a {@code Methanol} client. */
  public interface Interceptor {

    /**
     * Intercepts given request and returns the resulting response, normally by forwarding to the
     * chain.
     */
    <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
        throws IOException, InterruptedException;

    /**
     * Intercepts the given request and returns a {@code CompletableFuture} for the resulting
     * response, normally by forwarding to the chain.
     */
    <T> CompletableFuture<HttpResponse<T>> interceptAsync(HttpRequest request, Chain<T> chain);

    /** Returns an interceptor that forwards the request after applying the given operator. */
    static Interceptor create(UnaryOperator<HttpRequest> decorator) {
      requireNonNull(decorator);
      return new Interceptor() {
        @Override
        public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
            throws IOException, InterruptedException {
          return chain.forward(decorator.apply(request));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
            HttpRequest request, Chain<T> chain) {
          return chain.forwardAsync(decorator.apply(request));
        }
      };
    }

    /**
     * An object that gives an interceptor the ability to relay requests to following interceptors,
     * till eventually being sent by the underlying {@code HttpClient}.
     *
     * @param <T> the response body type
     */
    interface Chain<T> {

      /** Returns the {@code BodyHandler} this chain uses for handling the response. */
      BodyHandler<T> bodyHandler();

      /** Returns the {@code PushPromiseHandler} this chain uses for handling push promises. */
      Optional<PushPromiseHandler<T>> pushPromiseHandler();

      /** Returns a new chain that uses the given {@code BodyHandler}. */
      Chain<T> withBodyHandler(BodyHandler<T> bodyHandler);

      /** Returns a new chain that uses the given {@code PushPromiseHandler}. */
      Chain<T> withPushPromiseHandler(@Nullable PushPromiseHandler<T> pushPromiseHandler);

      /** Returns a new chain that uses given handlers, possibly targeting another response type. */
      <U> Chain<U> with(
          BodyHandler<U> bodyHandler, @Nullable PushPromiseHandler<U> pushPromiseHandler);

      /**
       * Forwards the request to the next interceptor, or to the underlying {@code HttpClient} if
       * called by the last interceptor.
       */
      HttpResponse<T> forward(HttpRequest request) throws IOException, InterruptedException;

      /**
       * Forwards the request to the next interceptor, or asynchronously to the underlying {@code
       * HttpClient} if called by the last interceptor.
       */
      CompletableFuture<HttpResponse<T>> forwardAsync(HttpRequest request);
    }
  }

  /** A base {@code Methanol} builder allowing to set the non-standard properties. */
  public abstract static class BaseBuilder<B extends BaseBuilder<B>> {
    final HeadersBuilder headersBuilder;
    @MonotonicNonNull String userAgent;
    @MonotonicNonNull URI baseUri;
    @MonotonicNonNull Duration requestTimeout;
    @MonotonicNonNull Duration readTimeout;
    @MonotonicNonNull ScheduledExecutorService readTimeoutScheduler;
    boolean autoAcceptEncoding;

    // These fields are put here for convenience, they're only writable by Builder
    @MonotonicNonNull HttpCache cache;
    @MonotonicNonNull Redirect redirectPolicy;

    final List<Interceptor> interceptors = new ArrayList<>();
    final List<Interceptor> networkInterceptors = new ArrayList<>();

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
      headersBuilder.set("User-Agent", userAgent); // overwrite previous if any
      return self();
    }

    /**
     * Sets the base {@code URI} with which each outgoing requests' {@code URI} is {@link
     * URI#resolve(URI) resolved}.
     */
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
      if ("User-Agent".equalsIgnoreCase(name)) {
        userAgent = value;
      }
      headersBuilder.add(name, value);
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
      requirePositiveDuration(requestTimeout);
      this.requestTimeout = requestTimeout;
      return self();
    }

    /**
     * Sets a default {@link MoreBodySubscribers#withReadTimeout(BodySubscriber, Duration) read
     * timeout}. Timeout events are scheduled using a system-wide {@code ScheduledExecutorService}.
     */
    public B readTimeout(Duration readTimeout) {
      requirePositiveDuration(readTimeout);
      this.readTimeout = readTimeout;
      return self();
    }

    /**
     * Sets a default {@link MoreBodySubscribers#withReadTimeout(BodySubscriber, Duration,
     * ScheduledExecutorService) readtimeout} using the given {@code ScheduledExecutorService} for
     * scheduling timeout events.
     */
    public B readTimeout(Duration readTimeout, ScheduledExecutorService scheduler) {
      requirePositiveDuration(readTimeout);
      requireNonNull(scheduler);
      this.readTimeout = readTimeout;
      this.readTimeoutScheduler = scheduler;
      return self();
    }

    /**
     * If enabled, each request will have an {@code Accept-Encoding} header appended, the value of
     * which is the set of {@link Factory#installedBindings() supported encodings}. Additionally,
     * each received response will be transparently decompressed by wrapping its {@code BodyHandler}
     * with {@link MoreBodyHandlers#decoding(BodyHandler)}.
     *
     * <p>The default value of this setting is {@code true}.
     */
    public B autoAcceptEncoding(boolean autoAcceptEncoding) {
      this.autoAcceptEncoding = autoAcceptEncoding;
      return self();
    }

    /**
     * Adds an interceptor that is invoked right after the client receives a request. The
     * interceptor receives the request before it is decorated (its {@code URI} resolved with the
     * base {@code URI}, default headers added, etc...) or handled by the {@link HttpCache}, if one
     * is installed.
     */
    public B interceptor(Interceptor interceptor) {
      requireNonNull(interceptor);
      interceptors.add(interceptor);
      return self();
    }

    /**
     * Adds an interceptor that is invoked right before the request is forwarded to the underlying
     * {@code HttpClient}. The interceptor receives the request after it is handled by all {@link
     * #interceptor(Interceptor) non-network interceptors}, is decorated (its {@code URI} resolved
     * with the base {@code URI}, default headers added, etc...) and finally handled by the {@link
     * HttpCache}, if one is installed. This means that network interceptors aren't called if
     * network isn't used, normally due to the presence of an {@code HttpCache} that is capable of
     * serving a stored response.
     */
    public B networkInterceptor(Interceptor interceptor) {
      requireNonNull(interceptor);
      networkInterceptors.add(interceptor);
      return self();
    }

    /**
     * Adds an interceptor that is invoked after request decoration (i.e. after default properties
     * or {@code BodyHandler} transformations are applied).
     *
     * @deprecated Use {@link #networkInterceptor(Interceptor)}
     */
    @Deprecated(since = "1.5.0")
    public B postDecorationInterceptor(Interceptor interceptor) {
      return networkInterceptor(interceptor);
    }

    /** Returns a new {@code Methanol} with a snapshot of the current builder's state. */
    public Methanol build() {
      return new Methanol(this);
    }

    abstract B self();

    abstract HttpClient buildBaseClient();
  }

  /** A builder for {@code Methanol} instances with a predefined {@code HttpClient}. */
  public static final class WithClientBuilder extends BaseBuilder<WithClientBuilder> {
    private final HttpClient baseClient;

    WithClientBuilder(HttpClient baseClient) {
      this.baseClient = requireNonNull(baseClient);
    }

    @Override
    WithClientBuilder self() {
      return this;
    }

    @Override
    HttpClient buildBaseClient() {
      return baseClient;
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

    /** Sets the {@link HttpCache} to be used by the client. */
    public Builder cache(HttpCache cache) {
      super.cache = requireNonNull(cache);
      return this;
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
      // Don't apply policy to base client until build() is called to know whether
      // a RedirectingInterceptor is to be used instead in case a cache is installed.
      redirectPolicy = requireNonNull(policy);
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
    HttpClient buildBaseClient() {
      // Apply redirectPolicy if a cache is not set.
      // In such case we let the base client handle redirects.
      if (cache == null && redirectPolicy != null) {
        delegateBuilder.followRedirects(redirectPolicy);
      }
      return delegateBuilder.build();
    }
  }

  private static final class InterceptorChain<T> implements Interceptor.Chain<T> {
    private final HttpClient baseClient;
    private final BodyHandler<T> bodyHandler;
    private final @Nullable PushPromiseHandler<T> pushPromiseHandler;
    private final List<Interceptor> interceptors;
    private final int currentInterceptorIndex;

    private InterceptorChain(
        HttpClient baseClient,
        BodyHandler<T> bodyHandler,
        @Nullable PushPromiseHandler<T> pushPromiseHandler,
        List<Interceptor> interceptors) {
      this(baseClient, bodyHandler, pushPromiseHandler, interceptors, 0);
    }

    private InterceptorChain(
        HttpClient baseClient,
        BodyHandler<T> bodyHandler,
        @Nullable PushPromiseHandler<T> pushPromiseHandler,
        List<Interceptor> interceptors,
        int currentInterceptorIndex) {
      this.baseClient = baseClient;
      this.bodyHandler = bodyHandler;
      this.pushPromiseHandler = pushPromiseHandler;
      this.interceptors = interceptors;
      this.currentInterceptorIndex = currentInterceptorIndex;
    }

    @Override
    public BodyHandler<T> bodyHandler() {
      return bodyHandler;
    }

    @Override
    public Optional<PushPromiseHandler<T>> pushPromiseHandler() {
      return Optional.ofNullable(pushPromiseHandler);
    }

    @Override
    public Interceptor.Chain<T> withBodyHandler(BodyHandler<T> bodyHandler) {
      requireNonNull(bodyHandler);
      return new InterceptorChain<>(
          baseClient, bodyHandler, pushPromiseHandler, interceptors, currentInterceptorIndex);
    }

    @Override
    public Interceptor.Chain<T> withPushPromiseHandler(
        @Nullable PushPromiseHandler<T> pushPromiseHandler) {
      return new InterceptorChain<>(
          baseClient, bodyHandler, pushPromiseHandler, interceptors, currentInterceptorIndex);
    }

    @Override
    public <U> Chain<U> with(
        BodyHandler<U> bodyHandler, @Nullable PushPromiseHandler<U> pushPromiseHandler) {
      requireNonNull(bodyHandler);
      return new InterceptorChain<>(
          baseClient, bodyHandler, pushPromiseHandler, interceptors, currentInterceptorIndex);
    }

    @Override
    public HttpResponse<T> forward(HttpRequest request) throws IOException, InterruptedException {
      requireNonNull(request);
      if (currentInterceptorIndex >= interceptors.size()) {
        return baseClient.send(request, bodyHandler);
      }

      var interceptor = interceptors.get(currentInterceptorIndex);
      return requireNonNull(
          interceptor.intercept(request, copyForNextInterceptor()),
          () -> interceptor + "::intercept returned a null response");
    }

    @Override
    public CompletableFuture<HttpResponse<T>> forwardAsync(HttpRequest request) {
      requireNonNull(request);
      if (currentInterceptorIndex >= interceptors.size()) {
        // sendAsync accepts nullable pushPromiseHandler
        return baseClient.sendAsync(request, bodyHandler, pushPromiseHandler);
      }

      var interceptor = interceptors.get(currentInterceptorIndex);
      return interceptor
          .interceptAsync(request, copyForNextInterceptor())
          .thenApply(
              res ->
                  requireNonNull(
                      res, () -> interceptor + "::interceptAsync completed with a null response"));
    }

    private InterceptorChain<T> copyForNextInterceptor() {
      return new InterceptorChain<>(
          baseClient, bodyHandler, pushPromiseHandler, interceptors, currentInterceptorIndex + 1);
    }

    static <T> HttpResponse<T> sendWithInterceptors(
        HttpClient baseClient,
        HttpRequest request,
        BodyHandler<T> bodyHandler,
        List<Interceptor> interceptors)
        throws IOException, InterruptedException {
      return new InterceptorChain<>(baseClient, bodyHandler, null, interceptors).forward(request);
    }

    static <T> CompletableFuture<HttpResponse<T>> sendAsyncWithInterceptors(
        HttpClient baseClient,
        HttpRequest request,
        BodyHandler<T> bodyHandler,
        @Nullable PushPromiseHandler<T> pushPromiseHandler,
        List<Interceptor> interceptors) {
      return new InterceptorChain<>(baseClient, bodyHandler, pushPromiseHandler, interceptors)
          .forwardAsync(request);
    }
  }

  /** Applies client-configured decoration to each ongoing request. */
  private static final class RequestDecorationInterceptor implements Interceptor {
    private final Optional<URI> baseUri;
    private final Optional<Duration> requestTimeout;
    private final Optional<Duration> readTimeout;
    private final @Nullable ScheduledExecutorService readTimeoutScheduler;
    private final HttpHeaders defaultHeaders;
    private final boolean autoAcceptEncoding;

    RequestDecorationInterceptor(Methanol client) {
      baseUri = client.baseUri;
      requestTimeout = client.requestTimeout;
      readTimeout = client.readTimeout;
      readTimeoutScheduler = client.readTimeoutScheduler;
      defaultHeaders = client.defaultHeaders;
      autoAcceptEncoding = client.autoAcceptEncoding;
    }

    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
        throws IOException, InterruptedException {
      return decorateChain(chain).forward(decorateRequest(request));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
        HttpRequest request, Chain<T> chain) {
      return decorateChain(chain).forwardAsync(decorateRequest(request));
    }

    private <T> Chain<T> decorateChain(Chain<T> chain) {
      var decoratedChain = chain;
      if (autoAcceptEncoding || readTimeout.isPresent()) { // has body handler decoration
        decoratedChain = chain.withBodyHandler(decorateBodyHandler(chain.bodyHandler()));

        var pushPromiseHandler = chain.pushPromiseHandler();
        if (pushPromiseHandler.isPresent()) {
          decoratedChain =
              decoratedChain.withPushPromiseHandler(
                  decoratePushPromiseHandler(pushPromiseHandler.get()));
        }
      }
      return decoratedChain;
    }

    /** Uses {@link #decorateBodyHandler(BodyHandler)} for each accepted push promise. */
    private <T> PushPromiseHandler<T> decoratePushPromiseHandler(
        PushPromiseHandler<T> pushPromiseHandler) {
      return (initial, push, acceptor) ->
          pushPromiseHandler.applyPushPromise(
              initial, push, acceptor.compose(this::decorateBodyHandler));
    }

    private <T> BodyHandler<T> decorateBodyHandler(BodyHandler<T> bodyHandler) {
      var decoratedBodyHandler = bodyHandler;

      if (autoAcceptEncoding) {
        decoratedBodyHandler = MoreBodyHandlers.decoding(decoratedBodyHandler);
      }

      // timeout interceptor should to be "closer" to the HTTP client for better accuracy
      if (readTimeout.isPresent()) {
        decoratedBodyHandler =
            readTimeoutScheduler != null
                ? MoreBodyHandlers.withReadTimeout(
                    decoratedBodyHandler, readTimeout.get(), readTimeoutScheduler)
                : MoreBodyHandlers.withReadTimeout(decoratedBodyHandler, readTimeout.get());
      }

      return decoratedBodyHandler;
    }

    private HttpRequest decorateRequest(HttpRequest request) {
      var decoratedRequest = MutableRequest.copyOf(request);

      // resolve with base URI
      if (baseUri.isPresent()) {
        var resolvedUri = baseUri.get().resolve(request.uri());
        validateUri(resolvedUri);
        decoratedRequest.uri(resolvedUri);
      }

      var originalHeadersMap = request.headers().map();
      var defaultHeadersMap = defaultHeaders.map();

      // add default headers without overwriting
      defaultHeadersMap.entrySet().stream()
          .filter(e -> !originalHeadersMap.containsKey(e.getKey()))
          .forEach(e -> e.getValue().forEach(v -> decoratedRequest.header(e.getKey(), v)));

      // add Accept-Encoding without overwriting if enabled
      if (autoAcceptEncoding
          && !originalHeadersMap.containsKey("Accept-Encoding")
          && !defaultHeadersMap.containsKey("Accept-Encoding")) {
        var supportedEncodings = BodyDecoder.Factory.installedBindings().keySet();
        if (!supportedEncodings.isEmpty()) {
          decoratedRequest.header("Accept-Encoding", String.join(", ", supportedEncodings));
        }
      }

      // overwrite Content-Type if request body is MimeBodyPublisher
      request
          .bodyPublisher()
          .filter(MimeBodyPublisher.class::isInstance)
          .map(body -> ((MimeBodyPublisher) body).mediaType())
          .ifPresent(mediaType -> decoratedRequest.setHeader("Content-Type", mediaType.toString()));

      // add default timeout if not already present
      if (request.timeout().isEmpty()) {
        requestTimeout.ifPresent(decoratedRequest::timeout);
      }

      return decoratedRequest;
    }
  }

  /**
   * An {@link Interceptor} that follows redirects. The interceptor's behaviour follows that of the
   * HttpClient. The interceptor is applied prior to the cache interceptor, provided that one is
   * installed. Allowing the cache to intercept redirects increases its efficiency as network access
   * can be avoided in case a redirected URI is accessed repeatedly (provided the redirecting
   * response is cacheable). Additionally, this ensures correctness in case a cacheable response is
   * received for a redirected request. In such case, the response should be cached for the URI the
   * request was redirected to, not the initiating URI.
   */
  static final class RedirectingInterceptor implements Interceptor {
    private static final int DEFAULT_MAX_REDIRECTS = 5;
    private static final int MAX_REDIRECTS =
        Integer.getInteger("jdk.httpclient.redirects.retrylimit", DEFAULT_MAX_REDIRECTS);

    private final Redirect policy;

    /** The executor used for invoking the response handler. */
    private final Executor handlerExecutor;

    RedirectingInterceptor(Redirect policy, @Nullable Executor handlerExecutor) {
      this.policy = policy;
      // Use a cached thread-pool of daemon threads if we can't access HttpClient's executor
      this.handlerExecutor =
          requireNonNullElseGet(
              handlerExecutor,
              () ->
                  Executors.newCachedThreadPool(
                      runnable -> {
                        var thread = new Thread(runnable);
                        thread.setDaemon(true);
                        return thread;
                      }));
    }

    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
        throws IOException, InterruptedException {
      if (policy == Redirect.NEVER) {
        return chain.forward(request);
      }
      return Utils.block(doIntercept(request, chain, false));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
        HttpRequest request, Chain<T> chain) {
      if (policy == Redirect.NEVER) {
        return chain.forwardAsync(request);
      }
      return doIntercept(request, chain, true);
    }

    private <T> CompletableFuture<HttpResponse<T>> doIntercept(
        HttpRequest request, Chain<T> chain, boolean async) {
      var publisherChain = chain.with(BodyHandlers.ofPublisher(), screenPushPromiseHandler(chain));
      return new Redirector(request, new SendAdapter(publisherChain, async))
          .sendAndFollowUp()
          .thenApply(Redirector::result)
          .thenCompose(response -> handleAsync(response, chain.bodyHandler()));
    }

    // FIXME this code is identical to PublisherResponse::handlerAsync
    private <T> CompletableFuture<HttpResponse<T>> handleAsync(
        HttpResponse<Publisher<List<ByteBuffer>>> response, BodyHandler<T> handler) {
      var publisher = response.body();
      var subscriberFuture =
          CompletableFuture.supplyAsync(
              () -> handler.apply(ImmutableResponseInfo.from(response)), handlerExecutor);
      subscriberFuture.thenAcceptAsync(publisher::subscribe, handlerExecutor);
      return subscriberFuture
          .thenComposeAsync(BodySubscriber::getBody, handlerExecutor)
          .thenApply(body -> ResponseBuilder.newBuilder(response).body(body).build());
    }

    /**
     * Returns a publisher-based {@code PushPromiseHandler} that invokes the handler with the
     * correct response type as specified by the interceptor chain.
     */
    private <T> @Nullable PushPromiseHandler<Publisher<List<ByteBuffer>>> screenPushPromiseHandler(
        Chain<T> chain) {
      return chain
          .pushPromiseHandler()
          .<PushPromiseHandler<Publisher<List<ByteBuffer>>>>map(
              pushPromiseHandler ->
                  (initiatingRequest, pushPromiseRequest, acceptor) -> {
                    Function<BodyHandler<T>, CompletableFuture<HttpResponse<T>>>
                        downstreamAcceptor =
                            bodyHandler -> {
                              var publisherResponseFuture =
                                  acceptor.apply(BodyHandlers.ofPublisher());
                              return publisherResponseFuture.thenCompose(
                                  response -> handleAsync(response, bodyHandler));
                            };
                    pushPromiseHandler.applyPushPromise(
                        initiatingRequest, pushPromiseRequest, downstreamAcceptor);
                  })
          .orElse(null);
    }

    private static final class SendAdapter {
      private final Chain<Publisher<List<ByteBuffer>>> chain;
      private final boolean async;

      private SendAdapter(Chain<Publisher<List<ByteBuffer>>> chain, boolean async) {
        this.chain = chain;
        this.async = async;
      }

      CompletableFuture<HttpResponse<Publisher<List<ByteBuffer>>>> send(HttpRequest request) {
        return async
            ? chain.forwardAsync(request)
            : Unchecked.supplyAsync(() -> chain.forward(request), FlowSupport.SYNC_EXECUTOR);
      }
    }

    private final class Redirector {
      private final HttpRequest request;
      private final SendAdapter sendAdapter;
      private final AtomicInteger redirectCount;
      private final @Nullable HttpResponse<Publisher<List<ByteBuffer>>> response;
      private final @Nullable HttpResponse<Publisher<List<ByteBuffer>>> previousResponse;

      Redirector(HttpRequest request, SendAdapter sendAdapter) {
        this(request, sendAdapter, new AtomicInteger(), null, null);
      }

      private Redirector(
          HttpRequest request,
          SendAdapter sendAdapter,
          AtomicInteger redirectCount,
          @Nullable HttpResponse<Publisher<List<ByteBuffer>>> response,
          @Nullable HttpResponse<Publisher<List<ByteBuffer>>> previousResponse) {
        this.request = request;
        this.sendAdapter = sendAdapter;
        this.redirectCount = redirectCount;
        this.response = response;
        this.previousResponse = previousResponse;
      }

      HttpResponse<Publisher<List<ByteBuffer>>> result() {
        requireState(response != null, "absent response");
        return castNonNull(response);
      }

      private Redirector withResponse(HttpResponse<Publisher<List<ByteBuffer>>> response) {
        var newResponse = response;
        if (previousResponse != null) {
          var previousResponseWithoutBody =
              ResponseBuilder.newBuilder(previousResponse).dropBody().build();
          newResponse =
              ResponseBuilder.newBuilder(response)
                  .previousResponse(previousResponseWithoutBody)
                  .build();
        }
        return new Redirector(request, sendAdapter, redirectCount, newResponse, null);
      }

      CompletableFuture<Redirector> sendAndFollowUp() {
        return sendAdapter
            .send(request)
            .thenApply(this::withResponse)
            .thenCompose(Redirector::followUp);
      }

      CompletableFuture<Redirector> followUp() {
        var response = result();
        var redirectedRequest = redirectedRequest(response);
        if (redirectedRequest == null || redirectCount.incrementAndGet() > MAX_REDIRECTS) {
          // Reached destination or exceeded allowed redirects
          return CompletableFuture.completedFuture(this);
        }

        // Discard the body of the redirecting response
        handleAsync(response, BodyHandlers.discarding());

        // Follow redirected request
        return new Redirector(
                redirectedRequest,
                sendAdapter,
                redirectCount,
                null, /* previousResponse */
                response)
            .sendAndFollowUp();
      }

      public @Nullable HttpRequest redirectedRequest(HttpResponse<?> response) {
        if (policy == Redirect.NEVER) {
          return null;
        }

        int statusCode = response.statusCode();
        if (isRedirecting(statusCode) && statusCode != HTTP_NOT_MODIFIED) {
          var redirectedUri = redirectedUri(response.headers());
          var newMethod = redirectedMethod(response.statusCode());
          if (canRedirectTo(redirectedUri)) {
            return createRedirectedRequest(redirectedUri, statusCode, newMethod);
          }
        }
        return null;
      }

      private URI redirectedUri(HttpHeaders responseHeaders) {
        return responseHeaders
            .firstValue("Location")
            .map(request.uri()::resolve)
            .orElseThrow(() -> new UncheckedIOException(new IOException("invalid redirection")));
      }

      // jdk.internal.net.http.RedirectFilter.redirectedMethod
      private String redirectedMethod(int statusCode) {
        var originalMethod = request.method();
        switch (statusCode) {
          case 301:
          case 302:
            return originalMethod.equals("POST") ? "GET" : originalMethod;
          case 303:
            return "GET";
          case 307:
          case 308:
          default:
            return originalMethod;
        }
      }

      // jdk.internal.net.http.RedirectFilter.canRedirect
      private boolean canRedirectTo(URI redirectedUri) {
        var oldScheme = request.uri().getScheme();
        var newScheme = redirectedUri.getScheme();
        switch (policy) {
          case ALWAYS:
            return true;
          case NEVER:
            return false;
          case NORMAL:
            return newScheme.equalsIgnoreCase(oldScheme) || newScheme.equalsIgnoreCase("https");
          default:
            throw new AssertionError("unexpected policy: " + policy);
        }
      }

      private HttpRequest createRedirectedRequest(
          URI redirectedUri, int statusCode, String newMethod) {
        boolean retainBody = statusCode != 303 && request.method().equals(newMethod);
        var newBody =
            request.bodyPublisher().filter(__ -> retainBody).orElseGet(BodyPublishers::noBody);
        return MutableRequest.copyOf(request).uri(redirectedUri).method(newMethod, newBody);
      }

      // jdk.internal.net.http.RedirectFilter.isRedirecting
      private boolean isRedirecting(int statusCode) {
        // 309-399 Unassigned => don't follow
        if (!HttpStatus.isRedirection(statusCode) || statusCode > 308) {
          return false;
        }

        switch (statusCode) {
            // 300: MultipleChoice => don't follow
            // 304: Not Modified => don't follow
            // 305: Proxy Redirect => don't follow.
            // 306: Unused => don't follow
          case 300:
          case 304:
          case 305:
          case 306:
            return false;
            // 301, 302, 303, 307, 308: OK to follow.
          default:
            return true;
        }
      }
    }
  }
}
