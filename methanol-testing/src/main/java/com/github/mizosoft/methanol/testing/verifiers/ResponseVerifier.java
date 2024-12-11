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

package com.github.mizosoft.methanol.testing.verifiers;

import static com.github.mizosoft.methanol.CacheAwareResponse.CacheStatus.CONDITIONAL_HIT;
import static com.github.mizosoft.methanol.CacheAwareResponse.CacheStatus.HIT;
import static com.github.mizosoft.methanol.CacheAwareResponse.CacheStatus.MISS;
import static com.github.mizosoft.methanol.CacheAwareResponse.CacheStatus.UNSATISFIABLE;
import static com.github.mizosoft.methanol.testing.TestUtils.headers;
import static java.net.HttpURLConnection.HTTP_GATEWAY_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.from;

import com.github.mizosoft.methanol.CacheAwareResponse;
import com.github.mizosoft.methanol.CacheAwareResponse.CacheStatus;
import com.github.mizosoft.methanol.TrackedResponse;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/** A small DSL for testing {@code HttpResponses}. */
@SuppressWarnings("UnusedReturnValue")
public final class ResponseVerifier<T> {
  private final HttpResponse<T> response;
  private final @Nullable TrackedResponse<T> trackedResponse;
  private final @Nullable CacheAwareResponse<T> cacheAwareResponse;

