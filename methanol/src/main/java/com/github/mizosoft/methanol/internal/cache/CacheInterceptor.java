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

package com.github.mizosoft.methanol.internal.cache;

import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_GATEWAY_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_GONE;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MULT_CHOICE;
import static java.net.HttpURLConnection.HTTP_NOT_AUTHORITATIVE;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_NOT_IMPLEMENTED;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PARTIAL;
import static java.net.HttpURLConnection.HTTP_REQ_TOO_LONG;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.CacheAwareResponse;
import com.github.mizosoft.methanol.CacheAwareResponse.CacheStatus;
import com.github.mizosoft.methanol.CacheControl;
import com.github.mizosoft.methanol.HttpCache.Listener;
import com.github.mizosoft.methanol.HttpStatus;
import com.github.mizosoft.methanol.Methanol.Interceptor;
import com.github.mizosoft.methanol.ResponseBuilder;
import com.github.mizosoft.methanol.TrackedResponse;
import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.concurrent.CancellationPropagatingFuture;
import com.github.mizosoft.methanol.internal.extensions.Handlers;
import com.github.mizosoft.methanol.internal.extensions.HeadersBuilder;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.text.CharMatcher;
import com.github.mizosoft.methanol.internal.text.HeaderValueTokenizer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient.Version;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An {@link Interceptor} that serves incoming requests from cache, network, both (in case of
 * successful/failed revalidation), or none (in case of unsatisfiable requests). The interceptor
 * also updates, populates and invalidates cache entries as necessary.
 */
public final class CacheInterceptor implements Interceptor {
  private static final Logger logger = System.getLogger(CacheInterceptor.class.getName());

  private static final Set<String> RETAINED_STORED_HEADERS;
  private static final Set<String> RETAINED_STORED_HEADER_PREFIXES;

  static {
    // Replacing stored headers with these names (or prefixes) from a 304 response is prohibited.
    // These lists are based on Chromium's http_response_headers.cc (see lists kNonUpdatedHeaders &
    // kNonUpdatedHeaderPrefixes).
    RETAINED_STORED_HEADERS = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    RETAINED_STORED_HEADERS.addAll(
        Set.of(
            "Connection",
            "Proxy-Connection",
            "Keep-Alive",
            "WWW-Authenticate",
            "Proxy-Authenticate",
            "Proxy-Authorization",
            "TE",
            "Trailer",
            "Transfer-Encoding",
            "Upgrade",
            "Content-Location",
            "Content-MD5",
            "ETag",
            "Content-Encoding",
            "Content-Range",
            "Content-Type",
            "Content-Length",
            "X-Frame-Options",
            "X-XSS-Protection"));
    RETAINED_STORED_HEADER_PREFIXES = Set.of("X-Content-", "X-Webkit-");
  }

  /** Matcher for valid characters in an entity-tag, as specified by rfc7232 Section 2.3. */
  private static final CharMatcher ETAG_C_MATCHER =
      CharMatcher.is(0x21)
          .or(CharMatcher.withinClosedRange(0x23, 0x7e))
          .or(CharMatcher.withinClosedRange(0x80, 0xff));

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  private static final Optional<Boolean> TRUE_OPTIONAL = Optional.of(true);

  private final LocalCache.Factory cacheFactory;
  private final Listener listener;
  private final Executor handlerExecutor;
  private final Clock clock;
  private final boolean synchronizeWrites;
  private final Predicate<String> implicitHeaderPredicate;

  public CacheInterceptor(
      LocalCache.Factory cacheFactory,
      Listener listener,
      Executor handlerExecutor,
      Clock clock,
      boolean synchronizeWrites,
      Predicate<String> implicitHeaderPredicate) {
    this.cacheFactory = requireNonNull(cacheFactory);
    this.listener = requireNonNull(listener);
    this.handlerExecutor = requireNonNull(handlerExecutor);
    this.clock = requireNonNull(clock);
    this.synchronizeWrites = synchronizeWrites;
    this.implicitHeaderPredicate = requireNonNull(implicitHeaderPredicate);
  }

  @Override
  public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
      throws IOException, InterruptedException {
    return Utils.get(exchange(request, chain, false)).handle(chain.bodyHandler());
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
      HttpRequest request, Chain<T> chain) {
    return exchange(request, chain, true)
        .thenCompose(rawResponse -> rawResponse.handleAsync(chain.bodyHandler(), handlerExecutor))
        .thenApply(Function.identity()); // TrackedResponse<T> -> HttpResponse<T>
  }

