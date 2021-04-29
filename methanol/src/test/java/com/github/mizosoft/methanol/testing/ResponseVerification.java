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

package com.github.mizosoft.methanol.testing;

import static com.github.mizosoft.methanol.internal.extensions.CacheAwareResponse.CacheStatus.CONDITIONAL_HIT;
import static com.github.mizosoft.methanol.internal.extensions.CacheAwareResponse.CacheStatus.HIT;
import static com.github.mizosoft.methanol.internal.extensions.CacheAwareResponse.CacheStatus.LOCALLY_GENERATED;
import static com.github.mizosoft.methanol.internal.extensions.CacheAwareResponse.CacheStatus.MISS;
import static com.github.mizosoft.methanol.testutils.TestUtils.headers;
import static java.net.HttpURLConnection.HTTP_GATEWAY_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.mizosoft.methanol.internal.extensions.CacheAwareResponse;
import com.github.mizosoft.methanol.internal.extensions.CacheAwareResponse.CacheStatus;
import com.github.mizosoft.methanol.internal.extensions.TrackedResponse;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

@SuppressWarnings("UnusedReturnValue")
public final class ResponseVerification<T> {
  private final HttpResponse<T> response;
  private final TrackedResponse<T> tracked;
  private final CacheAwareResponse<T> cacheAware;

  public ResponseVerification(HttpResponse<T> response) {
    this.response = response;
    tracked = response instanceof TrackedResponse<?> ? (TrackedResponse<T>) response : null;
    cacheAware = response instanceof CacheAwareResponse<?> ? (CacheAwareResponse<T>) response : null;
  }

  public ResponseVerification<T> assertUri(URI uri) {
    assertEquals(uri, response.uri());
    return this;
  }

  public ResponseVerification<T> assertCode(int code) {
    assertEquals(code, response.statusCode());
    return this;
  }

  public ResponseVerification<T> assertBody(T body) {
    assertEquals(body, response.body());
    return this;
  }

  public ResponseVerification<T> assertHeader(String name, Object value) {
    assertHeader(name, value, response.headers());
    return this;
  }

  public ResponseVerification<T> assertHeaderValues(String name, List<String> values) {
    assertEquals(values, response.headers().allValues(name), name);
    return this;
  }

  public ResponseVerification<T> assertHasHeaders(Map<String, List<String>> multiMap) {
    multiMap.forEach(this::assertHeaderValues);
    return this;
  }

  public ResponseVerification<T> assertAbsentHeader(String name) {
    return assertHeaderValues(name, List.of());
  }

  public ResponseVerification<T> assertHeaders(HttpHeaders headers) {
    assertEquals(headers, response.headers());
    return this;
  }

  public ResponseVerification<T> assertRequestHeader(String name, Object value) {
    assertHeader(name, value, response.request().headers());
    return this;
  }

  public ResponseVerification<T> assertRequestHeaders(HttpHeaders headers) {
    assertEquals(headers, response.request().headers());
    return this;
  }

  public ResponseVerification<T> assertRequestHeaders(String... headers) {
    return assertRequestHeaders(headers(headers));
  }

  public ResponseVerification<T> assertAbsentRequestHeader(String name) {
    assertEquals(List.of(), response.request().headers().allValues(name));
    return this;
  }

  public ResponseVerification<T> assertSecure() {
    assertTrue(response.sslSession().isPresent());
    return this;
  }

  public ResponseVerification<T> assertNotSecure() {
    assertTrue(response.sslSession().isEmpty());
    return this;
  }

  public ResponseVerification<T> assertEqualSSLSessions(SSLSession expected) {
    var actual = sslSession(this);
    assertEquals(expected.getCipherSuite(), actual.getCipherSuite());
    assertEquals(expected.getProtocol(), actual.getProtocol());
    assertArrayEquals(getPeerCerts(expected), getPeerCerts(actual));
    assertArrayEquals(expected.getLocalCertificates(), actual.getLocalCertificates());
    return this;
  }

  public ResponseVerification<T> assertRequestSentAt(Instant expected) {
    assertTracked();
    assertEquals(expected, tracked.timeRequestSent());
    return this;
  }

  public ResponseVerification<T> assertResponseReceivedAt(Instant expected) {
    assertTracked();
    assertEquals(expected, tracked.timeResponseReceived());
    return this;
  }

  public ResponseVerification<T> assertCacheStatus(CacheStatus expected) {
    assertCacheAware();
    assertEquals(expected, cacheAware.cacheStatus());
    return this;
  }

  public ResponseVerification<T> assertHasNetworkResponse() {
    assertCacheAware();
    var networkResponse = cacheAware.networkResponse();
    assertTrue(networkResponse.isPresent(), "absent network response");
    return this;
  }

  public ResponseVerification<T> assertHasCacheResponse() {
    assertCacheAware();
    var cacheResponse = cacheAware.cacheResponse();
    assertTrue(cacheResponse.isPresent(), "absent cache response");
    return this;
  }

  public ResponseVerification<T> assertHasNoNetworkResponse() {
    assertCacheAware();
    var networkResponse = cacheAware.networkResponse();
    assertTrue(
        networkResponse.isEmpty(),
        () -> "expected no network response: " + networkResponse.orElseThrow());
    return this;
  }

