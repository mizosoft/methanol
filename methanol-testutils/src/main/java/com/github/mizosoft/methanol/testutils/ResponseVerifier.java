/*
 * Copyright (c) 2019-2021 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.testutils;

import static com.github.mizosoft.methanol.CacheAwareResponse.CacheStatus.CONDITIONAL_HIT;
import static com.github.mizosoft.methanol.CacheAwareResponse.CacheStatus.HIT;
import static com.github.mizosoft.methanol.CacheAwareResponse.CacheStatus.MISS;
import static com.github.mizosoft.methanol.CacheAwareResponse.CacheStatus.UNSATISFIABLE;
import static com.github.mizosoft.methanol.testutils.TestUtils.headers;
import static java.net.HttpURLConnection.HTTP_GATEWAY_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.from;

import com.github.mizosoft.methanol.CacheAwareResponse;
import com.github.mizosoft.methanol.CacheAwareResponse.CacheStatus;
import com.github.mizosoft.methanol.TrackedResponse;
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

/** A small DSL for testing {@code HttpResponses}. */
@SuppressWarnings("UnusedReturnValue")
public final class ResponseVerifier<T> {
  private final HttpResponse<T> response;
  private final TrackedResponse<T> tracked;
  private final CacheAwareResponse<T> cacheAware;

  public ResponseVerifier(HttpResponse<T> response) {
    this.response = response;
    tracked = response instanceof TrackedResponse<?> ? (TrackedResponse<T>) response : null;
    cacheAware =
        response instanceof CacheAwareResponse<?> ? (CacheAwareResponse<T>) response : null;
  }

  public ResponseVerifier<T> hasUri(URI uri) {
    assertThat(response.uri()).isEqualTo(uri);
    return this;
  }

  public ResponseVerifier<T> hasCode(int code) {
    assertThat(response.statusCode()).isEqualTo(code);
    return this;
  }

  public ResponseVerifier<T> hasBody(T body) {
    assertThat(response.body()).isEqualTo(body);
    return this;
  }

  public ResponseVerifier<T> containsHeader(String name, String value) {
    assertContainsHeader(name, value, response.headers());
    return this;
  }

  public ResponseVerifier<T> containsHeader(String name, long value) {
    assertContainsHeader(name, value, response.headers());
    return this;
  }

  public ResponseVerifier<T> containsHeader(String name, List<String> values) {
    assertThat(response.headers().allValues(name)).as(name).isEqualTo(values);
    return this;
  }

  public ResponseVerifier<T> doesNotContainHeader(String name) {
    assertThat(response.headers().allValues(name)).as(name).isEmpty();
    return this;
  }

  public ResponseVerifier<T> containsHeaders(Map<String, List<String>> multimap) {
    assertThat(multimap).allSatisfy(this::containsHeader);
    return this;
  }

  public ResponseVerifier<T> hasHeadersExactly(HttpHeaders headers) {
    assertThat(response.headers()).isEqualTo(headers);
    return this;
  }

  public ResponseVerifier<T> hasHeadersExactlyDiscardingStrippedContentEncoding(
      HttpHeaders headers) {
    // Discard Content-Encoding & Content-Length if Content-Encoding is included in the given
    // headers but not our response's headers. When these are present in a network/cache response,
    // Methanol removes them from the returned response. This is because transparently decompressing
    // the response obsoletes these headers.
    if (headers.map().containsKey("Content-Encoding")
        && !response.headers().map().containsKey("Content-Encoding")) {
      hasHeadersExactly(
          HttpHeaders.of(
              headers.map(),
              (name, __) ->
                  !"Content-Encoding".equalsIgnoreCase(name)
                      && !"Content-Length".equalsIgnoreCase(name)));
    } else {
      hasHeadersExactly(headers);
    }
    return this;
  }

  public ResponseVerifier<T> containsRequestHeader(String name, String value) {
    assertContainsHeader(name, value, response.request().headers());
    return this;
  }

  public ResponseVerifier<T> doesNotContainRequestHeader(String name) {
    assertThat(response.request().headers().allValues(name)).as(name).isEmpty();
    return this;
  }

  public ResponseVerifier<T> hasRequestHeadersExactly(String... headers) {
    return hasRequestHeadersExactly(headers(headers));
  }

  public ResponseVerifier<T> hasRequestHeadersExactly(HttpHeaders headers) {
    assertThat(response.request().headers()).isEqualTo(headers);
    return this;
  }

  public ResponseVerifier<T> isSecure() {
    assertThat(response.sslSession()).isPresent();
    return this;
  }

  public ResponseVerifier<T> isNotSecure() {
    assertThat(response.sslSession()).isEmpty();
    return this;
  }

  public ResponseVerifier<T> hasSslSession(SSLSession expected) {
    assertThat(sslSession())
        .returns(expected.getCipherSuite(), from(SSLSession::getCipherSuite))
        .returns(expected.getProtocol(), from(SSLSession::getProtocol))
        .returns(expected.getLocalCertificates(), from(SSLSession::getLocalCertificates))
        .returns(getPeerCerts(expected), from(ResponseVerifier::getPeerCerts));
    return this;
  }

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  public ResponseVerifier<T> hasSslSession(Optional<SSLSession> expected) {
    expected.ifPresentOrElse(this::hasSslSession, this::isNotSecure);
    return this;
  }

  public ResponseVerifier<T> requestWasSentAt(Instant expected) {
    assertTracked();
    assertThat(tracked.timeRequestSent()).isEqualTo(expected);
    return this;
  }

  public ResponseVerifier<T> responseWasReceivedAt(Instant expected) {
    assertTracked();
    assertThat(tracked.timeResponseReceived()).isEqualTo(expected);
    return this;
  }