  private CompletableFuture<RawResponse> exchange(
      HttpRequest request, Chain<?> chain, boolean async) {
    var publisherChain = Handlers.toPublisherChain(chain, handlerExecutor);
    return new Exchange(
            request,
            cacheFactory.instance(async),
            async ? ChainAdapter.async(publisherChain) : ChainAdapter.syncOnCaller(publisherChain))
        .exchange();
  }

  /** Returns whether the given network response can be cached. Based on rfc7234 Section 3. */
  private boolean isCacheable(HttpRequest request, TrackedResponse<?> response) {
    if (isNotSupported(request)
        || !request.uri().equals(response.uri())
        || !request.method().equalsIgnoreCase(response.request().method())) {
      return false;
    }

    // Skip partial content.
    if (response.statusCode() == HTTP_PARTIAL) {
      return false;
    }

    CacheControl responseCacheControl;
    try {
      responseCacheControl = CacheControl.parse(response.headers());
    } catch (IllegalArgumentException e) {
      // Don't crash because of server's ill-formed Cache-Control. rfc9111 says in section 4.2.1.
      // that we're encouraged to consider such responses stale. But we take the more conservative
      // approach of not storing the response in the first place.
      logger.log(Level.WARNING, "Invalid response Cache-Control", e);
      return false;
    }

    if (responseCacheControl.noStore() || CacheControl.parse(request.headers()).noStore()) {
      return false;
    }

    Set<String> varyFields;
    try {
      varyFields = CacheResponseMetadata.varyFields(response.headers());
    } catch (IllegalArgumentException e) {
      // Don't crash because of server's ill-formed Vary.
      logger.log(Level.WARNING, "Invalid response Vary", e);
      return false;
    }

    // Skip if the response is unmatchable or varies with fields what we can't access.
    if (varyFields.contains("*") || varyFields.stream().anyMatch(implicitHeaderPredicate)) {
      return false;
    }

    return responseCacheControl.maxAge().isPresent()
        || responseCacheControl.isPublic()
        || responseCacheControl.isPrivate()
        || isHeuristicallyCacheable(response.statusCode())
        || response.headers().firstValue("Expires").filter(HttpDates::isHttpDate).isPresent();
  }

  private static boolean isNotSupported(HttpRequest request) {
    return !isSupportedRequestMethod(request.method())
        || !request.headers().map().keySet().stream()
            .allMatch(CacheInterceptor::isSupportedRequestHeader);
  }

  private static boolean isSupportedRequestMethod(String method) {
    return method.equalsIgnoreCase("GET");
  }

  private static boolean isSupportedRequestHeader(String name) {
    // The only headers we don't support are preconditions that we don't evaluate.
    return !name.startsWith("If-")
        || name.equalsIgnoreCase("If-None-Match")
        || name.equalsIgnoreCase("If-Modified-Since");
  }

  @SuppressWarnings("NullAway")
  private static boolean isNetworkOrServerError(
      @Nullable NetworkResponse networkResponse, @Nullable Throwable exception) {
    assert networkResponse != null ^ exception != null;
    if (networkResponse != null) {
      return HttpStatus.isServerError(networkResponse.get());
    }

    // Situational errors for network usage are considered for stale-if-error treatment, as they're
    // similar to 5xx response codes (but on the client side), which are perfect candidates for
    // stale-if-error.
    var cause = Utils.getDeepCompletionCause(exception); // Might be a CompletionException.
    if (cause instanceof UncheckedIOException) {
      cause = cause.getCause();
    }
    return cause instanceof ConnectException || cause instanceof UnknownHostException;
  }

  /**
   * Returns whether a response with the given code can be cached based on heuristic expiry in case
   * explicit expiry is absent. Based on rfc7231 Section 6.1.
   */
  private static boolean isHeuristicallyCacheable(int statusCode) {
    switch (statusCode) {
      case HTTP_OK:
      case HTTP_NOT_AUTHORITATIVE:
      case HTTP_NO_CONTENT:
      case HTTP_MULT_CHOICE:
      case HTTP_MOVED_PERM:
      case HTTP_NOT_FOUND:
      case HTTP_BAD_METHOD:
      case HTTP_GONE:
      case HTTP_REQ_TOO_LONG:
      case HTTP_NOT_IMPLEMENTED:
        return true;
      case HTTP_PARTIAL:
      // Although partial responses are heuristically cacheable, they're not supported by this
      // implementation.
      default:
        return false;
    }
  }