  public ResponseVerifier(HttpResponse<T> response) {
    this.response = response;
    trackedResponse = response instanceof TrackedResponse<?> ? (TrackedResponse<T>) response : null;
    cacheAwareResponse =
        response instanceof CacheAwareResponse<?> ? (CacheAwareResponse<T>) response : null;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> hasUri(URI uri) {
    assertThat(response.uri()).isEqualTo(uri);
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> hasCode(int code) {
    assertThat(response.statusCode()).isEqualTo(code);
    response
        .headers()
        .firstValue(":status")
        .ifPresent(status -> assertThat(status).isEqualTo(Integer.toString(code)));
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> hasBody(T body) {
    assertThat(response.body()).isEqualTo(body);
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> hasNoBody() {
    assertThat(response.body()).isNull();
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> containsHeader(String name, String value) {
    assertContainsHeader(name, value, response.headers());
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> containsHeader(String name, long value) {
    assertContainsHeader(name, value, response.headers());
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> containsHeader(String name, List<String> values) {
    assertThat(response.headers().allValues(name)).as(name).isEqualTo(values);
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> doesNotContainHeader(String name) {
    assertThat(response.headers().allValues(name)).as(name).isEmpty();
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> containsHeaders(Map<String, List<String>> headers) {
    assertThat(headers).allSatisfy(this::containsHeader);
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> containsHeadersExactly(HttpHeaders headers) {
    assertThat(response.headers()).isEqualTo(headers);
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> containsHeadersExactlyDiscardingStrippedContentEncoding(
      HttpHeaders headers) {
    // Discard Content-Encoding & Content-Length if Content-Encoding is included in the given
    // headers but not our response's headers. When these are present in a network/cache response,
    // Methanol removes them from the returned response. This is because transparently decompressing
    // the response obsoletes these headers.
    if (headers.map().containsKey("Content-Encoding")
        && !response.headers().map().containsKey("Content-Encoding")) {
      containsHeadersExactly(
          HttpHeaders.of(
              headers.map(),
              (name, __) ->
                  !"Content-Encoding".equalsIgnoreCase(name)
                      && !"Content-Length".equalsIgnoreCase(name)));
    } else {
      containsHeadersExactly(headers);
    }
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> containsRequestHeader(String name, String value) {
    assertContainsHeader(name, value, response.request().headers());
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> doesNotContainRequestHeader(String name) {
    assertThat(response.request().headers().allValues(name)).as(name).isEmpty();
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> containsRequestHeadersExactly(String... headers) {
    return containsRequestHeadersExactly(headers(headers));
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> containsRequestHeadersExactly(HttpHeaders headers) {
    assertThat(response.request().headers()).isEqualTo(headers);
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> isSecure() {
    assertThat(response.sslSession()).isPresent();
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> isNotSecure() {
    assertThat(response.sslSession()).isEmpty();
    return this;
  }

  @CanIgnoreReturnValue
  @SuppressWarnings("BadImport")
  public ResponseVerifier<T> hasSslSession(SSLSession expected) {
    assertThat(sslSession())
        .returns(expected.getCipherSuite(), from(SSLSession::getCipherSuite))
        .returns(expected.getProtocol(), from(SSLSession::getProtocol))
        .returns(expected.getLocalCertificates(), from(SSLSession::getLocalCertificates))
        .returns(getPeerCerts(expected), from(ResponseVerifier::getPeerCerts));
    return this;
  }

  @CanIgnoreReturnValue
  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  public ResponseVerifier<T> hasSslSession(Optional<SSLSession> expected) {
    expected.ifPresentOrElse(this::hasSslSession, this::isNotSecure);
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> requestWasSentAt(Instant expected) {
    assertIsTracked();
    assertThat(trackedResponse.timeRequestSent()).isEqualTo(expected);
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> responseWasReceivedAt(Instant expected) {
    assertIsTracked();
    assertThat(trackedResponse.timeResponseReceived()).isEqualTo(expected);
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> hasCacheStatus(CacheStatus expected) {
    assertIsCacheAware();
    assertThat(cacheAwareResponse.cacheStatus()).isEqualTo(expected);
    return this;
  }

  @CanIgnoreReturnValue
  @EnsuresNonNull({"trackedResponse", "cacheAwareResponse"})
  public ResponseVerifier<T> hasNetworkResponse() {
    assertIsCacheAware();
    assertThat(cacheAwareResponse.networkResponse()).isPresent();
    return this;
  }

  @CanIgnoreReturnValue
  @EnsuresNonNull({"trackedResponse", "cacheAwareResponse"})
  public ResponseVerifier<T> hasCacheResponse() {
    assertIsCacheAware();
    assertThat(cacheAwareResponse.cacheResponse()).isPresent();
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> hasNoNetworkResponse() {
    assertIsCacheAware();
    assertThat(cacheAwareResponse.networkResponse()).isEmpty();
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> hasNoCacheResponse() {
    assertIsCacheAware();
    assertThat(cacheAwareResponse.cacheResponse()).isEmpty();
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> isCacheHit() {
    hasCacheStatus(HIT);
    hasCacheResponse();
    hasNoNetworkResponse();

    var cacheResponse = cacheResponse().get();
    hasUri(cacheResponse.uri())
        .hasCode(cacheResponse.statusCode())
        .containsHeadersExactlyDiscardingStrippedContentEncoding(cacheResponse.headers())
        .hasSslSession(cacheResponse.sslSession());
    return this;
  }

  /**
   * Tests if this is a conditional cache hit resulting from a request that is conditionalized by
   * the client and evaluated by the cache, rather than conditionalized by the cache and evaluated
   * by the origin. The latter is checked with {@link #isConditionalHit()}.
   */
  @CanIgnoreReturnValue
  public ResponseVerifier<T> isExternallyConditionalCacheHit() {
    hasCacheStatus(HIT);
    hasCacheResponse();
    hasNoNetworkResponse();

    var cacheResponse = cacheResponse().get();
    hasUri(cacheResponse.uri())
        .hasCode(HTTP_NOT_MODIFIED)
        .containsHeadersExactlyDiscardingStrippedContentEncoding(cacheResponse.headers())
        .hasSslSession(cacheResponse.sslSession());
    return this;
  }

  @SuppressWarnings("unchecked")
  @CanIgnoreReturnValue
  public ResponseVerifier<T> isConditionalHit() {
    hasCacheStatus(CONDITIONAL_HIT);
    hasCacheResponse();
    hasNetworkResponse();

    networkResponse().hasCode(HTTP_NOT_MODIFIED);

    var networkResponse = networkResponse().getTrackedResponse();
    hasUri(networkResponse.uri());

    // Timestamps are updated to these of the network response.
    requestWasSentAt(networkResponse.timeRequestSent())
        .responseWasReceivedAt(networkResponse.timeResponseReceived());

    var cacheResponse = cacheResponse().getTrackedResponse();
    hasUri(cacheResponse.uri())
        .hasCode(cacheResponse.statusCode())
        .hasSslSession(cacheResponse.sslSession());

    // Make sure headers are merged correctly as specified in
    // https://httpwg.org/specs/rfc7234.html#freshening.responses.
    var networkHeaders = new HashMap<>(networkResponse.headers().map());
    var cacheHeaders = new HashMap<>(cacheResponse.headers().map());
    response
        .headers()
        .map()
        .forEach(
            (name, values) -> {
              var networkValues = networkHeaders.getOrDefault(name, List.of());
              var cacheValues = cacheHeaders.getOrDefault(name, List.of());

              // Header values must come from either network or cache.
              assertThat(List.of(networkValues, cacheValues)).anyMatch(list -> !list.isEmpty());

              // An unchecked cast is used in the inner assertThat as the assertion stores List<T>
              // as List<? extends T>. An inner assertion would store a
              // List<? extends capture of ? extends T>, which isn't assignable from a List<T>
              // from the generic system's point of view.
              assertThat(values)
                  .as(name)
                  .satisfiesAnyOf(
                      lambdaValues ->
                          assertThat((List<String>) lambdaValues)
                              .containsExactlyInAnyOrderElementsOf(cacheValues),
                      lambdaValues ->
                          assertThat((List<String>) lambdaValues)
                              .containsExactlyInAnyOrderElementsOf(networkValues));

              // 1xx warn codes must be deleted from the stored response.
              if ("Warning".equalsIgnoreCase(name)) {
                assertThat(values)
                    .as(name)
                    .noneMatch(value -> value.startsWith("1"))
                    .isEqualTo(
                        cacheValues.stream()
                            .filter(value -> !value.startsWith("1"))
                            .collect(Collectors.toUnmodifiableList()));
              }
            });
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> isConditionalMiss() {
    hasCacheStatus(MISS);
    hasNetworkResponse();
    hasCacheResponse();
    assertThat(networkResponse().get().statusCode()).isNotEqualTo(HTTP_NOT_MODIFIED);
    assertEqualToNetworkResponse(networkResponse().getTrackedResponse());
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> isCacheMiss() {
    hasCacheStatus(MISS);
    hasNetworkResponse();
    hasNoCacheResponse();
    assertEqualToNetworkResponse(networkResponse().getTrackedResponse());
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> isCacheMissWithCacheResponse() {
    hasCacheStatus(MISS);
    hasNetworkResponse();
    hasCacheResponse();
    assertEqualToNetworkResponse(networkResponse().getTrackedResponse());
    return this;
  }

  @CanIgnoreReturnValue
  private ResponseVerifier<T> assertEqualToNetworkResponse(TrackedResponse<?> networkResponse) {
    hasUri(networkResponse.uri())
        .hasCode(networkResponse.statusCode())
        .containsHeadersExactlyDiscardingStrippedContentEncoding(networkResponse.headers())
        .hasSslSession(networkResponse.sslSession())
        .requestWasSentAt(networkResponse.timeRequestSent())
        .responseWasReceivedAt(networkResponse.timeResponseReceived());
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> isCacheUnsatisfaction() {
    hasCacheStatus(UNSATISFIABLE);
    hasNoNetworkResponse();
    assertThat(response.statusCode()).isEqualTo(HTTP_GATEWAY_TIMEOUT);
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> isCachedWithSsl() {
    assertIsCacheAware();

    hasCacheResponse();
    hasSslSession(cacheResponse().sslSession());

    // Network response can be absent.
    if (cacheAwareResponse.networkResponse().isPresent()) {
      hasSslSession(networkResponse().sslSession());
    }
    return this;
  }

  public ResponseVerifier<?> networkResponse() {
    hasNetworkResponse();
    return new ResponseVerifier<>(cacheAwareResponse.networkResponse().orElseThrow());
  }

  public ResponseVerifier<?> cacheResponse() {
    hasCacheResponse();
    return new ResponseVerifier<>(cacheAwareResponse.cacheResponse().orElseThrow());
  }

  public ResponseVerifier<T> previousResponse() {
    hasPreviousResponse();
    return new ResponseVerifier<>(response.previousResponse().orElseThrow());
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> hasPreviousResponse() {
    assertThat(response.previousResponse()).isPresent();
    return this;
  }

  @EnsuresNonNull("trackedResponse")
  public void assertIsTracked() {
    assertThat(trackedResponse).isNotNull();
  }

  @EnsuresNonNull({"trackedResponse", "cacheAwareResponse"})
  public void assertIsCacheAware() {
    assertThat(trackedResponse).isNotNull();
    assertThat(cacheAwareResponse).isNotNull();
  }

  public SSLSession sslSession() {
    isSecure();
    return response.sslSession().orElseThrow();
  }

  public HttpResponse<T> get() {
    return response;
  }

  public TrackedResponse<T> getTrackedResponse() {
    assertIsTracked();
    return trackedResponse;
  }

  public CacheAwareResponse<T> getCacheAwareResponse() {
    assertIsCacheAware();
    return cacheAwareResponse;
  }

  private static void assertContainsHeader(String name, long value, HttpHeaders headers) {
    assertContainsHeader(name, Long.toString(value), headers);
  }

  private static void assertContainsHeader(String name, String value, HttpHeaders headers) {
    assertThat(headers.allValues(name)).as(name).singleElement().isEqualTo(value);
  }

  private static @Nullable Certificate[] getPeerCerts(SSLSession session) {
    try {
      return session.getPeerCertificates();
    } catch (SSLPeerUnverifiedException e) {
      return null;
    }
  }
}