  public ResponseVerification<T> assertHasNoCacheResponse() {
    assertCacheAware();
    var cacheResponse = cacheAware.cacheResponse();
    assertTrue(
        cacheResponse.isEmpty(),
        () -> "expected no cache response: " + cacheResponse.orElseThrow());
    return this;
  }

  public ResponseVerification<T> assertSimilarTo(HttpResponse<?> expected) {
    assertUri(expected.uri());
    assertCode(expected.statusCode());
    assertHeaders(expected.headers());
    if (expected.sslSession().isPresent()) {
      assertSecure();
    } else {
      assertNotSecure();
    }
    return this;
  }

  public ResponseVerification<T> assertEqualTo(HttpResponse<?> expected) {
    assertSimilarTo(expected);
    if (expected instanceof TrackedResponse<?>) {
      var trackedOther = ((TrackedResponse<?>) expected);
      assertRequestSentAt(trackedOther.timeRequestSent());
      assertResponseReceivedAt(trackedOther.timeResponseReceived());
    }
    return this;
  }

  public ResponseVerification<T> assertHit() {
    assertCacheStatus(HIT);
    assertHasNoNetworkResponse();
    assertSimilarTo(cacheResponse().get());
    return this;
  }

  public ResponseVerification<T> assertConditionalHit() {
    assertCacheStatus(CONDITIONAL_HIT);
    assertHasCacheResponse();
    assertHasNetworkResponse();

    var networkResponse = networkResponse();
    networkResponse.assertUri(response.uri());
    networkResponse.assertCode(HTTP_NOT_MODIFIED);

    var cacheResponse = cacheResponse();
    cacheResponse.assertUri(response.uri());
    cacheResponse.assertCode(response.statusCode());

    // Timestamps are set to that of network response
    assertRequestSentAt(networkResponse.getTracked().timeRequestSent());
    assertRequestSentAt(networkResponse.getTracked().timeResponseReceived());

    // Make sure headers are merged correctly as specified
    // in https://httpwg.org/specs/rfc7234.html#freshening.responses
    var networkHeaders = new HashMap<>(networkResponse.get().headers().map());
    var cacheHeaders = new HashMap<>(cacheResponse.get().headers().map());
    response.headers().map().forEach(
        (name, values) -> {
          var networkValues = networkHeaders.get(name);
          var cacheValues = cacheHeaders.get(name);
          // Assert Warning headers with 1xx code are removed
          if ("Warning".equalsIgnoreCase(name)) {
            values.forEach(value -> assertFalse(value.startsWith("1"), value));
            if (cacheValues != null) {
              cacheValues =
                  cacheValues.stream()
                      .filter(value -> !value.startsWith("1"))
                      .collect(Collectors.toUnmodifiableList());
            }
          }

          // Network values override cached unless it's Content-Length
          if (!"Content-Length".equalsIgnoreCase(name)) {
            assertEquals(networkValues != null ? networkValues : cacheValues, values, name);
          } else {
            assertEquals(cacheValues, values, name);
          }
        });

    return this;
  }

  public ResponseVerification<T> assertConditionalMiss() {
    assertCacheStatus(MISS);
    assertHasNetworkResponse();
    assertHasCacheResponse();
    assertNotEquals(HTTP_NOT_MODIFIED, networkResponse().get().statusCode());
    assertEqualTo(networkResponse().get());
    return this;
  }

  public ResponseVerification<T> assertMiss() {
    assertCacheStatus(MISS);
    assertHasNetworkResponse();
    assertHasNoCacheResponse();
    assertEqualTo(networkResponse().get());
    return this;
  }

  public ResponseVerification<T> assertLocallyGenerated() {
    assertCacheStatus(LOCALLY_GENERATED);
    assertEquals(HTTP_GATEWAY_TIMEOUT, response.statusCode());
    assertHasNoNetworkResponse();
    assertHasNoCacheResponse();
    return this;
  }

  public void assertCachedWithSSL() {
    assertSecure();

    cacheResponse().assertEqualSSLSessions(sslSession(this));

    if (cacheAware.networkResponse().isPresent()) {
      cacheResponse().assertEqualSSLSessions(sslSession(this));
    }
  }

  public ResponseVerification<?> networkResponse() {
    assertHasNetworkResponse();
    return verifying(cacheAware.networkResponse().orElseThrow());
  }

  public ResponseVerification<?> cacheResponse() {
    assertHasCacheResponse();
    return verifying(cacheAware.cacheResponse().orElseThrow());
  }

  public void assertTracked() {
    assertNotNull(tracked, "not a TrackedResponse: " + response);
  }

  public void assertCacheAware() {
    assertNotNull(cacheAware, "not a CacheAwareResponse: " + response);
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

  private static void assertHeader(String name, Object value, HttpHeaders headers) {
    var values = headers.allValues(name);
    assertFalse(values.isEmpty(), "no " + name + " headers");
    assertEquals(1, values.size(), "multiple " + name + " header values: " + values);
    assertEquals(value.toString(), values.get(0), name);
  }

  private static SSLSession sslSession(ResponseVerification<?> rv) {
    return rv.get().sslSession().orElseThrow(() -> new AssertionError("no SSLSession"));
  }

  private static Certificate[] getPeerCerts(SSLSession session) {
    try {
      return session.getPeerCertificates();
    } catch (SSLPeerUnverifiedException e) {
      return null;
    }
  }

  public static <T> ResponseVerification<T> verifying(HttpResponse<T> response) {
    return new ResponseVerification<>(response);
  }
}
