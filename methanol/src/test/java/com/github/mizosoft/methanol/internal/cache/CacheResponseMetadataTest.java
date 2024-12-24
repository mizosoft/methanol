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

package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.MutableRequest.GET;
import static com.github.mizosoft.methanol.testing.TestUtils.headers;
import static java.net.http.HttpRequest.BodyPublishers.noBody;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.mizosoft.methanol.MutableRequest;
import com.github.mizosoft.methanol.ResponseBuilder;
import com.github.mizosoft.methanol.TrackedResponse;
import com.github.mizosoft.methanol.testing.TestUtils;
import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient.Version;
import java.net.http.HttpHeaders;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

class CacheResponseMetadataTest {
  @Test
  void varyHeaders() {
    var builder = response("Vary", "Accept-Encoding", "Vary", "Accept, Accept-Language, Cookie");
    var gzip = headers("Accept-Encoding", "gzip");
    var gzipHtml = headers("Accept-Encoding", "gzip", "Accept", "text/html");
    var gzipHtmlFrench =
        headers("Accept-Encoding", "gzip", "Accept", "text/html", "Accept-Language", "fr-CH");
    var gzipHtmlFrenchWithCookie =
        headers(
            "Accept-Encoding", "gzip",
            "Accept", "text/html",
            "Accept-Language", "fr-CH",
            "Cookie", "foo=bar");
    var metadata = metadata(builder.request(request()).buildTrackedResponse());
    var metadataGzip = metadata(builder.request(request(gzip)).buildTrackedResponse());
    var metadataGzipHtml = metadata(builder.request(request(gzipHtml)).buildTrackedResponse());
    var metadataGzipHtmlFrench = metadata(builder.request(request(gzipHtmlFrench)).buildTrackedResponse());
    var metadataGzipHtmlFrenchWithCookie =
        metadata(builder.request(request(gzipHtmlFrenchWithCookie)).buildTrackedResponse());
    assertEquals(headers(), metadata.varyHeadersForTesting());
    assertEquals(gzip, metadataGzip.varyHeadersForTesting());
    assertEquals(gzipHtml, metadataGzipHtml.varyHeadersForTesting());
    assertEquals(gzipHtmlFrench, metadataGzipHtmlFrench.varyHeadersForTesting());
    assertEquals(
        gzipHtmlFrenchWithCookie, metadataGzipHtmlFrenchWithCookie.varyHeadersForTesting());
  }

  @Test
  void persistence() throws IOException {
    testPersistence(null);
  }

  @Test
  void persistenceWithSSL() throws IOException {
    testPersistence(new TestSSLSession(false));
    testPersistence(new TestSSLSession(true));
  }

  @Test
  void requestMatching() {
    var builder = response("Vary", "Accept-Encoding, Accept-Language");
    var metadata = metadata(builder.request(request()).buildTrackedResponse());
    var metadataGzip = metadata(builder.request(request("Accept-Encoding", "gzip")).buildTrackedResponse());
    var metadataGzipFrench =
        metadata(
            builder
                .request(request("Accept-Encoding", "gzip", "Accept-Language", "fr-CH"))
                .buildTrackedResponse());
    var metadataWithCookies =
        metadata(
            builder
                .setHeader("Vary", "Cookie")
                .request(request("Cookie", "foo=bar", "Cookie", "abc=123"))
                .buildTrackedResponse());

    assertTrue(metadata.matches(request()));
    assertTrue(metadata.matches(request("X-My-Header", ":)")));
    assertTrue(metadataGzip.matches(request("Accept-Encoding", "gzip")));
    assertTrue(
        metadataGzipFrench.matches(
            request("Accept-Encoding", "gzip", "Accept-Language", "fr-CH", "X-My-Header", ":)")));
    assertTrue(metadataWithCookies.matches(request("Cookie", "foo=bar", "Cookie", "abc=123")));
    assertTrue(metadataWithCookies.matches(request("Cookie", "abc=123", "Cookie", "foo=bar")));

    assertFalse(metadata.matches(request().uri("http://www.example.com"))); // not https
    assertFalse(metadata.matches(request().POST(noBody()))); // Different method
    assertFalse(metadata.matches(request("Accept-Encoding", "gzip")));
    assertFalse(metadataGzip.matches(request("Accept-Encoding", "br")));
    assertFalse(
        metadataGzip.matches(request("Accept-Encoding", "gzip", "Accept-Language", "en-GB")));
    assertFalse(
        metadataGzipFrench.matches(request("Accept-Encoding", "gzip", "Accept-Language", "en-GB")));
    assertFalse(metadataWithCookies.matches(request("Cookie", "foo=bar")));
  }

  @Test
  void endOfInput() {
    var response = response("Cache-Control", "max-age=4200").request(request()).buildTrackedResponse();
    var buffer = metadata(response).encode();
    buffer.limit(buffer.limit() - 10);
    assertThrows(EOFException.class, () -> metadata(buffer));
  }

  @Test
  void pseudoAndEmptyHeaders() throws IOException {
    var response =
        response(":status", "200", "X-My-Empty-Header", "")
            .request(request())
            .buildTrackedResponse();
    assertMetadataPersisted(response);
  }

  private static CacheResponseMetadata metadata(TrackedResponse<?> response) {
    return CacheResponseMetadata.from(response);
  }

  private static CacheResponseMetadata metadata(ByteBuffer metadataBuffer) throws IOException {
    return CacheResponseMetadata.decode(metadataBuffer);
  }

