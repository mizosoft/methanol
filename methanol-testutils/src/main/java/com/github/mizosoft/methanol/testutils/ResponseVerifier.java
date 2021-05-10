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

  public ResponseVerifier<T> assertUri(URI uri) {
    assertThat(response.uri()).isEqualTo(uri);
    return this;
  }

  public ResponseVerifier<T> assertCode(int code) {
    assertThat(response.statusCode()).isEqualTo(code);
    return this;
  }

  public ResponseVerifier<T> assertBody(T body) {
    assertThat(response.body()).isEqualTo(body);
    return this;
  }

  public ResponseVerifier<T> assertHeader(String name, String value) {
    assertHeader(name, value, response.headers());
    return this;
  }

  public ResponseVerifier<T> assertHeader(String name, long value) {
    assertHeader(name, value, response.headers());
    return this;
  }

  public ResponseVerifier<T> assertHeader(String name, List<String> values) {
    assertThat(response.headers().allValues(name)).as(name).isEqualTo(values);
    return this;
  }

  public ResponseVerifier<T> assertAbsentHeader(String name) {
    assertThat(response.headers().allValues(name)).as(name).isEmpty();
    return this;
  }

  public ResponseVerifier<T> assertHasHeaders(Map<String, List<String>> multiMap) {
    multiMap.forEach(this::assertHeader);
    return this;
  }

  public ResponseVerifier<T> assertHeaders(HttpHeaders headers) {
    assertThat(response.headers()).isEqualTo(headers);
    return this;
  }

  public ResponseVerifier<T> assertHeadersDiscardingStrippedContentEncoding(HttpHeaders headers) {
    // Discard Content-Encoding & Content-Length if Content-Encoding is included in the given
    // headers but not our response's headers. When these are present in a network/cache response,
    // Methanol removes them from the returned response. This is because transparently decompressing
    // the response obsoletes these headers.
    if (headers.map().containsKey("Content-Encoding")
        && !response.headers().map().containsKey("Content-Encoding")) {
      assertHeaders(
          HttpHeaders.of(
              headers.map(),
              (name, __) ->
                  !"Content-Encoding".equalsIgnoreCase(name)
                      && !"Content-Length".equalsIgnoreCase(name)));
    } else {
      assertHeaders(headers);
    }
    return this;
  }

  public ResponseVerifier<T> assertRequestHeader(String name, String value) {
    assertHeader(name, value, response.request().headers());
    return this;
  }

  public ResponseVerifier<T> assertAbsentRequestHeader(String name) {
    assertThat(response.request().headers().allValues(name)).as(name).isEmpty();
    return this;
  }

  public ResponseVerifier<T> assertRequestHeaders(String... headers) {
    return assertRequestHeaders(headers(headers));
  }

  public ResponseVerifier<T> assertRequestHeaders(HttpHeaders headers) {
    assertThat(response.request().headers()).isEqualTo(headers);
    return this;
  }

  public ResponseVerifier<T> assertSecure() {
    assertThat(response.sslSession()).isPresent();
    return this;
  }

  public ResponseVerifier<T> assertNotSecure() {
    assertThat(response.sslSession()).isEmpty();
    return this;
  }

  public ResponseVerifier<T> assertSslSession(SSLSession expected) {
    assertThat(sslSession())
        .returns(expected.getCipherSuite(), from(SSLSession::getCipherSuite))
        .returns(expected.getProtocol(), from(SSLSession::getProtocol))
        .returns(expected.getLocalCertificates(), from(SSLSession::getLocalCertificates))
        .returns(getPeerCerts(expected), from(ResponseVerifier::getPeerCerts));
    return this;
  }

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  public ResponseVerifier<T> assertSslSession(Optional<SSLSession> expected) {
    expected.ifPresentOrElse(this::assertSslSession, this::assertNotSecure);
    return this;
  }

  public ResponseVerifier<T> assertRequestSentAt(Instant expected) {
    assertTracked();
    assertThat(tracked.timeRequestSent()).isEqualTo(expected);
    return this;
  }

  public ResponseVerifier<T> assertResponseReceivedAt(Instant expected) {
    assertTracked();
    assertThat(tracked.timeResponseReceived()).isEqualTo(expected);
    return this;
  }

  public ResponseVerifier<T> assertCacheStatus(CacheStatus expected) {
    assertCacheAware();
    assertThat(cacheAware.cacheStatus()).isEqualTo(expected);
    return this;
  }

  public ResponseVerifier<T> assertHasNetworkResponse() {
    assertCacheAware();
    assertThat(cacheAware.networkResponse()).isPresent();
    return this;
  }

  public ResponseVerifier<T> assertHasCacheResponse() {
    assertCacheAware();
    assertThat(cacheAware.cacheResponse()).isPresent();
    return this;
  }

  public ResponseVerifier<T> assertHasNoNetworkResponse() {
    assertCacheAware();
    assertThat(cacheAware.networkResponse()).isEmpty();
    return this;
  }

  public ResponseVerifier<T> assertHasNoCacheResponse() {
    assertCacheAware();
    assertThat(cacheAware.cacheResponse()).isEmpty();
    return this;
  }

  public ResponseVerifier<T> assertHit() {
    assertCacheStatus(HIT);
    assertHasCacheResponse();
    assertHasNoNetworkResponse();

    var cacheResponse = cacheResponse().get();
    assertUri(cacheResponse.uri())
        .assertCode(cacheResponse.statusCode())
        .assertHeadersDiscardingStrippedContentEncoding(cacheResponse.headers())
        .assertSslSession(cacheResponse.sslSession());
    return this;
  }

  public ResponseVerifier<T> assertConditionalHit() {
    assertCacheStatus(CONDITIONAL_HIT);
    assertHasCacheResponse();
    assertHasNetworkResponse();

    networkResponse().assertCode(HTTP_NOT_MODIFIED);

    var networkResponse = networkResponse().getTracked();
    assertUri(networkResponse.uri());

    // Timestamps are updated to these of the network response
    assertRequestSentAt(networkResponse.timeRequestSent())
        .assertResponseReceivedAt(networkResponse.timeResponseReceived());

    var cacheResponse = cacheResponse().getTracked();
    assertUri(cacheResponse.uri())
        .assertCode(cacheResponse.statusCode())
        .assertSslSession(cacheResponse.sslSession());

    // Make sure headers are merged correctly as specified in
    // https://httpwg.org/specs/rfc7234.html#freshening.responses
    var networkHeaders = new HashMap<>(networkResponse.headers().map());
    var cachedHeaders = new HashMap<>(cacheResponse.headers().map());
    response
        .headers()
        .map()
        .forEach(
            (name, values) -> {
              var networkValues = networkHeaders.get(name);
              var cacheValues = cachedHeaders.get(name);

              // Header values must come from either network or cache
              assertThat(networkValues != null || cacheValues != null).isTrue();

              // Network values override stored ones unless the header is Content-Length.
              // This is because some servers misbehave by sending a Content-Length: 0 to
              // their 304 responses.
              if ("Content-Length".equalsIgnoreCase(name)) {
                assertThat(values).as(name).isEqualTo(cacheValues);
              } else if (networkValues != null) {
                assertThat(values).as(name).isEqualTo(networkValues);
              } else if ("Warning".equalsIgnoreCase(name)) {
                // Warn codes 1xx must be deleted from the stored response
                assertThat(values)
                    .as(name)
                    .noneMatch(value -> value.startsWith("1"))
                    .isEqualTo(
                        cacheValues.stream()
                            .filter(value -> !value.startsWith("1"))
                            .collect(Collectors.toUnmodifiableList()));
              } else {
                assertThat(values).as(name).isEqualTo(cacheValues);
              }
            });

    return this;
  }

  public ResponseVerifier<T> assertConditionalMiss() {
    assertCacheStatus(MISS);
    assertHasNetworkResponse();
    assertHasCacheResponse();
    assertThat(networkResponse().get().statusCode()).isNotEqualTo(HTTP_NOT_MODIFIED);
    assertEqualToNetworkResponse(networkResponse().getTracked());
    return this;
  }

  public ResponseVerifier<T> assertMiss() {
    assertCacheStatus(MISS);
    assertHasNetworkResponse();
    assertHasNoCacheResponse();
    assertEqualToNetworkResponse(networkResponse().getTracked());
    return this;
  }

  private ResponseVerifier<T> assertEqualToNetworkResponse(TrackedResponse<?> networkResponse) {
    assertUri(networkResponse.uri())
        .assertCode(networkResponse.statusCode())
        .assertHeadersDiscardingStrippedContentEncoding(networkResponse.headers())
        .assertSslSession(networkResponse.sslSession())
        .assertRequestSentAt(networkResponse.timeRequestSent())
        .assertResponseReceivedAt(networkResponse.timeResponseReceived());
    return this;
  }

  public ResponseVerifier<T> assertUnsatisfiable() {
    assertCacheStatus(UNSATISFIABLE);
    assertHasNoNetworkResponse();
    assertHasNoCacheResponse();
    assertThat(response.statusCode()).isEqualTo(HTTP_GATEWAY_TIMEOUT);
    return this;
  }

  public ResponseVerifier<T> assertCachedWithSsl() {
    assertCacheAware();

    assertHasCacheResponse();
    assertSslSession(cacheResponse().sslSession());

    // Network response can be absent
    if (cacheAware.networkResponse().isPresent()) {
      assertSslSession(networkResponse().sslSession());
    }
    return this;
  }

  public ResponseVerifier<?> networkResponse() {
    assertHasNetworkResponse();
    return verifying(cacheAware.networkResponse().orElseThrow());
  }

  public ResponseVerifier<?> cacheResponse() {
    assertHasCacheResponse();
    return verifying(cacheAware.cacheResponse().orElseThrow());
  }

  public ResponseVerifier<T> previousResponse() {
    assertHasPreviousResponse();
    return verifying(response.previousResponse().orElseThrow());
  }

  public ResponseVerifier<T> assertHasPreviousResponse() {
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
    assertSecure();
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

  public boolean hasCacheStatus(CacheStatus status) {
    assertCacheAware();
    return cacheAware.cacheStatus() == status;
  }

  private static void assertHeader(String name, long value, HttpHeaders headers) {
    assertHeader(name, Long.toString(value), headers);
  }

  private static void assertHeader(String name, String value, HttpHeaders headers) {
    assertThat(headers.allValues(name)).as(name).singleElement().isEqualTo(value);
  }

  private static Certificate[] getPeerCerts(SSLSession session) {
    try {
      return session.getPeerCertificates();
    } catch (SSLPeerUnverifiedException e) {
      return null;
    }
  }

  public static <T> ResponseVerifier<T> verifying(HttpResponse<T> response) {
    return new ResponseVerifier<>(response);
  }
}