  /**
   * Updates the cache response after successful revalidation as specified by rfc7234 Section 4.3.4.
   */
  private static CacheResponse updateCacheResponse(
      CacheResponse cacheResponse, NetworkResponse networkResponse) {
    return cacheResponse.with(
        builder ->
            builder
                .removeHeaders()
                .headers(
                    mergeHeaders(cacheResponse.get().headers(), networkResponse.get().headers()))
                .timeRequestSent(networkResponse.get().timeRequestSent())
                .timeResponseReceived(networkResponse.get().timeResponseReceived()));
  }

  private static HttpHeaders mergeHeaders(HttpHeaders storedHeaders, HttpHeaders networkHeaders) {
    var builder = new HeadersBuilder();
    builder.addAllLenient(storedHeaders);
    networkHeaders
        .map()
        .forEach(
            (name, values) -> {
              if (canReplaceStoredHeader(name)) {
                builder.setLenient(name, values);
              }
            });

    // Remove 1xx Warning codes.
    builder.removeIf((name, value) -> name.equalsIgnoreCase("Warning") && value.startsWith("1"));
    return builder.build();
  }

  // Visible for testing.
  public static boolean canReplaceStoredHeader(String name) {
    return !(RETAINED_STORED_HEADERS.contains(name)
        || RETAINED_STORED_HEADER_PREFIXES.stream()
            .anyMatch(prefix -> Utils.startsWithIgnoreCase(name, prefix))
        || name.equals(":status"));
  }

  /** Returns the URIs invalidated by the given exchange as specified by rfc7234 Section 4.4. */
  private static List<URI> invalidatedUris(HttpRequest request, TrackedResponse<?> response) {
    if (isUnsafe(request.method())
        && (HttpStatus.isSuccessful(response) || HttpStatus.isRedirection(response))) {
      var invalidatedUris = new ArrayList<URI>();
      invalidatedUris.add(request.uri());
      invalidatedLocationUri(request.uri(), response.headers(), "Location")
          .ifPresent(invalidatedUris::add);
      invalidatedLocationUri(request.uri(), response.headers(), "Content-Location")
          .ifPresent(invalidatedUris::add);
      return Collections.unmodifiableList(invalidatedUris);
    } else {
      return List.of();
    }
  }

  private static Optional<URI> invalidatedLocationUri(
      URI requestUri, HttpHeaders responseHeaders, String locationField) {
    return responseHeaders
        .firstValue(locationField)
        .map(requestUri::resolve)
        .filter(resolvedUri -> Objects.equals(requestUri.getHost(), resolvedUri.getHost()));
  }

  /**
   * Returns whether the given request method is unsafe, and hence may invalidate cached entries.
   * Based on rfc7231 Section 4.2.1.
   */
  private static boolean isUnsafe(String method) {
    return !method.equalsIgnoreCase("GET")
        && !method.equalsIgnoreCase("HEAD")
        && !method.equalsIgnoreCase("OPTIONS")
        && !method.equalsIgnoreCase("TRACE");
  }

  /**
   * Evaluates given request's preconditions against the given cache response. Only {@code
   * If-None-Match} and {@code If-Modified-Since} are evaluated as requests with other preconditions
   * are forwarded to the origin.
   */
  private static boolean evaluatePreconditions(
      HttpRequest request, TrackedResponse<?> cacheResponse) {
    return evaluateIfNoneMatch(request, cacheResponse)
        .or(() -> evaluateIfModifiedSince(request, cacheResponse))
        .orElse(true);
  }

  private static Optional<Boolean> evaluateIfNoneMatch(
      HttpRequest request, TrackedResponse<?> cacheResponse) {
    var ifNoneMatch = request.headers().allValues("If-None-Match");
    if (!ifNoneMatch.isEmpty()) {
      return cacheResponse
          .headers()
          .firstValue("ETag")
          .map(etag -> !anyMatch(ifNoneMatch, etag))
          .or(() -> TRUE_OPTIONAL); // Assume a hypothetical etag that matches with nothing.
    }
    return Optional.empty();
  }