  public ResponseVerifier<T> hasCacheStatus(CacheStatus expected) {
    assertCacheAware();
    assertThat(cacheAware.cacheStatus()).isEqualTo(expected);
    return this;
  }

  public ResponseVerifier<T> hasNetworkResponse() {
    assertCacheAware();
    assertThat(cacheAware.networkResponse()).isPresent();
    return this;
  }

  public ResponseVerifier<T> hasCacheResponse() {
    assertCacheAware();
    assertThat(cacheAware.cacheResponse()).isPresent();
    return this;
  }

  public ResponseVerifier<T> hasNoNetworkResponse() {
    assertCacheAware();
    assertThat(cacheAware.networkResponse()).isEmpty();
    return this;
  }

  public ResponseVerifier<T> hasNoCacheResponse() {
    assertCacheAware();
    assertThat(cacheAware.cacheResponse()).isEmpty();
    return this;
  }

  public ResponseVerifier<T> isCacheHit() {
    hasCacheStatus(HIT);
    hasCacheResponse();
    hasNoNetworkResponse();

    var cacheResponse = cacheResponse().get();
    hasUri(cacheResponse.uri())
        .hasCode(cacheResponse.statusCode())
        .hasHeadersExactlyDiscardingStrippedContentEncoding(cacheResponse.headers())
        .hasSslSession(cacheResponse.sslSession());
    return this;
  }

  public ResponseVerifier<T> isConditionalHit() {
    hasCacheStatus(CONDITIONAL_HIT);
    hasCacheResponse();
    hasNetworkResponse();

    networkResponse().hasCode(HTTP_NOT_MODIFIED);

    var networkResponse = networkResponse().getTracked();
    hasUri(networkResponse.uri());

    // Timestamps are updated to these of the network response.
    requestWasSentAt(networkResponse.timeRequestSent())
        .responseWasReceivedAt(networkResponse.timeResponseReceived());

    var cacheResponse = cacheResponse().getTracked();
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
              // as List<? extends T>. An inner assertion would store an
              // as List<? extends capture of ? extends T>, which isn't assignable from a List<T>
              // from the generic system's point of view.
              //noinspection unchecked
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

  public ResponseVerifier<T> isConditionalMiss() {
    hasCacheStatus(MISS);
    hasNetworkResponse();
    hasCacheResponse();
    assertThat(networkResponse().get().statusCode()).isNotEqualTo(HTTP_NOT_MODIFIED);
    assertEqualToNetworkResponse(networkResponse().getTracked());
    return this;
  }

  public ResponseVerifier<T> isCacheMiss() {
    hasCacheStatus(MISS);
    hasNetworkResponse();
    hasNoCacheResponse();
    assertEqualToNetworkResponse(networkResponse().getTracked());
    return this;
  }

  public ResponseVerifier<T> isCacheMissWithCacheResponse() {
    hasCacheStatus(MISS);
    hasNetworkResponse();
    hasCacheResponse();
    assertEqualToNetworkResponse(networkResponse().getTracked());
    return this;
  }

  private ResponseVerifier<T> assertEqualToNetworkResponse(TrackedResponse<?> networkResponse) {
    hasUri(networkResponse.uri())
        .hasCode(networkResponse.statusCode())
        .hasHeadersExactlyDiscardingStrippedContentEncoding(networkResponse.headers())
        .hasSslSession(networkResponse.sslSession())
        .requestWasSentAt(networkResponse.timeRequestSent())
        .responseWasReceivedAt(networkResponse.timeResponseReceived());
    return this;
  }

  public ResponseVerifier<T> isCacheUnsatisfaction() {
    hasCacheStatus(UNSATISFIABLE);
    hasNoNetworkResponse();
    hasNoCacheResponse();
    assertThat(response.statusCode()).isEqualTo(HTTP_GATEWAY_TIMEOUT);
    return this;
  }

  public ResponseVerifier<T> isCachedWithSsl() {
    assertCacheAware();

    hasCacheResponse();
    hasSslSession(cacheResponse().sslSession());

    // Network response can be absent
    if (cacheAware.networkResponse().isPresent()) {
      hasSslSession(networkResponse().sslSession());
    }
    return this;
  }

  public ResponseVerifier<?> networkResponse() {
    hasNetworkResponse();
    return new ResponseVerifier<>(cacheAware.networkResponse().orElseThrow());
  }

  public ResponseVerifier<?> cacheResponse() {
    hasCacheResponse();
    return new ResponseVerifier<>(cacheAware.cacheResponse().orElseThrow());
  }

  public ResponseVerifier<T> previousResponse() {
    hasPreviousResponse();
    return new ResponseVerifier<>(response.previousResponse().orElseThrow());
  }

  public ResponseVerifier<T> hasPreviousResponse() {
    assertThat(response.previousResponse()).isPresent();
    return this;
  }

  public void assertTracked() {
    assertThat(tracked).isNotNull();
  }

  public void assertCacheAware() {
    assertThat(cacheAware).isNotNull();
  }

  public SSLSession sslSession() {
    isSecure();
    return response.sslSession().orElseThrow();
  }

  public HttpResponse<T> get() {
    return response;
  }

  public TrackedResponse<T> getTracked() {
    return tracked;
  }

  public CacheAwareResponse<T> getCacheAware() {
    return cacheAware;
  }

  private static void assertContainsHeader(String name, long value, HttpHeaders headers) {
    assertContainsHeader(name, Long.toString(value), headers);
  }

  private static void assertContainsHeader(String name, String value, HttpHeaders headers) {
    assertThat(headers.allValues(name)).as(name).singleElement().isEqualTo(value);
  }

  private static Certificate[] getPeerCerts(SSLSession session) {
    try {
      return session.getPeerCertificates();
    } catch (SSLPeerUnverifiedException e) {
      return null;
    }
  }
}
