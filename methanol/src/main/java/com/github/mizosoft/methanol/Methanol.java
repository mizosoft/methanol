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
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.ScheduledExecutorService;
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
 * <p>In addition to implementing the {@link HttpClient} interface, this class also allows to:
 *
 * <ul>
 *   <li>Specify a {@link BaseBuilder#baseUri(URI) base URI}.
 *   <li>Specify a default {@link HttpRequest#timeout() request timeout}.
 *   <li>Add a set of default HTTP headers for inclusion in requests if absent.
 *   <li>{@link BaseBuilder#autoAcceptEncoding(boolean) Transparent} response decompression.
 *   <li>Intercept requests and responses going through this client.
 *   <li>Get {@code Publisher<HttpResponse<T>>} for asynchronous requests.
 * </ul>
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public final class Methanol extends HttpClient {

  private final HttpClient delegate;
  private final Optional<String> userAgent;
  private final Optional<URI> baseUri;
  private final Optional<Duration> requestTimeout;
  private final Optional<Duration> readTimeout;
  private final @Nullable ScheduledExecutorService readTimeoutScheduler;
  private final HttpHeaders defaultHeaders;
  private final boolean autoAcceptEncoding;
  private final List<Interceptor> preDecorationInterceptors;
  private final List<Interceptor> postDecorationInterceptors;

  private Methanol(BaseBuilder<?> builder) {
    delegate = builder.buildDelegateClient();
    userAgent = Optional.ofNullable(builder.userAgent);
    baseUri = Optional.ofNullable(builder.baseUri);
    requestTimeout = Optional.ofNullable(builder.requestTimeout);
    readTimeout = Optional.ofNullable(builder.readTimeout);
    readTimeoutScheduler = builder.readTimeoutScheduler;
    defaultHeaders = builder.headersBuilder.build();
    autoAcceptEncoding = builder.autoAcceptEncoding;
    preDecorationInterceptors = List.copyOf(builder.preDecorationInterceptors);
    postDecorationInterceptors = List.copyOf(builder.postDecorationInterceptors);
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
    return delegate;
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

  /** Returns the list of interceptors invoked before request decoration. */
  public List<Interceptor> interceptors() {
    return preDecorationInterceptors;
  }

  /** Returns the list of interceptors invoked after request decoration. */
  public List<Interceptor> postDecorationInterceptors() {
    return postDecorationInterceptors;
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
    return delegate.cookieHandler();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    return delegate.connectTimeout();
  }

  @Override
  public Redirect followRedirects() {
    return delegate.followRedirects();
  }

  @Override
  public Optional<ProxySelector> proxy() {
    return delegate.proxy();
  }

  @Override
  public SSLContext sslContext() {
    return delegate.sslContext();
  }

  @Override
  public SSLParameters sslParameters() {
    return delegate.sslParameters();
  }

  @Override
  public Optional<Authenticator> authenticator() {
    return delegate.authenticator();
  }

  @Override
  public Version version() {
    return delegate.version();
  }

  @Override
  public Optional<Executor> executor() {
    return delegate.executor();
  }

  @Override
  public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> bodyHandler)
      throws IOException, InterruptedException {
    requireNonNull(request, "request");
    requireNonNull(bodyHandler, "bodyHandler");
    return InterceptorChain.sendWithInterceptors(
        delegate, request, bodyHandler, buildInterceptorQueue());
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, BodyHandler<T> bodyHandler) {
    requireNonNull(request, "request");
    requireNonNull(bodyHandler, "bodyHandler");
    return InterceptorChain.sendAsyncWithInterceptors(
        delegate, request, bodyHandler, null, buildInterceptorQueue());
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      BodyHandler<T> bodyHandler,
      @Nullable PushPromiseHandler<T> pushPromiseHandler) {
    requireNonNull(request, "request");
    requireNonNull(bodyHandler, "bodyHandler");
    return InterceptorChain.sendAsyncWithInterceptors(
        delegate, request, bodyHandler, pushPromiseHandler, buildInterceptorQueue());
  }

  private List<Interceptor> buildInterceptorQueue() {
    var interceptors = new ArrayList<>(this.preDecorationInterceptors);
    interceptors.add(new RequestDecorationInterceptor(this));
    interceptors.addAll(postDecorationInterceptors);
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
     * <p>{@param T the response body type}
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

    final List<Interceptor> preDecorationInterceptors = new ArrayList<>();
    final List<Interceptor> postDecorationInterceptors = new ArrayList<>();

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
      requirePositiveDuration(requestTimeout);
      this.requestTimeout = requestTimeout;
      return self();
    }

    /**
     * Sets a default {@link MoreBodySubscribers#withReadTimeout(BodySubscriber, Duration)
     * readtimeout}. Timeout events are scheduled using a system-wide {@code
     * ScheduledExecutorService}.
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

    /**
     * Adds an interceptor that is invoked before request decoration (i.e. before default properties
     * or {@code BodyHandler} transformations are applied).
     */
    public B interceptor(Interceptor interceptor) {
      requireNonNull(interceptor);
      preDecorationInterceptors.add(interceptor);
      return self();
    }

    /**
     * Adds an interceptor that is invoked after request decoration (i.e. after default properties
     * or {@code BodyHandler transformations are applied).
     */
    public B postDecorationInterceptor(Interceptor interceptor) {
      requireNonNull(interceptor);
      postDecorationInterceptors.add(interceptor);
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
}