  private static void testPersistence(@Nullable SSLSession session) throws IOException {
    var response =
        response("Vary", "Accept-Encoding", "Cache-Control", "private; max-age=6969")
            .request(request("Accept-Encoding", "gzip", "Accept", "text/html"))
            .sslSession(session)
            .buildTrackedResponse();
    assertMetadataPersisted(response);
  }

  private static void assertMetadataPersisted(TrackedResponse<?> response) throws IOException {
    var metadata = metadata(response);
    var buffer = metadata.encode();
    var recovered = metadata(buffer).toResponseBuilder().buildTrackedResponse();
    assertEqualResponses(response, recovered);
    assertEquals(response.request().uri(), recovered.request().uri());
    assertEquals(response.request().method(), recovered.request().method());
    assertTrue(
        response.request().headers().map().entrySet()
            .containsAll(metadata.varyHeadersForTesting().map().entrySet()));
    assertTrue(
        response.request().headers().map().entrySet()
            .containsAll(recovered.request().headers().map().entrySet()));
  }

  private static void assertEqualResponses(TrackedResponse<?> expected, TrackedResponse<?> actual) {
    assertEquals(expected.uri(), actual.uri());
    assertEquals(expected.statusCode(), actual.statusCode());
    assertEquals(expected.headers(), actual.headers());
    assertEquals(expected.version(), actual.version());
    assertEquals(expected.timeRequestSent(), actual.timeRequestSent());
    assertEquals(expected.timeResponseReceived(), actual.timeResponseReceived());
    assertEquals(expected.sslSession().isPresent(), actual.sslSession().isPresent());
    if (expected.sslSession().isPresent()) {
      assertEqualSSLSessions(expected.sslSession().get(), actual.sslSession().get());
    }
  }

  private static void assertEqualSSLSessions(SSLSession expected, SSLSession actual) {
    assertEquals(expected.getCipherSuite(), actual.getCipherSuite());
    assertEquals(expected.getProtocol(), actual.getProtocol());

    Certificate[] peerCerts = null;
    Certificate[] recoveredPeerCerts = null;
    try {
      peerCerts = expected.getPeerCertificates();
    } catch (SSLPeerUnverifiedException ignored) {
    }
    try {
      recoveredPeerCerts = actual.getPeerCertificates();
    } catch (SSLPeerUnverifiedException ignored) {
    }
    assertArrayEquals(peerCerts, recoveredPeerCerts);
    assertArrayEquals(expected.getLocalCertificates(), actual.getLocalCertificates());
  }

  private static MutableRequest request(String... headers) {
    return GET("https://www.example.com").headers(headers(headers));
  }

  private static MutableRequest request(HttpHeaders headers) {
    return GET("https://www.example.com").headers(headers);
  }

  private static ResponseBuilder<?> response(String... responseHeaders) {
    var uri = URI.create("https://www.example.com");
    var timeSent = Instant.ofEpochSecond(1605276170);
    var timeReceived = timeSent.plus(Duration.ofSeconds(1));
    return ResponseBuilder.create()
        .uri(uri)
        .statusCode(200)
        .headers(headers(responseHeaders))
        .timeRequestSent(timeSent)
        .timeResponseReceived(timeReceived)
        .version(Version.HTTP_1_1);
  }

  private static final class TestSSLSession implements SSLSession {
    private final String cipherSuite;
    private final String protocol;
    private final List<X509Certificate> peerCertificates;
    private final List<X509Certificate> localCertificates;

    TestSSLSession(boolean useCerts) {
      this.cipherSuite = "TLS_AES_128_GCM_SHA256";
      this.protocol = "TLSv1.3";
      if (useCerts) {
        peerCertificates = localCertificates = List.of(TestUtils.localhostCert());
      } else {
        peerCertificates = localCertificates = List.of();
      }
    }

    @Override
    public Certificate[] getPeerCertificates() throws SSLPeerUnverifiedException {
      if (peerCertificates.isEmpty()) {
        throw new SSLPeerUnverifiedException("peer unverified");
      }
      return peerCertificates.toArray(Certificate[]::new);
    }

    @Override
    public Certificate[] getLocalCertificates() {
      return localCertificates.isEmpty() ? null : localCertificates.toArray(Certificate[]::new);
    }

    @Override
    public String getCipherSuite() {
      return cipherSuite;
    }

    @Override
    public String getProtocol() {
      return protocol;
    }

    @Override public byte[] getId() { return fail(); }
    @Override public SSLSessionContext getSessionContext() { return fail(); }
    @Override public long getCreationTime() { return fail(); }
    @Override public long getLastAccessedTime() { return fail(); }
    @Override public void invalidate() { fail(); }
    @Override public boolean isValid() { return fail(); }
    @Override public void putValue(String name, Object value) { fail(); }
    @Override public Object getValue(String name) { return fail(); }
    @Override public void removeValue(String name) { fail(); }
    @Override public String[] getValueNames() { return fail(); }
    @SuppressWarnings({"deprecation", "removal"})
    @Override public javax.security.cert.X509Certificate[] getPeerCertificateChain() { return fail(); }
    @Override public Principal getPeerPrincipal() { return fail(); }
    @Override public Principal getLocalPrincipal() { return fail(); }
    @Override public String getPeerHost() { return fail(); }
    @Override public int getPeerPort() { return fail(); }
    @Override public int getPacketBufferSize() { return fail(); }
    @Override public int getApplicationBufferSize() { return fail(); }
  }
}
