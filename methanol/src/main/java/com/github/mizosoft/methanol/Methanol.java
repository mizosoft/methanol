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
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static com.github.mizosoft.methanol.internal.flow.FlowSupport.SYNC_EXECUTOR;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

import com.github.mizosoft.methanol.BodyDecoder.Factory;
import com.github.mizosoft.methanol.Methanol.Interceptor.Chain;
import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.RedirectingInterceptor;
import com.github.mizosoft.methanol.internal.concurrent.Delayer;
import com.github.mizosoft.methanol.internal.extensions.HeadersBuilder;
import com.github.mizosoft.methanol.internal.extensions.HttpResponsePublisher;
import com.github.mizosoft.methanol.internal.extensions.ResponseBuilder;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An {@code HttpClient} with interceptors, request decoration, HTTP caching and reactive
 * extensions.
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
 *
 * <p>A {@code Methanol} client relies on a standard {@code HttpClient} instance for sending
 * requests, referred to as its backend. You can obtain builders for {@code Methanol} using either
 * {@link #newBuilder()} or {@link #newBuilder(HttpClient)}. The latter takes a prebuilt backend,
 * while the former allows configuring a backend to be newly created each time {@link
 * BaseBuilder#build()} is invoked. Note that {@code HttpCaches} are not usable with a prebuilt
 * backend.
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public final class Methanol extends HttpClient {
  private static final Logger logger = System.getLogger(HeadersTimeoutInterceptor.class.getName());

  private final HttpClient backend;
  private final Redirect redirectPolicy;
  private final HttpHeaders defaultHeaders;
  private final Optional<HttpCache> cache;
  private final Optional<String> userAgent;
  private final Optional<URI> baseUri;
  private final Optional<Duration> headersTimeout;
  private final Optional<Duration> requestTimeout;
  private final Optional<Duration> readTimeout;
  private final boolean autoAcceptEncoding;
  private final List<Interceptor> interceptors;
  private final List<Interceptor> backendInterceptors;

  /** The complete list of interceptors invoked throughout the chain. */
  private final List<Interceptor> chainInterceptors;

  private Methanol(BaseBuilder<?> builder) {
    backend = builder.buildBackend();
    redirectPolicy = requireNonNullElse(builder.redirectPolicy, backend.followRedirects());
    defaultHeaders = builder.headersBuilder.build();
    cache = Optional.ofNullable(builder.cache);
    userAgent = Optional.ofNullable(builder.userAgent);
    baseUri = Optional.ofNullable(builder.baseUri);
    headersTimeout = Optional.ofNullable(builder.headersTimeout);
    requestTimeout = Optional.ofNullable(builder.requestTimeout);
    readTimeout = Optional.ofNullable(builder.readTimeout);
    autoAcceptEncoding = builder.autoAcceptEncoding;
    interceptors = List.copyOf(builder.interceptors);
    backendInterceptors = List.copyOf(builder.backendInterceptors);

    var chainInterceptors = new ArrayList<>(interceptors);
    chainInterceptors.add(
        new RequestRewritingInterceptor(
            baseUri, defaultHeaders, requestTimeout, autoAcceptEncoding));
    if (autoAcceptEncoding) {
      chainInterceptors.add(AutoDecompressingInterceptor.INSTANCE);
    }
    headersTimeout.ifPresent(
        timeout ->
            chainInterceptors.add(
                new HeadersTimeoutInterceptor(timeout, builder.headersTimeoutDelayer)));
    readTimeout.ifPresent(
        timeout ->
            chainInterceptors.add(
                new ReadTimeoutInterceptor(timeout, builder.readTimeoutScheduler)));
    cache.ifPresent(
        cache -> {
          var executor = backend.executor().orElse(null);
          // We handle redirection ourselves if a cache is installed
          chainInterceptors.add(new RedirectingInterceptor(redirectPolicy, executor));
          chainInterceptors.add(cache.interceptor(executor));
        });
    chainInterceptors.addAll(backendInterceptors);
    this.chainInterceptors = Collections.unmodifiableList(chainInterceptors);
  }

  /**
   * Returns a {@code Publisher} for the {@code HttpResponse<T>} resulting from asynchronously
   * sending the given request.
   */
  public <T> Publisher<HttpResponse<T>> exchange(HttpRequest request, BodyHandler<T> bodyHandler) {
    requireNonNull(request, "request");
    requireNonNull(bodyHandler, "bodyHandler");
    return new HttpResponsePublisher<>(
        this, request, bodyHandler, null, executor().orElse(SYNC_EXECUTOR));
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
        this, request, bodyHandler, pushPromiseAcceptor, executor().orElse(SYNC_EXECUTOR));
  }

  /** Returns the underlying {@code HttpClient} used for sending requests. */
  public HttpClient underlyingClient() {
    return backend;
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

  /** Returns the headers' timeout. */
  public Optional<Duration> headersTimeout() {
    return headersTimeout;
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
   * Returns an immutable list of this client's {@link BaseBuilder#backendInterceptor(Interceptor)
   * backend interceptors}.
   */
  public List<Interceptor> backendInterceptors() {
    return backendInterceptors;
  }

  /**
   * Returns the list of interceptors invoked after request decoration.
   *
   * @deprecated Use {@link #backendInterceptors()}
   */
  @Deprecated(since = "1.5.0")
  public List<Interceptor> postDecorationInterceptors() {
    return backendInterceptors;
  }

  /** Returns this client's default headers. */
  public HttpHeaders defaultHeaders() {
    return defaultHeaders;
  }

  /** Returns this client's {@link Builder#autoAcceptEncoding auto Accept-Encoding} setting. */
  public boolean autoAcceptEncoding() {
    return autoAcceptEncoding;
  }

  /** Returns this client's {@link HttpCache cache}. */
  public Optional<HttpCache> cache() {
    return cache;
  }

  @Override
  public Optional<CookieHandler> cookieHandler() {
    return backend.cookieHandler();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    return backend.connectTimeout();
  }

  @Override
  public Redirect followRedirects() {
    return redirectPolicy;
  }

  @Override
  public Optional<ProxySelector> proxy() {
    return backend.proxy();
  }

  @Override
  public SSLContext sslContext() {
    return backend.sslContext();
  }

  @Override
  public SSLParameters sslParameters() {
    return backend.sslParameters();
  }

  @Override
  public Optional<Authenticator> authenticator() {
    return backend.authenticator();
  }

  @Override
  public Version version() {
    return backend.version();
  }

  @Override
  public Optional<Executor> executor() {
    return backend.executor();
  }

  @Override
  public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> bodyHandler)
      throws IOException, InterruptedException {
    requireNonNull(request, "request");
    requireNonNull(bodyHandler, "bodyHandler");
    return new InterceptorChain<>(backend, bodyHandler, null, chainInterceptors).forward(request);
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, BodyHandler<T> bodyHandler) {
    requireNonNull(request, "request");
    requireNonNull(bodyHandler, "bodyHandler");
    return new InterceptorChain<>(backend, bodyHandler, null, chainInterceptors)
        .forwardAsync(request);
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      BodyHandler<T> bodyHandler,
      @Nullable PushPromiseHandler<T> pushPromiseHandler) {
    requireNonNull(request, "request");
    requireNonNull(bodyHandler, "bodyHandler");
    return new InterceptorChain<>(backend, bodyHandler, pushPromiseHandler, chainInterceptors)
        .forwardAsync(request);
  }

  private static URI validateUri(URI uri) {
    var scheme = uri.getScheme();
    requireArgument(scheme != null, "uri has no scheme: %s", uri);
    scheme = scheme.toLowerCase(Locale.ENGLISH);
    requireArgument(
        "http".equals(scheme) || "https".equals(scheme), "unsupported scheme: %s", scheme);
    requireArgument(uri.getHost() != null, "uri has no host: %s", uri);
    return uri;
  }

  private static <T> PushPromiseHandler<T> transformPushPromises(
      PushPromiseHandler<T> pushPromiseHandler,
      UnaryOperator<BodyHandler<T>> bodyHandlerTransformer,
      UnaryOperator<HttpResponse<T>> responseTransformer) {
    return (initialRequest, pushRequest, acceptor) ->
        pushPromiseHandler.applyPushPromise(
            initialRequest,
            pushRequest,
            acceptor
                .compose(bodyHandlerTransformer)
                .andThen(future -> future.thenApply(responseTransformer)));
  }

  /** Returns a new {@link Methanol.Builder}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** Returns a new {@link Methanol.WithClientBuilder} with a prebuilt backend. */
  public static WithClientBuilder newBuilder(HttpClient backend) {
    return new WithClientBuilder(backend);
  }

  /** Creates a default {@code Methanol} instance. */
  public static Methanol create() {
    return newBuilder().build();
  }

  /** An object that intercepts requests being sent over a {@code Methanol} client. */
  public interface Interceptor {

    /**
     * Intercepts given request and returns the resulting response, usually by forwarding to the
     * given chain.
     */
    <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
        throws IOException, InterruptedException;

    /**
     * Intercepts the given request and returns a {@code CompletableFuture} for the resulting
     * response, usually by forwarding to the given chain.
     */
    <T> CompletableFuture<HttpResponse<T>> interceptAsync(HttpRequest request, Chain<T> chain);

    /** Returns an interceptor that forwards the request after applying the given operator. */
    static Interceptor create(UnaryOperator<HttpRequest> operator) {
      requireNonNull(operator);
      return new Interceptor() {
        @Override
        public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
            throws IOException, InterruptedException {
          return chain.forward(operator.apply(request));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
            HttpRequest request, Chain<T> chain) {
          return chain.forwardAsync(operator.apply(request));
        }
      };
    }

    /**
     * An object that gives interceptors the ability to relay requests to sibling interceptors, till
     * eventually being sent by the client's backend.
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
      default <U> Chain<U> with(
          BodyHandler<U> bodyHandler, @Nullable PushPromiseHandler<U> pushPromiseHandler) {
        throw new UnsupportedOperationException();
      }

      /**
       * Forwards the request to the next interceptor, or to the client's backend if called by the
       * last interceptor.
       */
      HttpResponse<T> forward(HttpRequest request) throws IOException, InterruptedException;

      /**
       * Forwards the request to the next interceptor, or asynchronously to the client's backend if
       * called by the last interceptor.
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
    @MonotonicNonNull Duration headersTimeout;
    @MonotonicNonNull Delayer headersTimeoutDelayer;
    @MonotonicNonNull Duration readTimeout;
    @MonotonicNonNull ScheduledExecutorService readTimeoutScheduler;
    boolean autoAcceptEncoding;

    // These fields are put here for convenience, they're only writable by Builder
    @MonotonicNonNull HttpCache cache;
    @MonotonicNonNull Redirect redirectPolicy;

    final List<Interceptor> interceptors = new ArrayList<>();
    final List<Interceptor> backendInterceptors = new ArrayList<>();

    BaseBuilder() {
      headersBuilder = new HeadersBuilder();
      autoAcceptEncoding = true;
    }

    /** Calls the given consumer against this builder. */
    public final B apply(Consumer<? super B> consumer) {
      consumer.accept(self());
      return self();
    }

    /**
     * Sets a default {@code User-Agent} header to use when sending requests.
     *
     * @throws IllegalArgumentException if {@code userAgent} is an invalid header value
     */
    public B userAgent(String userAgent) {
      requireNonNull(userAgent);
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
      this.baseUri = validateUri(uri);
      return self();
    }

    /** Adds the given header as default. */
    public B defaultHeader(String name, String value) {
      requireNonNull(name);
      requireNonNull(value);
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
      requireNonNull(requestTimeout);
      requirePositiveDuration(requestTimeout);
      this.requestTimeout = requestTimeout;
      return self();
    }

    /**
     * Sets a timeout that will raise an {@link HttpHeadersTimeoutException} if all response headers
     * aren't received within the timeout.
     */
    public B headersTimeout(Duration headersTimeout) {
      return headersTimeout(headersTimeout, Delayer.systemDelayer());
    }

    /**
     * Same as {@link #headersTimeout(Duration)} but specifies a {@code ScheduledExecutorService} to
     * use for scheduling timeout events.
     */
    public B headersTimeout(Duration headersTimeout, ScheduledExecutorService scheduler) {
      return headersTimeout(headersTimeout, Delayer.of(scheduler));
    }

    B headersTimeout(Duration headersTimeout, Delayer delayer) {
      requireNonNull(headersTimeout);
      requireNonNull(delayer);
      requirePositiveDuration(headersTimeout);
      this.headersTimeout = headersTimeout;
      this.headersTimeoutDelayer = delayer;
      return self();
    }

    /**
     * Sets a default {@link MoreBodySubscribers#withReadTimeout(BodySubscriber, Duration) read
     * timeout}. Timeout events are scheduled using a system-wide {@code ScheduledExecutorService}.
     */
    public B readTimeout(Duration readTimeout) {
      requireNonNull(readTimeout);
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
      requireNonNull(readTimeout);
      requireNonNull(scheduler);
      requirePositiveDuration(readTimeout);
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
     * base {@code URI}, default headers added, etc...) or handled by an {@link HttpCache}.
     */
    public B interceptor(Interceptor interceptor) {
      requireNonNull(interceptor);
      interceptors.add(interceptor);
      return self();
    }

    /**
     * Adds an interceptor that is invoked right before the request is forwarded to the client's
     * backend. The interceptor receives the request after it is handled by all {@link
     * #interceptor(Interceptor) client interceptors}, is decorated (its {@code URI} resolved with
     * the base {@code URI}, default headers added, etc...) and finally handled by an {@link
     * HttpCache}. This means that backend interceptors aren't called if network isn't used,
     * normally due to the presence of an {@code HttpCache} that is capable of serving a stored
     * response.
     */
    public B backendInterceptor(Interceptor interceptor) {
      requireNonNull(interceptor);
      backendInterceptors.add(interceptor);
      return self();
    }

    /**
     * Adds an interceptor that is invoked after request decoration (i.e. after default properties
     * or {@code BodyHandler} transformations are applied).
     *
     * @deprecated Use {@link #backendInterceptor(Interceptor)}
     */
    @Deprecated(since = "1.5.0")
    public B postDecorationInterceptor(Interceptor interceptor) {
      return backendInterceptor(interceptor);
    }

    /** Returns a new {@code Methanol} with a snapshot of the current builder's state. */
    public Methanol build() {
      return new Methanol(this);
    }

    abstract B self();

    abstract HttpClient buildBackend();
  }

  /** A builder for {@code Methanol} instances with a predefined backend {@code HttpClient}. */
  public static final class WithClientBuilder extends BaseBuilder<WithClientBuilder> {
    private final HttpClient backend;

    WithClientBuilder(HttpClient backend) {
      this.backend = requireNonNull(backend);
    }

    @Override
    WithClientBuilder self() {
      return this;
    }

    @Override
    HttpClient buildBackend() {
      return backend;
    }
  }

  /** A builder of {@code Methanol} instances. */
  public static final class Builder extends BaseBuilder<Builder> implements HttpClient.Builder {
    private final HttpClient.Builder backendBuilder;

    Builder() {
      backendBuilder = HttpClient.newBuilder();
    }

    /** Sets the {@link HttpCache} to be used by the client. */
    public Builder cache(HttpCache cache) {
      super.cache = requireNonNull(cache);
      return this;
    }

    @Override
    public Builder cookieHandler(CookieHandler cookieHandler) {
      backendBuilder.cookieHandler(cookieHandler);
      return this;
    }

    @Override
    public Builder connectTimeout(Duration duration) {
      backendBuilder.connectTimeout(duration);
      return this;
    }

    @Override
    public Builder sslContext(SSLContext sslContext) {
      backendBuilder.sslContext(sslContext);
      return this;
    }

    @Override
    public Builder sslParameters(SSLParameters sslParameters) {
      backendBuilder.sslParameters(sslParameters);
      return this;
    }

    @Override
    public Builder executor(Executor executor) {
      backendBuilder.executor(executor);
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
      backendBuilder.version(version);
      return this;
    }

    @Override
    public Builder priority(int priority) {
      backendBuilder.priority(priority);
      return this;
    }

    @Override
    public Builder proxy(ProxySelector proxySelector) {
      backendBuilder.proxy(proxySelector);
      return this;
    }

    @Override
    public Builder authenticator(Authenticator authenticator) {
      backendBuilder.authenticator(authenticator);
      return this;
    }

    @Override
    Builder self() {
      return this;
    }

    @Override
    HttpClient buildBackend() {
      // Apply redirectPolicy if a cache is not set.
      // In such case we let the backend handle redirects.
      if (cache == null && redirectPolicy != null) {
        backendBuilder.followRedirects(redirectPolicy);
      }
      return backendBuilder.build();
    }
  }

  private static final class InterceptorChain<T> implements Interceptor.Chain<T> {
    private final HttpClient backend;
    private final BodyHandler<T> bodyHandler;
    private final @Nullable PushPromiseHandler<T> pushPromiseHandler;
    private final List<Interceptor> interceptors;
    private final int currentInterceptorIndex;

    InterceptorChain(
        HttpClient backend,
        BodyHandler<T> bodyHandler,
        @Nullable PushPromiseHandler<T> pushPromiseHandler,
        List<Interceptor> interceptors) {
      this(backend, bodyHandler, pushPromiseHandler, interceptors, 0);
    }

    private InterceptorChain(
        HttpClient backend,
        BodyHandler<T> bodyHandler,
        @Nullable PushPromiseHandler<T> pushPromiseHandler,
        List<Interceptor> interceptors,
        int currentInterceptorIndex) {
      this.backend = backend;
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
          backend, bodyHandler, pushPromiseHandler, interceptors, currentInterceptorIndex);
    }

    @Override
    public Interceptor.Chain<T> withPushPromiseHandler(
        @Nullable PushPromiseHandler<T> pushPromiseHandler) {
      return new InterceptorChain<>(
          backend, bodyHandler, pushPromiseHandler, interceptors, currentInterceptorIndex);
    }

    @Override
    public <U> Chain<U> with(
        BodyHandler<U> bodyHandler, @Nullable PushPromiseHandler<U> pushPromiseHandler) {
      requireNonNull(bodyHandler);
      return new InterceptorChain<>(
          backend, bodyHandler, pushPromiseHandler, interceptors, currentInterceptorIndex);
    }

    @Override
    public HttpResponse<T> forward(HttpRequest request) throws IOException, InterruptedException {
      requireNonNull(request);
      if (currentInterceptorIndex >= interceptors.size()) {
        return backend.send(request, bodyHandler);
      }

      var interceptor = interceptors.get(currentInterceptorIndex);
      return requireNonNull(
          interceptor.intercept(request, nextInterceptorChain()),
          () -> interceptor + "::intercept returned a null response");
    }

    @Override
    public CompletableFuture<HttpResponse<T>> forwardAsync(HttpRequest request) {
      requireNonNull(request);
      if (currentInterceptorIndex >= interceptors.size()) {
        // sendAsync accepts a nullable pushPromiseHandler
        return backend.sendAsync(request, bodyHandler, pushPromiseHandler);
      }

      var interceptor = interceptors.get(currentInterceptorIndex);
      return interceptor
          .interceptAsync(request, nextInterceptorChain())
          .thenApply(
              res ->
                  requireNonNull(
                      res, () -> interceptor + "::interceptAsync completed with a null response"));
    }

    private InterceptorChain<T> nextInterceptorChain() {
      return new InterceptorChain<>(
          backend, bodyHandler, pushPromiseHandler, interceptors, currentInterceptorIndex + 1);
    }
  }

  /** Rewrites requests as configured. */
  private static final class RequestRewritingInterceptor implements Interceptor {
    private final Optional<URI> baseUri;
    private final Optional<Duration> requestTimeout;
    private final HttpHeaders defaultHeaders;
    private final boolean autoAcceptEncoding;

    RequestRewritingInterceptor(
        Optional<URI> baseUri,
        HttpHeaders defaultHeaders,
        Optional<Duration> requestTimeout,
        boolean autoAcceptEncoding) {
      this.baseUri = baseUri;
      this.requestTimeout = requestTimeout;
      this.defaultHeaders = defaultHeaders;
      this.autoAcceptEncoding = autoAcceptEncoding;
    }

    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
        throws IOException, InterruptedException {
      return chain.forward(rewriteRequest(request));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
        HttpRequest request, Chain<T> chain) {
      return chain.forwardAsync(rewriteRequest(request));
    }

    private HttpRequest rewriteRequest(HttpRequest request) {
      var rewrittenRequest = MutableRequest.copyOf(request);

      baseUri.map(baseUri -> baseUri.resolve(request.uri())).ifPresent(rewrittenRequest::uri);
      validateUri(rewrittenRequest.uri());

      var originalHeadersMap = request.headers().map();
      var defaultHeadersMap = defaultHeaders.map();

      defaultHeadersMap.forEach(
          (name, values) -> {
            if (!originalHeadersMap.containsKey(name)) {
              values.forEach(value -> rewrittenRequest.header(name, value));
            }
          });

      if (autoAcceptEncoding
          && !originalHeadersMap.containsKey("Accept-Encoding")
          && !defaultHeadersMap.containsKey("Accept-Encoding")) {
        var supportedEncodings = BodyDecoder.Factory.installedBindings().keySet();
        if (!supportedEncodings.isEmpty()) {
          rewrittenRequest.header("Accept-Encoding", String.join(", ", supportedEncodings));
        }
      }

      // Overwrite Content-Type if request body is a MimeBodyPublisher
      request
          .bodyPublisher()
          .filter(MimeBodyPublisher.class::isInstance)
          .map(body -> ((MimeBodyPublisher) body).mediaType())
          .ifPresent(mediaType -> rewrittenRequest.setHeader("Content-Type", mediaType.toString()));

      if (request.timeout().isEmpty()) {
        requestTimeout.ifPresent(rewrittenRequest::timeout);
      }

      return rewrittenRequest.toImmutableRequest();
    }
  }

  /** Applies {@link MoreBodyHandlers#decoding(BodyHandler)} to responses and push promises. */
  private enum AutoDecompressingInterceptor implements Interceptor {
    INSTANCE;

    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
        throws IOException, InterruptedException {
      return stripContentEncoding(decoding(request, chain).forward(request));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
        HttpRequest request, Chain<T> chain) {
      return decoding(request, chain)
          .forwardAsync(request)
          .thenApply(AutoDecompressingInterceptor::stripContentEncoding);
    }

    private static <T> Chain<T> decoding(HttpRequest request, Chain<T> chain) {
      // Skip auto decompression if this is a HEAD
      if ("HEAD".equalsIgnoreCase(request.method())) {
        return chain;
      }

      return chain.with(
          MoreBodyHandlers.decoding(chain.bodyHandler()),
          chain
              .pushPromiseHandler()
              .map(
                  handler ->
                      transformPushPromises(
                          handler,
                          MoreBodyHandlers::decoding,
                          AutoDecompressingInterceptor::stripContentEncoding))
              .orElse(null));
    }

    private static <T> HttpResponse<T> stripContentEncoding(HttpResponse<T> response) {
      // Don't strip if the response wasn't decompressed
      if ("HEAD".equalsIgnoreCase(response.request().method())
          || !response.headers().map().containsKey("Content-Encoding")) {
        return response;
      }

      return ResponseBuilder.newBuilder(response)
          .removeHeader("Content-Encoding")
          .removeHeader("Content-Length")
          .build();
    }
  }

  private static final class HeadersTimeoutInterceptor implements Interceptor {
    private final Duration headersTimeout;
    private final Delayer delayer;

    HeadersTimeoutInterceptor(Duration headersTimeout, Delayer delayer) {
      this.headersTimeout = headersTimeout;
      this.delayer = delayer;
    }

    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
        throws IOException, InterruptedException {
      return Utils.block(interceptAsync(request, chain));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
        HttpRequest request, Chain<T> chain) {
      var timeoutTask = new HeadersTimeoutTask(headersTimeout, delayer);
      var responseFuture = withHeadersTimeout(chain, timeoutTask).forwardAsync(request);
      return timeoutTask.scheduleFor(responseFuture);
    }

    private <T> Chain<T> withHeadersTimeout(Chain<T> chain, HeadersTimeoutTask timeoutTask) {
      // TODO handle push promises
      return chain.withBodyHandler(
          responseInfo ->
              timeoutTask.cancel()
                  ? chain.bodyHandler().apply(responseInfo)
                  : new TimedOutSubscriber<>());
    }

    private static final class HeadersTimeoutTask {
      private static final Future<Void> EVALUATED_FUTURE = CompletableFuture.completedFuture(null);
      private static final Future<Void> CANCELLED_FUTURE =
          CompletableFuture.failedFuture(new CancellationException());

      private final Duration timeout;
      private final Delayer delayer;
      private final AtomicReference<@Nullable Future<Void>> timeoutFuture = new AtomicReference<>();

      HeadersTimeoutTask(Duration timeout, Delayer delayer) {
        this.timeout = timeout;
        this.delayer = delayer;
      }

      <T> CompletableFuture<HttpResponse<T>> scheduleFor(
          CompletableFuture<HttpResponse<T>> responseFuture) {
        // Make a dependent copy of the original response future, so we can cancel the latter and
        // complete the former exceptionally on timeout. Cancelling the original future may lead
        // to cancelling the actual request on JDK 16 or higher.
        var responseFutureCopy = responseFuture.copy();
        var scheduledFuture =
            delayer.delay(
                () -> {
                  while (true) {
                    var currentFuture = timeoutFuture.get();
                    if (currentFuture != null && currentFuture.isCancelled()) {
                      return;
                    }
                    if (timeoutFuture.compareAndSet(currentFuture, EVALUATED_FUTURE)) {
                      responseFutureCopy.completeExceptionally(
                          new HttpHeadersTimeoutException("couldn't receive headers on time"));
                      responseFuture.cancel(true);
                      return;
                    }
                  }
                },
                timeout,
                SYNC_EXECUTOR);

        while (true) {
          var currentFuture = timeoutFuture.get();
          if (currentFuture != null && currentFuture.isCancelled()) {
            scheduledFuture.cancel(false);
            return responseFuture;
          }
          if ((currentFuture == null && timeoutFuture.compareAndSet(null, scheduledFuture))
              || (currentFuture != null && currentFuture.isDone())) {
            // Either we could CAS to scheduledFuture or scheduledFuture actually finished and CASed
            // to EVALUATED_FUTURE before we had a chance to set it.
            return responseFutureCopy;
          }
        }
      }

      boolean cancel() {
        var future = timeoutFuture.getAndSet(CANCELLED_FUTURE);
        return future == null || future.cancel(false);
      }
    }

    private static final class TimedOutSubscriber<T> implements BodySubscriber<T> {
      TimedOutSubscriber() {}

      @Override
      public CompletionStage<T> getBody() {
        return CompletableFuture.failedFuture(
            new HttpHeadersTimeoutException("couldn't receive headers ont time"));
      }

      @Override
      public void onSubscribe(Subscription subscription) {
        requireNonNull(subscription);
        subscription.cancel();
      }

      @Override
      public void onNext(List<ByteBuffer> item) {
        requireNonNull(item);
      }

      @Override
      public void onError(Throwable throwable) {
        requireNonNull(throwable);
        logger.log(Level.WARNING, "exception received after headers timeout", throwable);
      }

      @Override
      public void onComplete() {}
    }
  }

  /**
   * Applies {@link MoreBodyHandlers#withReadTimeout read timeouts} to responses and push promises.
   */
  private static final class ReadTimeoutInterceptor implements Interceptor {
    private final Duration readTimeout;
    private final @Nullable ScheduledExecutorService readTimeoutScheduler;

    ReadTimeoutInterceptor(
        Duration readTimeout, @Nullable ScheduledExecutorService readTimeoutScheduler) {
      this.readTimeout = readTimeout;
      this.readTimeoutScheduler = readTimeoutScheduler;
    }

    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
        throws IOException, InterruptedException {
      return withReadTimeout(chain).forward(request);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
        HttpRequest request, Chain<T> chain) {
      return withReadTimeout(chain).forwardAsync(request);
    }

    private <T> Chain<T> withReadTimeout(Chain<T> chain) {
      return chain.with(
          withReadTimeout(chain.bodyHandler()),
          chain
              .pushPromiseHandler()
              .map(
                  handler ->
                      transformPushPromises(
                          handler, this::withReadTimeout, UnaryOperator.identity()))
              .orElse(null));
    }

    private <T> BodyHandler<T> withReadTimeout(BodyHandler<T> bodyHandler) {
      return readTimeoutScheduler != null
          ? MoreBodyHandlers.withReadTimeout(bodyHandler, readTimeout, readTimeoutScheduler)
          : MoreBodyHandlers.withReadTimeout(bodyHandler, readTimeout);
    }
  }
}