  private static Optional<Boolean> evaluateIfModifiedSince(
      HttpRequest request, TrackedResponse<?> cacheResponse) {
    return request
        .headers()
        .firstValue("If-Modified-Since")
        .flatMap(HttpDates::tryParseHttpDate)
        .map(value -> isModifiedSince(cacheResponse, value));
  }

  private static boolean anyMatch(List<String> candidates, String target) {
    // Fast path for single etag.
    if (candidates.size() == 1 && !candidates.get(0).contains(",")) {
      return !candidates.get(0).equals("*") && weaklyMatches(target, candidates.get(0));
    }

    // If-None-Match = "*" / 1#entity-tag
    // entity-tag = [ weak ] opaque-tag
    // weak       = %x57.2F ; "W/", case-sensitive
    // opaque-tag = DQUOTE *etagc DQUOTE
    // etagc      = %x21 / %x23-7E / obs-text
    //            ; VCHAR except double quotes, plus obs-text
    try {
      for (var value : candidates) {
        var tokenizer = new HeaderValueTokenizer(value);
        do {
          tokenizer.consumeIfPresent("W/");
          tokenizer.requireCharacter('"');
          var candidate = tokenizer.nextMatching(ETAG_C_MATCHER);
          tokenizer.requireCharacter('"');
          if (weaklyMatches(target, "\"" + candidate + "\"")) {
            return true;
          }
        } while (tokenizer.consumeDelimiter(','));
      }
    } catch (IllegalArgumentException e) {
      logger.log(Level.WARNING, "Exception while parsing candidate E-Tags, assuming a no-match", e);
    }
    return false;
  }

  /**
   * Returns whether the given entity tags match as per the weak comparison rule specified by
   * rfc7232 Section 2.3.2. Weak comparison must be used when evaluating {@code If-None-Match} as
   * specified by rfc7232 Section 3.2.
   */
  private static boolean weaklyMatches(String firstTag, String secondTag) {
    int firstTagBegin = 0;
    if (firstTag.startsWith("W/")) {
      firstTagBegin += 2;
    }

    int secondTagBegin = 0;
    if (secondTag.startsWith("W/")) {
      secondTagBegin += 2;
    }

    int firstTagLength = firstTag.length() - firstTagBegin;
    if (firstTagLength != secondTag.length() - secondTagBegin) {
      return false;
    }

    // Entity tags must be quoted.
    if (firstTagLength < 2
        || firstTag.charAt(firstTagBegin) != '"'
        || firstTag.charAt(firstTag.length() - 1) != '"'
        || secondTag.charAt(secondTagBegin) != '"'
        || secondTag.charAt(secondTag.length() - 1) != '"') {
      return false;
    }

    return firstTag.regionMatches(firstTagBegin, secondTag, secondTagBegin, firstTagLength);
  }

  private static boolean isModifiedSince(TrackedResponse<?> cacheResponse, LocalDateTime dateTime) {
    return cacheResponse
        .headers()
        .firstValue("Last-Modified")
        .or(() -> cacheResponse.headers().firstValue("Date"))
        .flatMap(HttpDates::tryParseHttpDate)
        .orElseGet(() -> HttpDates.toUtcDateTime(cacheResponse.timeResponseReceived()))
        .isAfter(dateTime);
  }

  private static <T> TrackedResponse<T> toTrackedResponse(
      HttpResponse<T> response, Instant requestTime, Clock clock) {
    return response instanceof TrackedResponse<?>
        ? (TrackedResponse<T>) response
        : ResponseBuilder.from(response)
            .timeRequestSent(requestTime)
            .timeResponseReceived(clock.instant())
            .buildTrackedResponse();
  }

  private static final class DrainingBodySubscriber implements BodySubscriber<Void> {
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private final AtomicBoolean subscribed = new AtomicBoolean();

    DrainingBodySubscriber() {}

    @Override
    public CompletionStage<Void> getBody() {
      return completion;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      requireNonNull(subscription);
      if (subscribed.compareAndSet(false, true)) {
        subscription.request(Long.MAX_VALUE);
      } else {
        subscription.cancel();
      }
    }

    @Override
    public void onNext(List<ByteBuffer> item) {
      requireNonNull(item);
    }

    @Override
    public void onError(Throwable throwable) {
      completion.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
      completion.complete(null);
    }
  }

  private static class CacheRetrieval {
    final CacheResponse response;
    final CacheStrategy strategy;
    final boolean owned;

