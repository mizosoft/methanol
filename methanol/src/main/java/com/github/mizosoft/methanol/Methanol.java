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

import static com.github.mizosoft.methanol.internal.Utils.requirePositiveDuration;
import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

import com.github.mizosoft.methanol.BodyDecoder.Factory;
import com.github.mizosoft.methanol.Methanol.Interceptor.Chain;
import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.adapter.PayloadHandlerExecutor;
import com.github.mizosoft.methanol.internal.cache.RedirectingInterceptor;
import com.github.mizosoft.methanol.internal.concurrent.Delayer;
import com.github.mizosoft.methanol.internal.extensions.HeadersBuilder;
import com.github.mizosoft.methanol.internal.extensions.HttpResponsePublisher;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.function.Unchecked;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.InlineMe;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An {@code HttpClient} with interceptors, request decoration, HTTP caching and reactive
 * extensions.
 *
 * <p>In addition to implementing the {@link HttpClient} API, this class allows to:
 *
 * <ul>
 *   <li>Specify a {@link BaseBuilder#baseUri(URI) base URI}.
 *   <li>Specify a default {@link HttpRequest#timeout() request timeout}.
 *   <li>Specify a read timeout.
 *   <li>Add a set of default HTTP headers for inclusion in requests if absent.
 *   <li>Add an {@link HttpCache HTTP caching} layer.
 *   <li>{@link BaseBuilder#autoAcceptEncoding(boolean) Transparent} response decompression.
 *   <li>Intercept requests and responses going through this client.
 *   <li>Specify an {@link AdapterCodec} to automatically convert to/from request/response bodies.
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
public class Methanol extends HttpClient {
  private static final Logger logger = System.getLogger(Methanol.class.getName());

  private static final @Nullable MethodHandle SHUTDOWN; // Since Java 21.
  private static final @Nullable MethodHandle AWAIT_TERMINATION; // Since Java 21.
  private static final @Nullable MethodHandle IS_TERMINATED; // Since Java 21.
  private static final @Nullable MethodHandle SHUTDOWN_NOW; // Since Java 21.
  private static final @Nullable MethodHandle CLOSE; // Since Java 21.

  static {
    var lookup = MethodHandles.lookup();
    MethodHandle shutdown;
    try {
      shutdown =
          lookup.findVirtual(HttpClient.class, "shutdown", MethodType.methodType(void.class));
    } catch (NoSuchMethodException e) {
      shutdown = null;
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }

    if (shutdown == null) {
      SHUTDOWN = null;
      AWAIT_TERMINATION = null;
      IS_TERMINATED = null;
      SHUTDOWN_NOW = null;
      CLOSE = null;
    } else {
      SHUTDOWN = shutdown;
      try {
        AWAIT_TERMINATION =
            lookup.findVirtual(
                HttpClient.class,
                "awaitTermination",
                MethodType.methodType(boolean.class, Duration.class));
        IS_TERMINATED =
            lookup.findVirtual(
                HttpClient.class, "isTerminated", MethodType.methodType(boolean.class));
        SHUTDOWN_NOW =
            lookup.findVirtual(HttpClient.class, "shutdownNow", MethodType.methodType(void.class));
        CLOSE = lookup.findVirtual(HttpClient.class, "close", MethodType.methodType(void.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private final HttpClient backend;
  private final Redirect redirectPolicy;
  private final HttpHeaders defaultHeaders;
  private final Optional<String> userAgent;
  private final Optional<URI> baseUri;
  private final Optional<Duration> headersTimeout;
  private final Optional<Duration> requestTimeout;
  private final Optional<Duration> readTimeout;
  private final Optional<AdapterCodec> adapterCodec;
  private final boolean autoAcceptEncoding;
  private final List<Interceptor> interceptors;
  private final List<Interceptor> backendInterceptors;
  private final List<HttpCache> caches;

  /** The complete list of interceptors invoked throughout the chain. */
  private final List<Interceptor> mergedInterceptors;

  private Methanol(BaseBuilder<?> builder) {
    backend = builder.buildBackend();
    redirectPolicy = requireNonNullElse(builder.redirectPolicy, backend.followRedirects());
    defaultHeaders = builder.defaultHeadersBuilder.build();
    userAgent = Optional.ofNullable(builder.userAgent);
    baseUri = Optional.ofNullable(builder.baseUri);
    headersTimeout = Optional.ofNullable(builder.headersTimeout);
    requestTimeout = Optional.ofNullable(builder.requestTimeout);
    readTimeout = Optional.ofNullable(builder.readTimeout);
    adapterCodec = Optional.ofNullable(builder.adapterCodec);
    autoAcceptEncoding = builder.autoAcceptEncoding;
    interceptors = List.copyOf(builder.interceptors);
    backendInterceptors = List.copyOf(builder.backendInterceptors);
    caches = builder.caches;

    var mergedInterceptors = new ArrayList<>(interceptors);
    mergedInterceptors.add(
        new RequestRewritingInterceptor(
            baseUri, requestTimeout, adapterCodec, defaultHeaders, autoAcceptEncoding));
    if (autoAcceptEncoding) {
      mergedInterceptors.add(AutoDecompressingInterceptor.INSTANCE);
    }
    headersTimeout.ifPresent(
        timeout ->
            mergedInterceptors.add(
                new HeadersTimeoutInterceptor(
                    timeout, castNonNull(builder.headersTimeoutDelayer))));
    readTimeout.ifPresent(
        timeout ->
            mergedInterceptors.add(
                new ReadTimeoutInterceptor(timeout, castNonNull(builder.readTimeoutDelayer))));

    if (!caches.isEmpty()) {
      mergedInterceptors.add(
          new RedirectingInterceptor(redirectPolicy, backend.executor().orElse(null)));
    }
    caches.forEach(cache -> mergedInterceptors.add(cache.interceptor()));

    mergedInterceptors.addAll(backendInterceptors);
    this.mergedInterceptors = Collections.unmodifiableList(mergedInterceptors);
  }

  /**
   * Returns a {@code Publisher} for the {@code HttpResponse<T>} resulting from asynchronously
   * sending the given request.
   */
  public <T> Publisher<HttpResponse<T>> exchange(HttpRequest request, BodyHandler<T> bodyHandler) {
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
      Function<HttpRequest, @Nullable BodyHandler<T>> pushPromiseMapper) {
    return new HttpResponsePublisher<>(
        this,
        request,
        bodyHandler,
        pushPromiseMapper,
        executor().orElse(FlowSupport.SYNC_EXECUTOR));
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

  /** Returns the headers timeout. */
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
    return caches.stream().findFirst();
  }

  public List<HttpCache> caches() {
    return caches;
  }

  public Optional<AdapterCodec> adapterCodec() {
    return adapterCodec;
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
  public WebSocket.Builder newWebSocketBuilder() {
    return backend.newWebSocketBuilder();
  }

  @Override
  public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> bodyHandler)
      throws IOException, InterruptedException {
    return new InterceptorChain<>(backend, bodyHandler, null, mergedInterceptors).forward(request);
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, BodyHandler<T> bodyHandler) {
    return new InterceptorChain<>(backend, bodyHandler, null, mergedInterceptors)
        .forwardAsync(request);
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      BodyHandler<T> bodyHandler,
      @Nullable PushPromiseHandler<T> pushPromiseHandler) {
    return new InterceptorChain<>(backend, bodyHandler, pushPromiseHandler, mergedInterceptors)
        .forwardAsync(request);
  }

  /**
   * {@link #send(HttpRequest, BodyHandler) Sends} the given request and converts the response body
   * into an object of the given type.
   */
  public <T> HttpResponse<T> send(HttpRequest request, Class<T> type)
      throws IOException, InterruptedException {
    return send(request, TypeRef.of(type));
  }

  /**
   * {@link #sendAsync(HttpRequest, BodyHandler) Asynchronously sends} the given request and
   * converts the response body into an object of the given type.
   */
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, Class<T> type) {
    return sendAsync(request, TypeRef.of(type));
  }

  /**
   * {@link #send(HttpRequest, BodyHandler) Sends} the given request and converts the response body
   * into an object of the given type.
   */
  public <T> HttpResponse<T> send(HttpRequest request, TypeRef<T> typeRef)
      throws IOException, InterruptedException {
    return new InterceptorChain<>(backend, handlerOf(request, typeRef), null, mergedInterceptors)
        .forward(request);
  }

  /**
   * {@link #sendAsync(HttpRequest, BodyHandler) Asynchronously sends} the given request and
   * converts the response body into an object of the given type.
   */
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, TypeRef<T> typeRef) {
    return new InterceptorChain<>(backend, handlerOf(request, typeRef), null, mergedInterceptors)
        .forwardAsync(request);
  }

  private <T> BodyHandler<T> handlerOf(HttpRequest request, TypeRef<T> typeRef) {
    var adapterCodec = MutableRequest.adapterCodecOf(request).or(() -> this.adapterCodec);
    var hints = TaggableRequest.hintsOf(request);
    var deferredValueTypeRef =
        typeRef.isParameterizedType() && typeRef.rawType() == Supplier.class
            ? typeRef
                .resolveSupertype(Supplier.class)
                .typeArgumentAt(0)
                .orElseThrow(AssertionError::new) // A parameterized type must contain a type arg.
            : null;
    if ((typeRef.isRawType() && typeRef.rawType() == ResponsePayload.class)
        || (deferredValueTypeRef != null
            && deferredValueTypeRef.isRawType()
            && deferredValueTypeRef.rawType() == ResponsePayload.class)) {
      // Add ResponsePayload-specific hints (see BasicAdapter.Decoder).
      var hintsBuilder = hints.mutate();
      adapterCodec.ifPresent(codec -> hintsBuilder.put(AdapterCodec.class, codec));
      executor()
          .ifPresent(
              executor ->
                  hintsBuilder.put(
                      PayloadHandlerExecutor.class, new PayloadHandlerExecutor(executor)));
      hints = hintsBuilder.build();
    }

    var effectiveAdapterCodec = adapterCodec.orElseGet(AdapterCodec::installed);
    if (deferredValueTypeRef != null) {
      @SuppressWarnings("unchecked")
      var bodyHandler =
          (BodyHandler<T>) effectiveAdapterCodec.deferredHandlerOf(deferredValueTypeRef, hints);
      return bodyHandler;
    } else {
      return effectiveAdapterCodec.handlerOf(typeRef, hints);
    }
  }

  @CanIgnoreReturnValue
  private static URI validateUri(URI uri) {
    var scheme = uri.getScheme();
    requireArgument(scheme != null, "URI has no scheme: %s", uri);
    requireArgument(
        scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"),
        "Unsupported scheme: %s",
        scheme);
    requireArgument(uri.getHost() != null, "URI has no host: %s", uri);
    return uri;
  }

  private static <T> PushPromiseHandler<T> transformPushPromiseHandler(
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
    return Builder.create();
  }

  /** Returns a new {@link Methanol.WithClientBuilder} with a prebuilt backend. */
  public static WithClientBuilder newBuilder(HttpClient backend) {
    return new WithClientBuilder(backend);
  }

  /** Creates a default {@code Methanol} instance. */
  public static Methanol create() {
    return newBuilder().build();
  }

  private static final class MethanolForJava21AndLater extends Methanol {
    MethanolForJava21AndLater(BaseBuilder<?> builder) {
      super(builder);
    }

    // @Override
    @SuppressWarnings("Since15")
    public void shutdown() {
      try {
        castNonNull(SHUTDOWN).invokeExact(underlyingClient());
      } catch (Throwable e) {
        Unchecked.propagateIfUnchecked(e);
        throw new RuntimeException(e);
      }
    }

    // @Override
    @SuppressWarnings("Since15")
    public boolean awaitTermination(Duration duration) throws InterruptedException {
      try {
        return (boolean) castNonNull(AWAIT_TERMINATION).invokeExact(underlyingClient(), duration);
      } catch (Throwable e) {
        Unchecked.propagateIfUnchecked(e);
        if (e instanceof InterruptedException) {
          throw (InterruptedException) e;
        }
        throw new RuntimeException(e);
      }
    }

    // @Override
    @SuppressWarnings("Since15")
    public boolean isTerminated() {
      try {
        return (boolean) castNonNull(IS_TERMINATED).invokeExact(underlyingClient());
      } catch (Throwable e) {
        Unchecked.propagateIfUnchecked(e);
        throw new RuntimeException(e);
      }
    }

    // @Override
    @SuppressWarnings("Since15")
    public void shutdownNow() {
      try {
        castNonNull(SHUTDOWN_NOW).invokeExact(underlyingClient());
      } catch (Throwable e) {
        Unchecked.propagateIfUnchecked(e);
        throw new RuntimeException(e);
      }
    }

    // @Override
    public void close() {
      try {
        castNonNull(CLOSE).invokeExact(underlyingClient());
      } catch (Throwable e) {
        Unchecked.propagateIfUnchecked(e);
        throw new RuntimeException(e);
      }
    }
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
    static Interceptor create(Function<HttpRequest, HttpRequest> operator) {
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

      /** Returns a new chain after applying the given function to this chain's body handler. */
      default Chain<T> with(UnaryOperator<BodyHandler<T>> bodyHandlerTransformer) {
        return withBodyHandler(bodyHandlerTransformer.apply(bodyHandler()));
      }

      /**
       * Returns a new chain after applying the given functions to this chain's body and push
       * promise handlers, and only to the latter if a push promise handler is present.
       */
      default Chain<T> with(
          UnaryOperator<BodyHandler<T>> bodyHandlerTransformer,
          UnaryOperator<PushPromiseHandler<T>> pushPromiseHandlerTransformer) {
        return with(
            bodyHandlerTransformer.apply(bodyHandler()),
            pushPromiseHandler().map(pushPromiseHandlerTransformer).orElse(null));
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
    final HeadersBuilder defaultHeadersBuilder = new HeadersBuilder();

    @MonotonicNonNull String userAgent;
    @MonotonicNonNull URI baseUri;
    @MonotonicNonNull Duration requestTimeout;
    @MonotonicNonNull Duration headersTimeout;
    @MonotonicNonNull Delayer headersTimeoutDelayer;
    @MonotonicNonNull Duration readTimeout;
    @MonotonicNonNull Delayer readTimeoutDelayer;
    @MonotonicNonNull AdapterCodec adapterCodec;
    boolean autoAcceptEncoding = true;

    final List<Interceptor> interceptors = new ArrayList<>();
    final List<Interceptor> backendInterceptors = new ArrayList<>();

    // These fields are put here for convenience, they're only writable by Builder.
    List<HttpCache> caches = List.of();
    @MonotonicNonNull Redirect redirectPolicy;

    BaseBuilder() {}

    /** Calls the given consumer against this builder. */
    @CanIgnoreReturnValue
    public final B apply(Consumer<? super B> consumer) {
      consumer.accept(self());
      return self();
    }

    /**
     * Sets a default {@code User-Agent} header to use when sending requests.
     *
     * @throws IllegalArgumentException if {@code userAgent} is an invalid header value
     */
    @CanIgnoreReturnValue
    public B userAgent(String userAgent) {
      defaultHeadersBuilder.set("User-Agent", userAgent);
      this.userAgent = userAgent;
      return self();
    }

    /**
     * Sets the base {@code URI} with which each outgoing requests' {@code URI} is {@link
     * URI#resolve(URI) resolved}.
     */
    @CanIgnoreReturnValue
    public B baseUri(String uri) {
      return baseUri(URI.create(uri));
    }

    /**
     * Sets the base {@code URI} with which each outgoing requests' {@code URI} is {@link
     * URI#resolve(URI) resolved}.
     */
    @CanIgnoreReturnValue
    public B baseUri(URI uri) {
      this.baseUri = validateUri(uri);
      return self();
    }

    /** Adds the given default header. */
    @CanIgnoreReturnValue
    public B defaultHeader(String name, String value) {
      defaultHeadersBuilder.add(name, value);
      if ("User-Agent".equalsIgnoreCase(name)) {
        userAgent = value;
      }
      return self();
    }

    /** Adds each of the given default headers. */
    @CanIgnoreReturnValue
    public B defaultHeaders(String... headers) {
      requireArgument(
          headers.length > 0 && headers.length % 2 == 0,
          "Illegal number of headers: %d",
          headers.length);
      for (int i = 0; i < headers.length; i += 2) {
        defaultHeader(headers[i], headers[i + 1]);
      }
      return self();
    }

    /** Configures the default headers as specified by the given consumer. */
    @CanIgnoreReturnValue
    public B defaultHeaders(Consumer<HeadersAccumulator<?>> configurator) {
      configurator.accept(defaultHeadersBuilder.asHeadersAccumulator());
      defaultHeadersBuilder
          .lastValue("User-Agent")
          .ifPresent(userAgent -> this.userAgent = userAgent);
      return self();
    }

    /** Sets a default request timeout to use when not explicitly by an {@code HttpRequest}. */
    @CanIgnoreReturnValue
    public B requestTimeout(Duration requestTimeout) {
      this.requestTimeout = requirePositiveDuration(requestTimeout);
      return self();
    }

    /**
     * Sets a timeout that will raise an {@link HttpHeadersTimeoutException} if all response headers
     * aren't received within the timeout. Timeout events are scheduled using a system-wide {@code
     * ScheduledExecutorService}.
     */
    @CanIgnoreReturnValue
    public B headersTimeout(Duration headersTimeout) {
      return headersTimeout(headersTimeout, Delayer.systemDelayer());
    }

    /**
     * Same as {@link #headersTimeout(Duration)} but specifies a {@code ScheduledExecutorService} to
     * use for scheduling timeout events.
     */
    @CanIgnoreReturnValue
    public B headersTimeout(Duration headersTimeout, ScheduledExecutorService scheduler) {
      return headersTimeout(headersTimeout, Delayer.of(scheduler));
    }

    @CanIgnoreReturnValue
    B headersTimeout(Duration headersTimeout, Delayer delayer) {
      this.headersTimeout = requirePositiveDuration(headersTimeout);
      this.headersTimeoutDelayer = requireNonNull(delayer);
      return self();
    }

    /**
     * Sets a default {@link MoreBodySubscribers#withReadTimeout(BodySubscriber, Duration) read
     * timeout}. Timeout events are scheduled using a system-wide {@code ScheduledExecutorService}.
     */
    @CanIgnoreReturnValue
    public B readTimeout(Duration readTimeout) {
      return readTimeout(readTimeout, Delayer.systemDelayer());
    }

    /**
     * Sets a default {@link MoreBodySubscribers#withReadTimeout(BodySubscriber, Duration,
     * ScheduledExecutorService) readtimeout} using the given {@code ScheduledExecutorService} for
     * scheduling timeout events.
     */
    @CanIgnoreReturnValue
    public B readTimeout(Duration readTimeout, ScheduledExecutorService scheduler) {
      return readTimeout(readTimeout, Delayer.of(scheduler));
    }

    @CanIgnoreReturnValue
    private B readTimeout(Duration readTimeout, Delayer delayer) {
      this.readTimeout = requirePositiveDuration(readTimeout);
      this.readTimeoutDelayer = requireNonNull(delayer);
      return self();
    }

    /** Specifies the {@code AdapterCodec} with which request and response payloads are mapped. */
    @CanIgnoreReturnValue
    public B adapterCodec(AdapterCodec adapterCodec) {
      this.adapterCodec = requireNonNull(adapterCodec);
      return self();
    }

    /**
     * If enabled, each request will have an {@code Accept-Encoding} header appended, the value of
     * which is the set of {@link Factory#installedBindings() supported encodings}. Additionally,
     * each received response will be transparently decompressed by wrapping its {@code BodyHandler}
     * with {@link MoreBodyHandlers#decoding(BodyHandler)}.
     *
     * <p>This value is {@code true} by default.
     */
    @CanIgnoreReturnValue
    public B autoAcceptEncoding(boolean autoAcceptEncoding) {
      this.autoAcceptEncoding = autoAcceptEncoding;
      return self();
    }

    /**
     * Adds an interceptor that is invoked right after the client receives a request. The
     * interceptor receives the request before it is decorated (its {@code URI} resolved with the
     * base {@code URI}, default headers added, etc...) or handled by an {@link HttpCache}.
     */
    @CanIgnoreReturnValue
    public B interceptor(Interceptor interceptor) {
      interceptors.add(requireNonNull(interceptor));
      return self();
    }

    /**
     * Adds an interceptor that is invoked right before the request is forwarded to the client's
     * backend. The interceptor receives the request after it is handled by all {@link
     * #interceptor(Interceptor) client interceptors}, is decorated (its {@code URI} resolved with
     * the base {@code URI}, default headers added, etc...) and finally handled by an {@link
     * HttpCache}. This implies that backend interceptors aren't called if network isn't used,
     * normally due to the presence of an {@code HttpCache} that is capable of serving a stored
     * response.
     */
    @CanIgnoreReturnValue
    public B backendInterceptor(Interceptor interceptor) {
      backendInterceptors.add(requireNonNull(interceptor));
      return self();
    }

    /**
     * @deprecated Use {@link #backendInterceptor(Interceptor)}
     */
    @CanIgnoreReturnValue
    @Deprecated(since = "1.5.0")
    @InlineMe(replacement = "this.backendInterceptor(interceptor)")
    public final B postDecorationInterceptor(Interceptor interceptor) {
      return backendInterceptor(interceptor);
    }

    /** Creates a new {@code Methanol} instance. */
    public Methanol build() {
      return SHUTDOWN != null ? new MethanolForJava21AndLater(this) : new Methanol(this);
    }

    abstract B self();

    abstract HttpClient buildBackend();

    // Currently used in tests.
    @CanIgnoreReturnValue
    B clearInterceptors() {
      interceptors.clear();
      backendInterceptors.clear();
      return self();
    }
  }

  /** A builder for {@code Methanol} instances with a pre-specified backend {@code HttpClient}. */
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
  public static class Builder extends BaseBuilder<Builder> implements HttpClient.Builder {
    private static final @Nullable MethodHandle LOCAL_ADDRESS; // Since Java 19.

    static {
      MethodHandle localAddress;
      try {
        localAddress =
            MethodHandles.lookup()
                .findVirtual(
                    HttpClient.Builder.class,
                    "localAddress",
                    MethodType.methodType(HttpClient.Builder.class, InetAddress.class));
      } catch (NoSuchMethodException e) {
        localAddress = null;
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
      LOCAL_ADDRESS = localAddress;
    }

    final HttpClient.Builder backendBuilder = HttpClient.newBuilder();

    private Builder() {}

    /** Sets the {@link HttpCache} to be used by the client. */
    @CanIgnoreReturnValue
    public Builder cache(HttpCache cache) {
      super.caches = List.of(cache);
      return this;
    }

    /**
     * Sets a chain of caches to be called one after another, in the order specified by the given
     * list. Each cache forwards to the other till a suitable response is found or the request is
     * sent to network. Although not enforced, it is highly recommended for the caches to be sorted
     * in the order of decreasing locality.
     */
    @CanIgnoreReturnValue
    public Builder cacheChain(List<HttpCache> caches) {
      var cachesCopy = List.copyOf(caches);
      requireArgument(!cachesCopy.isEmpty(), "Must have at least one cache in the chain");
      this.caches = cachesCopy;
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public Builder cookieHandler(CookieHandler cookieHandler) {
      backendBuilder.cookieHandler(cookieHandler);
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public Builder connectTimeout(Duration duration) {
      backendBuilder.connectTimeout(duration);
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public Builder sslContext(SSLContext sslContext) {
      backendBuilder.sslContext(sslContext);
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public Builder sslParameters(SSLParameters sslParameters) {
      backendBuilder.sslParameters(sslParameters);
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public Builder executor(Executor executor) {
      backendBuilder.executor(executor);
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public Builder followRedirects(Redirect policy) {
      // Don't apply policy to base client until build() is called to know whether
      // a RedirectingInterceptor is to be used instead in case a cache is installed.
      redirectPolicy = requireNonNull(policy);
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public Builder version(Version version) {
      backendBuilder.version(version);
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public Builder priority(int priority) {
      backendBuilder.priority(priority);
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public Builder proxy(ProxySelector proxySelector) {
      backendBuilder.proxy(proxySelector);
      return this;
    }

    @Override
    @CanIgnoreReturnValue
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
      // Apply redirectPolicy if no caches are set. In such case we let the backend handle
      // redirects.
      if (caches.isEmpty() && redirectPolicy != null) {
        backendBuilder.followRedirects(redirectPolicy);
      }
      return backendBuilder.build();
    }

    static Builder create() {
      return LOCAL_ADDRESS != null ? new BuilderForJava19AndLater() : new Builder();
    }

    private static final class BuilderForJava19AndLater extends Builder {
      BuilderForJava19AndLater() {}

      // Note that the return type MUST be exactly the same as the overridden method's return type
      // (in HttpClient.Builder). This is because we compile under Java 11 (or later with a
      // --release 11 flag). When the override is covariant, the JVM will call the super interface
      // method and not this one, because the exact return type is part of the method signature, and
      // the compiler doesn't generate a synthetic method with the overridden function's signature
      // because it didn't exist while compiling.
      // @Override
      @SuppressWarnings("Since15")
      public HttpClient.Builder localAddress(InetAddress localAddr) {
        try {
          castNonNull(LOCAL_ADDRESS).invoke(backendBuilder, localAddr);
          return this;
        } catch (Throwable e) {
          Unchecked.propagateIfUnchecked(e);
          throw new RuntimeException(e);
        }
      }
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
      this.backend = requireNonNull(backend);
      this.bodyHandler = requireNonNull(bodyHandler);
      this.pushPromiseHandler = pushPromiseHandler;
      this.interceptors = requireNonNull(interceptors);
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
      return interceptor.intercept(request, nextInterceptorChain());
    }

    @Override
    public CompletableFuture<HttpResponse<T>> forwardAsync(HttpRequest request) {
      requireNonNull(request);
      if (currentInterceptorIndex >= interceptors.size()) {
        // sendAsync accepts a nullable pushPromiseHandler.
        return backend.sendAsync(request, bodyHandler, pushPromiseHandler);
      }

      var interceptor = interceptors.get(currentInterceptorIndex);
      return interceptor.interceptAsync(request, nextInterceptorChain());
    }

    private InterceptorChain<T> nextInterceptorChain() {
      return new InterceptorChain<>(
          backend, bodyHandler, pushPromiseHandler, interceptors, currentInterceptorIndex + 1);
    }
  }

  /** An interceptor that rewrites requests as configured. */
  private static final class RequestRewritingInterceptor implements Interceptor {
    private final Optional<URI> baseUri;
    private final Optional<Duration> requestTimeout;
    private final Optional<AdapterCodec> adapterCodec;
    private final HttpHeaders defaultHeaders;
    private final boolean autoAcceptEncoding;

    RequestRewritingInterceptor(
        Optional<URI> baseUri,
        Optional<Duration> requestTimeout,
        Optional<AdapterCodec> adapterCodec,
        HttpHeaders defaultHeaders,
        boolean autoAcceptEncoding) {
      this.baseUri = baseUri;
      this.requestTimeout = requestTimeout;
      this.adapterCodec = adapterCodec;
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
      if (rewrittenRequest.adapterCodec().isEmpty()) {
        adapterCodec.ifPresent(rewrittenRequest::adapterCodec);
      }

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

      // Overwrite Content-Type if the request body has a MediaType.
      rewrittenRequest
          .mimeBody()
          .map(MimeBody::mediaType)
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
      // HEADs don't have bodies, so no decompression is needed.
      if ("HEAD".equalsIgnoreCase(request.method())) {
        return chain;
      }

      return chain.with(
          MoreBodyHandlers::decoding,
          pushPromiseHandler ->
              transformPushPromiseHandler(
                  pushPromiseHandler,
                  MoreBodyHandlers::decoding,
                  AutoDecompressingInterceptor::stripContentEncoding));
    }

    private static <T> HttpResponse<T> stripContentEncoding(HttpResponse<T> response) {
      // Don't strip if the response wasn't decompressed.
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
      return Utils.get(interceptAsync(request, chain));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
        HttpRequest request, Chain<T> chain) {
      var timeoutTrigger = new TimeoutTrigger();
      var triggerFuture =
          delayer.delay(timeoutTrigger::trigger, headersTimeout, FlowSupport.SYNC_EXECUTOR);
      timeoutTrigger.onCancellation(() -> triggerFuture.cancel(false));

      var responseFuture = withHeadersTimeout(chain, timeoutTrigger).forwardAsync(request);

      // Make a dependent copy of the original response future, so we can cancel the original and
      // complete the copy exceptionally on timeout. Cancelling the original future may lead
      // to cancelling the actual request on JDK 16 or higher.
      var responseFutureCopy = responseFuture.copy();
      timeoutTrigger.onTimeout(
          () -> {
            responseFutureCopy.completeExceptionally(
                new HttpHeadersTimeoutException("couldn't receive headers on time"));
            responseFuture.cancel(true);
          });
      return responseFutureCopy;
    }

    private <T> Chain<T> withHeadersTimeout(Chain<T> chain, TimeoutTrigger timeoutTrigger) {
      // TODO handle push promises
      return chain.with(
          bodyHandler ->
              responseInfo ->
                  timeoutTrigger.cancel()
                      ? bodyHandler.apply(responseInfo)
                      : new TimedOutSubscriber<>());
    }

    private static final class TimeoutTrigger {
      private final CompletableFuture<Void> onTimeout = new CompletableFuture<>();

      TimeoutTrigger() {}

      void trigger() {
        onTimeout.complete(null);
      }

      @SuppressWarnings("FutureReturnValueIgnored")
      void onTimeout(Runnable action) {
        onTimeout.thenRun(action);
      }

      @SuppressWarnings("FutureReturnValueIgnored")
      void onCancellation(Runnable action) {
        onTimeout.whenComplete(
            (__, e) -> {
              if (e instanceof CancellationException) {
                action.run();
              }
            });
      }

      boolean cancel() {
        return onTimeout.cancel(false);
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
    private final Delayer delayer;

    ReadTimeoutInterceptor(Duration readTimeout, Delayer delayer) {
      this.readTimeout = readTimeout;
      this.delayer = delayer;
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
          bodyHandler -> MoreBodyHandlers.withReadTimeout(bodyHandler, readTimeout, delayer),
          pushPromiseHandler ->
              transformPushPromiseHandler(
                  pushPromiseHandler,
                  bodyHandler ->
                      MoreBodyHandlers.withReadTimeout(bodyHandler, readTimeout, delayer),
                  UnaryOperator.identity()));
    }
  }
}
