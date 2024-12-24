/*
 * Copyright (c) 2024 Moataz Hussein
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
import static org.assertj.core.api.Assertions.*;

import com.github.mizosoft.methanol.CacheAwareResponse;
import com.github.mizosoft.methanol.CacheAwareResponse.CacheStatus;
import com.github.mizosoft.methanol.TrackedResponse;
import com.github.mizosoft.methanol.internal.cache.CacheInterceptor;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import org.checkerframework.checker.nullness.qual.Nullable;

/** A small DSL for testing {@code HttpResponses}. */
@SuppressWarnings("UnusedReturnValue")
public final class ResponseVerifier<T> {
  private final HttpResponse<T> response;

  public ResponseVerifier(HttpResponse<T> response) {
    this.response = response;
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
    assertContainsHeader(response.headers(), name, value);
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> containsHeader(String name, long value) {
    assertContainsHeader(response.headers(), name, value);
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> doesNotContainHeader(String name) {
    assertDoesNotContainHeader(response.headers(), name);
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> containsHeaders(Map<String, List<String>> headers) {
    assertThat(response.headers().map()).containsAllEntriesOf(headers);
    return this;
  }

  // Make sure served response headers are equal to cache response headers, expect headers which
  // the cache adds to the served response.
  @CanIgnoreReturnValue
  private ResponseVerifier<T> compareServedResponseAndCacheResponseHeadersOnCacheHit() {
    var actualMap = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
    actualMap.putAll(response.headers().map());
    var expectedMap = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
    expectedMap.putAll(
        discardStrippedContentEncoding(cacheAwareResponse().cacheResponse().orElseThrow().headers())
            .map());

    class DiffPair {
      final Set<String> left;
      final Set<String> right;

      DiffPair(Set<String> left, Set<String> right) {
        this.left = left;
        this.right = right;
      }
    }

    // Generate difference between actual & expected headers.
    var diff = new TreeMap<String, DiffPair>(String.CASE_INSENSITIVE_ORDER);
    var allNames = new HashSet<String>();
    allNames.addAll(actualMap.keySet());
    allNames.addAll(expectedMap.keySet());
    for (var name : allNames) {
      var actualValuesList = actualMap.getOrDefault(name, List.of());
      var expectedValuesList = expectedMap.getOrDefault(name, List.of());

      // actualValues - expectedValues
      var leftDiff = new HashSet<>(actualValuesList);
      expectedValuesList.forEach(leftDiff::remove);

      // expectedValues - actualValues
      var rightDiff = new HashSet<>(expectedValuesList);
      actualValuesList.forEach(rightDiff::remove);

      if (!leftDiff.isEmpty() || !rightDiff.isEmpty()) {
        diff.put(name, new DiffPair(leftDiff, rightDiff));
      }
    }

    for (var diffEntry : diff.entrySet()) {
      // Served response's Age can be different from cache response's Age (if any). But a served
      // response must contain the Age header.
      var name = diffEntry.getKey();
      var diffPair = diffEntry.getValue();
      if (name.equalsIgnoreCase("Age")) {
        assertThat(diffPair.left)
            .as("Age")
            .withFailMessage(
                () ->
                    "Unexpected diff between served & cached response headers: "
                        + diffPair.left
                        + ", "
                        + diffPair.right)
            .isNotEmpty();
      } else if (name.equalsIgnoreCase("Warning")) {
        // The cache doesn't skip any warn codes, but it can add an 110 or a 113.
        assertThat(diffPair.left)
            .as("Warning")
            .withFailMessage(
                () ->
                    "Unexpected diff between served & cached response headers: "
                        + diffPair.left
                        + ", "
                        + diffPair.right)
            .isNotEmpty()
            .allMatch(value -> value.contains("110") || value.contains("113"));
      } else {
        // No other diff is expected.
        fail(
            "["
                + name
                + "] Unexpected diff between served & cached response headers: "
                + diffPair.left
                + ", "
                + diffPair.right);
      }
    }
    return this;
  }

  // Makes sure headers are merged correctly as specified in
  // https://httpwg.org/specs/rfc7234.html#freshening.responses.
  @CanIgnoreReturnValue
  private ResponseVerifier<T> compareServedResponseAndCacheResponseHeadersOnConditionalCacheHit() {
    var networkHeaders =
        discardStrippedContentEncoding(
                cacheAwareResponse().networkResponse().orElseThrow().headers())
            .map();
    var cacheHeaders =
        discardStrippedContentEncoding(cacheAwareResponse().cacheResponse().orElseThrow().headers())
            .map();
    response
        .headers()
        .map()
        .forEach(
            (name, values) -> {
              var networkValues = networkHeaders.getOrDefault(name, List.of());
              var cacheValues = cacheHeaders.getOrDefault(name, List.of());

              // Header values must come from either network or cache.
              assertThat(List.of(networkValues, cacheValues)).anyMatch(list -> !list.isEmpty());
              if (CacheInterceptor.canReplaceStoredHeader(name) && !networkValues.isEmpty()) {
                // The values must be updated from network if provided.
                assertThat(values).as(name).containsExactlyInAnyOrderElementsOf(networkValues);
              } else {
                // The value sare retained from cache.
                assertThat(values).as(name).containsExactlyInAnyOrderElementsOf(cacheValues);
              }

              // 1xx warn codes must be deleted from the stored response.
              if (name.equalsIgnoreCase("Warning")) {
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

  private HttpHeaders discardStrippedContentEncoding(HttpHeaders headers) {
    if (response.request().headers().map().containsKey("Accept-Encoding")
        && !response.headers().map().containsKey("Content-Encoding")
        && headers.map().containsKey("Content-Encoding")) {
      return HttpHeaders.of(
          headers.map(),
          (name, __) ->
              !(name.equalsIgnoreCase("Content-Encoding")
                  || name.equalsIgnoreCase("Content-Length")));
    } else {
      return headers;
    }
  }

  @CanIgnoreReturnValue
  private ResponseVerifier<T> containsHeadersExactly(HttpHeaders expected) {
    assertThat(response.headers().map()).isEqualTo(expected.map());
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> containsRequestHeader(String name, String value) {
    assertContainsHeader(response.request().headers(), name, value);
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> doesNotContainRequestHeader(String name) {
    assertDoesNotContainHeader(response.request().headers(), name);
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
    assertThat(trackedResponse().timeRequestSent()).isEqualTo(expected);
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> responseWasReceivedAt(Instant expected) {
    assertThat(trackedResponse().timeResponseReceived()).isEqualTo(expected);
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> hasCacheStatus(CacheStatus expected) {
    assertThat(cacheAwareResponse().cacheStatus()).isEqualTo(expected);
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> hasNetworkResponse() {
    assertThat(cacheAwareResponse().networkResponse()).isPresent();
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> hasCacheResponse() {
    assertThat(cacheAwareResponse().cacheResponse()).isPresent();
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> hasNoNetworkResponse() {
    assertThat(cacheAwareResponse().networkResponse()).isEmpty();
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> hasNoCacheResponse() {
    assertThat(cacheAwareResponse().cacheResponse()).isEmpty();
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> isCacheHit() {
    hasCacheStatus(HIT);
    hasCacheResponse();
    hasNoNetworkResponse();

    var cacheResponse = cacheResponse().response();
    hasUri(cacheResponse.uri())
        .hasCode(cacheResponse.statusCode())
        .compareServedResponseAndCacheResponseHeadersOnCacheHit()
        .hasSslSession(cacheResponse.sslSession());
    return this;
  }

  /**
   * Tests if this is a conditional cache hit resulting from a request that is conditionalized by
   * the client and evaluated by the cache, rather than conditionalized by the cache and evaluated
   * by the origin. The latter is checked with {@link #isConditionalCacheHit()}.
   */
  @CanIgnoreReturnValue
  public ResponseVerifier<T> isExternallyConditionalCacheHit() {
    hasCacheStatus(HIT);
    hasCacheResponse();
    hasNoNetworkResponse();

    var cacheResponse = cacheResponse().response();
    new ResponseVerifier<>(cacheAwareResponse()).hasCode(HTTP_NOT_MODIFIED);
    hasUri(cacheResponse.uri())
        .hasCode(HTTP_NOT_MODIFIED)
        .compareServedResponseAndCacheResponseHeadersOnCacheHit()
        .hasSslSession(cacheResponse.sslSession());
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> isConditionalCacheHit() {
    hasCacheStatus(CONDITIONAL_HIT);
    hasCacheResponse();
    hasNetworkResponse();
    networkResponse().hasCode(HTTP_NOT_MODIFIED);

    var networkResponse = networkResponse().trackedResponse();
    hasUri(networkResponse.uri());

    // Timestamps are updated to these of the network response.
    requestWasSentAt(networkResponse.timeRequestSent())
        .responseWasReceivedAt(networkResponse.timeResponseReceived());

    var cacheResponse = cacheResponse().trackedResponse();
    hasUri(cacheResponse.uri())
        .hasCode(cacheResponse.statusCode())
        .hasSslSession(cacheResponse.sslSession())
        .compareServedResponseAndCacheResponseHeadersOnConditionalCacheHit();
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> isConditionalCacheMiss() {
    hasCacheStatus(MISS);
    hasNetworkResponse();
    hasCacheResponse();
    assertThat(networkResponse().response().statusCode()).isNotEqualTo(HTTP_NOT_MODIFIED);
    assertEqualToNetworkResponse(networkResponse().trackedResponse());
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> isCacheMiss() {
    hasCacheStatus(MISS);
    hasNetworkResponse();
    hasNoCacheResponse();
    assertEqualToNetworkResponse(networkResponse().trackedResponse());
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> isCacheMissWithCacheResponse() {
    hasCacheStatus(MISS);
    hasNetworkResponse();
    hasCacheResponse();
    assertEqualToNetworkResponse(networkResponse().trackedResponse());
    return this;
  }

  private void assertEqualToNetworkResponse(TrackedResponse<?> networkResponse) {
    hasUri(networkResponse.uri())
        .hasCode(networkResponse.statusCode())
        .containsHeadersExactly(discardStrippedContentEncoding(networkResponse.headers()))
        .hasSslSession(networkResponse.sslSession())
        .requestWasSentAt(networkResponse.timeRequestSent())
        .responseWasReceivedAt(networkResponse.timeResponseReceived());
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> isCacheUnsatisfaction() {
    hasCacheStatus(UNSATISFIABLE);
    hasNoNetworkResponse();
    hasCode(HTTP_GATEWAY_TIMEOUT);
    return this;
  }

  @CanIgnoreReturnValue
  public ResponseVerifier<T> isCachedWithSsl() {
    hasCacheResponse();
    hasSslSession(cacheResponse().sslSession());

    // Network response can be absent.
    if (cacheAwareResponse().networkResponse().isPresent()) {
      hasSslSession(networkResponse().sslSession());
    }
    return this;
  }

  public ResponseVerifier<?> networkResponse() {
    hasNetworkResponse();
    return new ResponseVerifier<>(cacheAwareResponse().networkResponse().orElseThrow());
  }

  public ResponseVerifier<?> cacheResponse() {
    hasCacheResponse();
    return new ResponseVerifier<>(cacheAwareResponse().cacheResponse().orElseThrow());
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

  public SSLSession sslSession() {
    isSecure();
    return response.sslSession().orElseThrow();
  }

  public HttpResponse<T> response() {
    return response;
  }

  public TrackedResponse<T> trackedResponse() {
    assertThat(response).isInstanceOf(TrackedResponse.class);
    return (TrackedResponse<T>) response;
  }

  public CacheAwareResponse<T> cacheAwareResponse() {
    assertThat(response).isInstanceOf(CacheAwareResponse.class);
    return (CacheAwareResponse<T>) response;
  }

  private static void assertContainsHeader(HttpHeaders headers, String name, long value) {
    assertContainsHeader(headers, name, Long.toString(value));
  }

  private static void assertContainsHeader(HttpHeaders headers, String name, String value) {
    assertThat(headers.allValues(name)).as(name).singleElement().isEqualTo(value);
  }

  private static void assertDoesNotContainHeader(HttpHeaders headers, String name) {
    assertThat(headers.allValues(name)).as(name).isEmpty();
  }

  private static @Nullable Certificate[] getPeerCerts(SSLSession session) {
    try {
      return session.getPeerCertificates();
    } catch (SSLPeerUnverifiedException e) {
      return null;
    }
  }
}