    CacheRetrieval(CacheResponse response, CacheStrategy strategy) {
      this(response, strategy, true);
    }

    CacheRetrieval(CacheResponse response, CacheStrategy strategy, boolean owned) {
      this.response = response;
      this.strategy = strategy;
      this.owned = owned;
    }

    CacheRetrieval unowned() {
      return new CacheRetrieval(response, strategy, false);
    }

    void closeResponse() {
      if (owned) {
        response.close();
      }
    }
  }

  private final class Exchange {
    final HttpRequest request;
    final CacheControl requestCacheControl;
    final LocalCache cache;
    final ChainAdapter chainAdapter;

    Exchange(HttpRequest request, LocalCache cache, ChainAdapter chainAdapter) {
      this.request = request;
      this.requestCacheControl = CacheControl.parse(request.headers());
      this.cache = cache;
      this.chainAdapter = chainAdapter;
    }

    CompletableFuture<RawResponse> exchange() {
      listener.onRequest(request);
      var requestTime = clock.instant();
      return CancellationPropagatingFuture.of(retrieveCacheResponse(requestTime))
          .thenCompose(
              optionalCacheRetrieval ->
                  optionalCacheRetrieval
                      .map(
                          cacheRetrieval ->
                              exchange(requestTime, cacheRetrieval)
                                  .whenComplete(
                                      (__, ex) -> {
                                        // Make sure the CacheResponse is always released.
                                        if (ex != null) {
                                          cacheRetrieval.closeResponse();
                                        }
                                      }))
                      .orElseGet(() -> exchange(requestTime, null)))
          .thenApply(
              response -> {
                listener.onResponse(request, (CacheAwareResponse<?>) response.get());
                return response;
              });
    }

    private CompletableFuture<Optional<CacheRetrieval>> retrieveCacheResponse(Instant requestTime) {
      if (isNotSupported(request) || chainAdapter.chain().pushPromiseHandler().isPresent()) {
        return CompletableFuture.completedFuture(Optional.empty());
      }
      return cache
          .get(request)
          .exceptionally(
              exception -> {
                listener.onReadFailure(request, exception);
                return Optional.empty();
              })
          .thenApply(
              optionalCacheResponse ->
                  optionalCacheResponse.map(
                      cacheResponse ->
                          new CacheRetrieval(
                              cacheResponse,
                              CacheStrategy.create(
                                  requestCacheControl, cacheResponse, requestTime))));
    }

    private CompletableFuture<RawResponse> exchange(
        Instant requestTime, @Nullable CacheRetrieval cacheRetrieval) {
      if (cacheRetrieval != null && cacheRetrieval.strategy.isCacheResponseServable()) {
        if (cacheRetrieval.strategy.requiresBackgroundRevalidation()) {
          revalidateInBackground(requestTime, cacheRetrieval.unowned());
        }
        return CompletableFuture.completedFuture(serveFromCache(requestTime, cacheRetrieval));
      }

      // Don't use the network if the request prohibits it.
      if (requestCacheControl.onlyIfCached()) {
        return CompletableFuture.completedFuture(
            serveUnsatisfiableRequest(requestTime, cacheRetrieval));
      }

      listener.onNetworkUse(request, cacheRetrieval != null ? cacheRetrieval.response.get() : null);
      return exchangeWithNetwork(
              cacheRetrieval != null ? cacheRetrieval.strategy.conditionalize(request) : request,
              requestTime)
          .handle(
              (networkResponse, exception) ->
                  handleNetworkOrServerError(networkResponse, exception, cacheRetrieval))
          .thenCompose(networkResponse -> exchange(requestTime, cacheRetrieval, networkResponse));
    }

    @SuppressWarnings({"NullAway", "FutureReturnValueIgnored"})
    private CompletableFuture<RawResponse> exchange(
        Instant requestTime,
        @Nullable CacheRetrieval cacheRetrieval,
        @Nullable NetworkResponse networkResponse) {
      assert cacheRetrieval != null || networkResponse != null;

      // A null networkResponse indicates that network has failed but the cache response is
      // servable, as specified by stale-if-error.
      if (networkResponse == null) {
        return CompletableFuture.completedFuture(serveFromCache(requestTime, cacheRetrieval));
      }

      // If we have a cacheRetrieval, this may be a successful revalidation.
      if (cacheRetrieval != null && networkResponse.get().statusCode() == HTTP_NOT_MODIFIED) {
        networkResponse.discard(handlerExecutor); // Release.
        return serveFromCacheAfterUpdating(cacheRetrieval, networkResponse);
      }

      if (isCacheable(request, networkResponse.get())) {
        return cache
            .put(request, networkResponse, cacheRetrieval != null ? cacheRetrieval.response : null)
            .exceptionally(
                exception -> {
                  listener.onWriteFailure(request, exception);
                  return Optional.empty();
                })
            .thenApply(
                cacheUpdatingNetworkResponse ->
                    serveFromNetwork(
                        cacheUpdatingNetworkResponse.orElse(networkResponse), cacheRetrieval));
      }

      // An uncacheable response might invalidate itself and other related responses.
      var invalidatedUris = invalidatedUris(request, networkResponse.get());
      if (!invalidatedUris.isEmpty()) {
        cache
            .removeAll(invalidatedUris)
            .whenComplete(
                (__, ex) -> {
                  if (ex != null) {
                    logger.log(Level.WARNING, "Exception when removing entries", ex);
                  }
                });
      }
      return CompletableFuture.completedFuture(serveFromNetwork(networkResponse, cacheRetrieval));
    }

    private CompletableFuture<NetworkResponse> exchangeWithNetwork(
        HttpRequest request, Instant requestTime) {
      return exchangeWithNetwork(request, requestTime, chainAdapter::forward);
    }

    private CompletableFuture<NetworkResponse> exchangeWithNetworkInBackground(
        HttpRequest request, Instant requestTime) {
      return exchangeWithNetwork(
          request,
          requestTime,
          chainAdapter.chain()::forwardAsync); // Ensure asynchronous execution.
    }

    private CompletableFuture<NetworkResponse> exchangeWithNetwork(
        HttpRequest request,
        Instant requestTime,
        Function<HttpRequest, CompletableFuture<HttpResponse<Publisher<List<ByteBuffer>>>>>
            forwarder) {
      return forwarder
          .apply(request)
          .thenApply(
              response -> NetworkResponse.of(toTrackedResponse(response, requestTime, clock)));
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void revalidateInBackground(Instant requestTime, CacheRetrieval cacheRetrieval) {
      // TODO implement a bounding policy on asynchronous revalidation & find a mechanism to
      //      notify caller for revalidation's completion.
      listener.onNetworkUse(request, cacheRetrieval.response.get());
      exchangeWithNetworkInBackground(cacheRetrieval.strategy.conditionalize(request), requestTime)
          .thenCompose(networkResponse -> exchange(requestTime, cacheRetrieval, networkResponse))
          .whenComplete(this::handleBackgroundRevalidation);
    }

    @SuppressWarnings({"NullAway", "FutureValueIsIgnored"})
    private void handleBackgroundRevalidation(
        @Nullable RawResponse response, @Nullable Throwable exception) {
      assert response != null ^ exception != null;
      if (response instanceof NetworkResponse) {
        // Make sure the network response is released properly. If we're updating a cache entry, the
        // entire response body must be consumed.
        var networkResponse = (NetworkResponse) response;
        if (networkResponse.isCacheUpdating()) {
          networkResponse
              .handleAsync(__ -> new DrainingBodySubscriber(), handlerExecutor)
              .whenComplete(
                  (__, ex) -> {
                    if (ex != null) {
                      logger.log(Level.WARNING, "Asynchronous revalidation failure", ex);
                    }
                  });
        } else {
          networkResponse.discard(handlerExecutor); // Release.
        }
      } else if (exception != null) {
        logger.log(Level.WARNING, "Asynchronous revalidation failure", exception);
      }
    }

    /**
     * Handles an error in a network exchange, falling back to the cache response (indicated by
     * returning null) if one that satisfies stale-if-error is available.
     */
    @SuppressWarnings("NullAway")
    private @Nullable NetworkResponse handleNetworkOrServerError(
        @Nullable NetworkResponse networkResponse,
        @Nullable Throwable exception,
        @Nullable CacheRetrieval cacheRetrieval) {
      assert networkResponse != null ^ exception != null;
      if (isNetworkOrServerError(networkResponse, exception)
          && cacheRetrieval != null
          && cacheRetrieval.strategy.isCacheResponseServableOnError()) {
        if (networkResponse != null) {
          networkResponse.discard(handlerExecutor); // Release.
        }
        return null;
      }

      // stale-if-error isn't satisfied. Forward exception or networkResponse as is.
      if (exception != null) {
        if (cacheRetrieval != null) {
          cacheRetrieval.closeResponse();
        }
        throw Utils.toCompletionException(exception);
      }
      return networkResponse;
    }

    private RawResponse serveFromCache(Instant requestTime, CacheRetrieval cacheRetrieval) {
      // As per rfc7234 Section 4.3.2, preconditions should be evaluated only for 200 & 206. We
      // don't cache 206, so only 200 is considered.
      var cacheResponse = cacheRetrieval.response;
      if (cacheResponse.get().statusCode() != HTTP_OK
          || evaluatePreconditions(request, cacheResponse.get())) {
        return cacheResponse.with(
            builder ->
                builder
                    .request(request)
                    .cacheStatus(CacheStatus.HIT)
                    .cacheResponse(cacheResponse.get()) // Use original cache response.
                    .apply(cacheRetrieval.strategy::addCacheHeaders)
                    .timeRequestSent(requestTime)
                    .timeResponseReceived(clock.instant()));
      }

      // The cache response wasn't selected by request's preconditions. This is like a server's
      // successful revalidation but done by us.
      cacheRetrieval.closeResponse();
      return NetworkResponse.of(
          ResponseBuilder.create()
              .uri(request.uri())
              .request(request)
              .cacheStatus(CacheStatus.HIT)
              .statusCode(HTTP_NOT_MODIFIED)
              .version(Version.HTTP_1_1)
              .cacheResponse(cacheResponse.get())
              .headers(cacheResponse.get().headers())
              .apply(cacheRetrieval.strategy::addCacheHeaders)
              .timeRequestSent(requestTime)
              .timeResponseReceived(clock.instant())
              .body(FlowSupport.<List<ByteBuffer>>emptyPublisher())
              .buildCacheAwareResponse());
    }

    private CompletableFuture<RawResponse> serveFromCacheAfterUpdating(
        CacheRetrieval cacheRetrieval, NetworkResponse networkResponse) {
      var cacheResponse = cacheRetrieval.response;
      var updatedCacheResponse = updateCacheResponse(cacheResponse, networkResponse);
      var cacheUpdateFuture =
          cache
              .update(updatedCacheResponse)
              .thenAccept(
                  updated -> {
                    if (updated) {
                      listener.onWriteSuccess(request);
                    }
                  })
              .exceptionally(
                  exception -> {
                    listener.onWriteFailure(request, exception);
                    return null;
                  });
      var servableResponse =
          updatedCacheResponse.with(
              builder ->
                  builder
                      .request(request)
                      .cacheStatus(CacheStatus.CONDITIONAL_HIT)
                      .cacheResponse(cacheResponse.get()) // Use original cache response.
                      .networkResponse(networkResponse.get()));
      return synchronizeWrites
          ? cacheUpdateFuture.thenApply(__ -> servableResponse)
          : CompletableFuture.completedFuture(servableResponse);
    }

    private RawResponse serveFromNetwork(
        NetworkResponse networkResponse, @Nullable CacheRetrieval cacheRetrieval) {
      if (cacheRetrieval != null) {
        cacheRetrieval.closeResponse();
      }
      return networkResponse.with(
          builder ->
              builder
                  .request(request)
                  .cacheStatus(CacheStatus.MISS)
                  .cacheResponse(cacheRetrieval != null ? cacheRetrieval.response.get() : null)
                  .networkResponse(networkResponse.get()));
    }

    private RawResponse serveUnsatisfiableRequest(
        Instant requestTime, @Nullable CacheRetrieval cacheRetrieval) {
      if (cacheRetrieval != null) {
        cacheRetrieval.closeResponse();
      }
      return NetworkResponse.of(
          ResponseBuilder.create()
              .uri(request.uri())
              .request(request)
              .cacheStatus(CacheStatus.UNSATISFIABLE)
              .cacheResponse(cacheRetrieval != null ? cacheRetrieval.response.get() : null)
              .statusCode(HTTP_GATEWAY_TIMEOUT)
              .version(Version.HTTP_1_1)
              .timeRequestSent(requestTime)
              .timeResponseReceived(clock.instant())
              .body(FlowSupport.<List<ByteBuffer>>emptyPublisher())
              .buildCacheAwareResponse());
    }
  }
}
