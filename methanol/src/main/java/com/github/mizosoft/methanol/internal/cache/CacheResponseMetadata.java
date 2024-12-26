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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.mizosoft.methanol.MutableRequest;
import com.github.mizosoft.methanol.ResponseBuilder;
import com.github.mizosoft.methanol.TrackedResponse;
import com.github.mizosoft.methanol.internal.extensions.HeadersBuilder;
import com.github.mizosoft.methanol.internal.text.HeaderValueTokenizer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient.Version;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Metadata for each response entry in the cache. */
public final class CacheResponseMetadata {
  private static final int VARINT_SHIFT = 7;
  private static final int VARINT_MASK = 0x7F;
  private static final int VARINT_HAS_MORE_MASK = 0xFF & ~VARINT_MASK;
  private static final long INT_MASK = 0xFFFFFFFFL;

  private static final int FLAG_HAS_SSL_INFO = 0x1;

  private final URI uri;
  private final String requestMethod;
  private final HttpHeaders varyHeaders;
  private final int statusCode;
  private final HttpHeaders responseHeaders;
  private final Instant timeRequestSent;
  private final Instant timeResponseReceived;
  private final @Nullable SSLSession sslSession;

  private CacheResponseMetadata(
      URI uri,
      String requestMethod,
      HttpHeaders varyHeaders,
      int statusCode,
      HttpHeaders responseHeaders,
      Instant timeRequestSent,
      Instant timeResponseReceived,
      @Nullable SSLSession sslSession) {
    this.uri = uri;
    this.requestMethod = requestMethod;
    this.varyHeaders = varyHeaders;
    this.statusCode = statusCode;
    this.responseHeaders = responseHeaders;
    this.timeRequestSent = timeRequestSent;
    this.timeResponseReceived = timeResponseReceived;
    this.sslSession = sslSession;
  }

  HttpHeaders varyHeadersForTesting() {
    return varyHeaders;
  }

  public URI uri() {
    return uri;
  }

  public boolean matches(HttpRequest request) {
    return uri.equals(request.uri())
        && requestMethod.equalsIgnoreCase(request.method())
        && selectedBy(request.headers());
  }

  private boolean selectedBy(HttpHeaders requestHeaders) {
    // TODO this won't work for ["Value1, Value2"] with ["Value1", "Value2"]
    return varyFields(responseHeaders).stream()
        .allMatch(
            name -> unorderedEquals(varyHeaders.allValues(name), requestHeaders.allValues(name)));
  }

  private static boolean unorderedEquals(List<String> left, List<String> right) {
    if (left.size() != right.size()) {
      return false;
    }

    var mutableOther = new ArrayList<>(right);
    for (var value : left) {
      int i = mutableOther.indexOf(value);
      if (i < 0) {
        return false;
      }
      mutableOther.remove(i);
    }
    return true;
  }

  public ByteBuffer encode() {
    var writer = new MetadataWriter();
    writer.writeInt(sslSession != null ? FLAG_HAS_SSL_INFO : 0);
    writer.writeUtf8(uri.toString());
    writer.writeUtf8(requestMethod);
    writer.writeHeaders(varyHeaders);
    writer.writeInt(statusCode);
    writer.writeHeaders(responseHeaders);
    writer.writeInstant(timeRequestSent);
    writer.writeInstant(timeResponseReceived);
    if (sslSession != null) {
      try {
        writer.writeSSLSession(sslSession);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return writer.snapshot();
  }

  public ResponseBuilder<?> toResponseBuilder() {
    return ResponseBuilder.create()
        .uri(uri)
        .request(
            MutableRequest.create(uri)
                .method(requestMethod, BodyPublishers.noBody())
                .headers(varyHeaders)
                .toImmutableRequest())
        .statusCode(statusCode)
        .headers(responseHeaders)
        .timeRequestSent(timeRequestSent)
        .timeResponseReceived(timeResponseReceived)
        .sslSession(sslSession)
        .version(Version.HTTP_1_1);
  }

  public static CacheResponseMetadata decode(ByteBuffer metadataBuffer) throws IOException {
    var reader = new MetadataReader(metadataBuffer);
    int flags = reader.readInt();
    var uri = parseUri(reader.readUtf8String());
    var requestMethod = reader.readUtf8String();
    var varyHeaders = reader.readHeaders();
    int statusCode = reader.readInt();
    var headers = reader.readHeaders();
    var timeRequestSent = reader.readInstant();
    var timeResponseReceived = reader.readInstant();
    var sslSession = (flags & FLAG_HAS_SSL_INFO) != 0 ? reader.readSSLSession() : null;
    return new CacheResponseMetadata(
        uri,
        requestMethod,
        varyHeaders,
        statusCode,
        headers,
        timeRequestSent,
        timeResponseReceived,
        sslSession);
  }

  private static URI parseUri(String uri) throws IOException {
    try {
      return new URI(uri);
    } catch (URISyntaxException e) {
      throw new IOException("invalid URI", e);
    }
  }

  public static CacheResponseMetadata from(TrackedResponse<?> response) {
    return new CacheResponseMetadata(
        response.uri(),
        response.request().method(),
        varyHeaders(response.request().headers(), response.headers()),
        response.statusCode(),
        response.headers(),
        response.timeRequestSent(),
        response.timeResponseReceived(),
        response.sslSession().orElse(null));
  }

  public static Set<String> varyFields(HttpHeaders headers) {
    var values = headers.allValues("Vary");
    if (values.isEmpty()) {
      return Collections.emptySet();
    }

    // Vary = "*" / 1#field-name
    var fields = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    for (var value : values) {
      var tokenizer = new HeaderValueTokenizer(value);
      do {
        fields.add(tokenizer.nextToken());
      } while (tokenizer.consumeDelimiter(','));
    }
    return Collections.unmodifiableSet(fields);
  }

  private static HttpHeaders varyHeaders(HttpHeaders requestHeaders, HttpHeaders responseHeaders) {
    var builder = new HeadersBuilder();
    for (var name : varyFields(responseHeaders)) {
      requestHeaders.allValues(name).forEach(value -> builder.addLenient(name, value));
    }
    return builder.build();
  }

  private static final class MetadataReader {
    private final ByteBuffer buffer;

    MetadataReader(ByteBuffer buffer) {
      // Slice to start with position = 0 to report read bytes when EOF is reached prematurely.
      this.buffer = buffer.slice();
    }

    int readInt() throws IOException {
      return (int) readVarint(Integer.SIZE);
    }

    private long readLong() throws IOException {
      return readVarint(Long.SIZE);
    }

    private long readVarint(int sizeInBits) throws IOException {
      long value = 0L;
      for (int shift = 0; shift < sizeInBits; shift += VARINT_SHIFT) {
        long currentByte = requireByte() & 0xFF; // Use long as shift might exceed 32.
        value |= (currentByte & VARINT_MASK) << shift;
        if ((currentByte & VARINT_HAS_MORE_MASK) == 0) {
          return value;
        }
      }
      throw new IOException("wrong varint format");
    }

    private byte requireByte() throws EOFException {
      try {
        return buffer.get();
      } catch (BufferUnderflowException e) {
        throw endOfInput();
      }
    }

    private CharBuffer readUtf8Chars() throws IOException {
      int length = readInt();
      int originalLimit = buffer.limit();
      try {
        buffer.limit(buffer.position() + length);
      } catch (IllegalArgumentException e) {
        throw endOfInput();
      }
      var value = UTF_8.decode(buffer);
      buffer.limit(originalLimit);
      return value;
    }

    String readUtf8String() throws IOException {
      return readUtf8Chars().toString();
    }

    HttpHeaders readHeaders() throws IOException {
      var builder = new HeadersBuilder();
      for (int i = 0, count = readInt(); i < count; i++) {
        addHeader(builder, readUtf8Chars());
      }
      return builder.build();
    }

    private void addHeader(HeadersBuilder builder, CharBuffer header) throws IOException {
      int separatorIndex = indexOfHeaderSeparator(header, 0);
      if (separatorIndex == 0) {
        // Colon is the first character, this can occur in two cases:
        //   - The header is an HTTP2 pseudo-header
        //   - The header has an empty name
        // #2 is never expected as it's not allowed (header names must be non-empty tokens).
        // AFAIK the client ignores these so they are never written to the cache and hence
        // are treated as cache corruption.
        separatorIndex = indexOfHeaderSeparator(header, 1);
      }

      if (separatorIndex <= 0) {
        throw new IOException("malformed header");
      }

      int originalLimit = header.limit();
      var name = header.limit(separatorIndex).toString();
      var value = header.limit(originalLimit).position(separatorIndex + 1).toString();
      builder.add(name.trim(), value.trim());
    }

    private int indexOfHeaderSeparator(CharBuffer buffer, int offset) {
      for (int p = offset; p < buffer.limit(); p++) {
        if (buffer.get(p) == ':') {
          return p;
        }
      }
      return -1;
    }

    private byte[] readByteArray() throws IOException {
      int length = readInt();
      var array = new byte[length];
      try {
        buffer.get(array);
      } catch (BufferUnderflowException e) {
        throw new EOFException(
            "expected " + length + " bytes (position = " + buffer.position() + ")");
      }
      return array;
    }

    private List<X509Certificate> readCertificates(CertificateFactory factory)
        throws IOException, CertificateException {
      var certificates = new ArrayList<X509Certificate>();
      for (int i = 0, count = readInt(); i < count; i++) {
        var certBytes = readByteArray();
        certificates.add(
            (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(certBytes)));
      }
      return Collections.unmodifiableList(certificates);
    }

    SSLSession readSSLSession() throws IOException {
      try {
        var certificateFactory = CertificateFactory.getInstance("X.509");
        var cipherSuite = readUtf8String();
        var protocol = readUtf8String();
        var peerCertificates = readCertificates(certificateFactory);
        var localCertificates = readCertificates(certificateFactory);
        return new CacheRecoveredSSLSession(
            cipherSuite, protocol, peerCertificates, localCertificates);
      } catch (CertificateException e) {
        throw new IOException(e);
      }
    }

    Instant readInstant() throws IOException {
      return Instant.ofEpochMilli(readLong());
    }

    private EOFException endOfInput() {
      return new EOFException("unexpected end of input (position = " + buffer.position() + ")");
    }
  }

  private static final class MetadataWriter {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    MetadataWriter() {}

    void writeInt(int value) {
      writeVarint(value & INT_MASK);
    }

    private void writeLong(long value) {
      writeVarint(value);
    }

    private void writeByteArray(byte[] array) {
      writeInt(array.length);
      buffer.write(array, 0, array.length);
    }

    void writeUtf8(String value) {
      writeByteArray(value.getBytes(UTF_8));
    }

    private void writeVarint(long value) {
      while ((value & ~VARINT_MASK) != 0) { // Value requires more than one varint byte?
        buffer.write(((int) value & VARINT_MASK) | VARINT_HAS_MORE_MASK);
        value >>>= VARINT_SHIFT;
      }
      buffer.write((int) value); // Last varint byte
    }

    void writeInstant(Instant instant) {
      writeLong(instant.toEpochMilli());
    }

    void writeHeaders(HttpHeaders headers) {
      var headersMap = headers.map();
      int deepHeaderCount = headersMap.values().stream().mapToInt(Collection::size).sum();
      writeInt(deepHeaderCount);
      headersMap.forEach((name, values) -> values.forEach(value -> writeUtf8(name + ':' + value)));
    }

    void writeSSLSession(SSLSession session) throws IOException {
      writeUtf8(session.getCipherSuite());
      writeUtf8(session.getProtocol());
      // getPeerCertificates throws SSLPeerUnverifiedException if it has none
      try {
        writeCertificates(session.getPeerCertificates());
      } catch (SSLPeerUnverifiedException e) {
        writeInt(0);
      }
      // getLocalCertificates returns null if it has none
      var localCertificates = session.getLocalCertificates();
      if (localCertificates != null) {
        writeCertificates(localCertificates);
      } else {
        writeInt(0);
      }
    }

    private void writeCertificates(Certificate[] certificates) throws IOException {
      writeInt(certificates.length);
      try {
        for (var cert : certificates) {
          writeByteArray(cert.getEncoded());
        }
      } catch (CertificateEncodingException e) {
        throw new IOException(e);
      }
    }

    ByteBuffer snapshot() {
      return ByteBuffer.wrap(buffer.toByteArray());
    }
  }

  private static final class CacheRecoveredSSLSession implements SSLSession {
    private final String cipherSuite;
    private final String protocol;
    private final List<X509Certificate> peerCertificates;
    private final List<X509Certificate> localCertificates;

    CacheRecoveredSSLSession(
        String cipherSuite,
        String protocol,
        List<X509Certificate> peerCertificates,
        List<X509Certificate> localCertificates) {
      this.cipherSuite = cipherSuite;
      this.protocol = protocol;
      this.localCertificates = localCertificates;
      this.peerCertificates = peerCertificates;
    }

    @Override
    public String getCipherSuite() {
      return cipherSuite;
    }

    @Override
    public String getProtocol() {
      return protocol;
    }

    @Override
    public Certificate[] getPeerCertificates() throws SSLPeerUnverifiedException {
      requireAuthenticatedPeer();
      return peerCertificates.toArray(Certificate[]::new);
    }

    @Override
    public @Nullable Certificate[] getLocalCertificates() {
      return localCertificates.isEmpty() ? null : localCertificates.toArray(Certificate[]::new);
    }

    @Override
    public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
      requireAuthenticatedPeer();
      return x509principal(peerCertificates);
    }

    @Override
    public @Nullable Principal getLocalPrincipal() {
      return localCertificates.isEmpty() ? null : x509principal(localCertificates);
    }

    private void requireAuthenticatedPeer() throws SSLPeerUnverifiedException {
      if (peerCertificates.isEmpty()) {
        throw new SSLPeerUnverifiedException("peer not authenticated");
      }
    }

    private static Principal x509principal(List<X509Certificate> certificates) {
      return certificates.get(0).getSubjectX500Principal();
    }

    // The following properties are not cached and hence either throw
    // UnsupportedOperationException or return a default value if possible

    @Override
    public byte[] getId() {
      throw unsupported();
    }

    @Override
    public @Nullable SSLSessionContext getSessionContext() {
      return null;
    }

    @Override
    public long getCreationTime() {
      throw unsupported();
    }

    @Override
    public long getLastAccessedTime() {
      throw unsupported();
    }

    @Override
    public void invalidate() {}

    @Override
    public boolean isValid() {
      return false;
    }

    @Override
    public void putValue(String name, Object value) {
      throw unsupported();
    }

    @Override
    public @Nullable Object getValue(String name) {
      return null;
    }

    @Override
    public void removeValue(String name) {}

    @Override
    public String[] getValueNames() {
      return new String[0];
    }

    @SuppressWarnings({"removal", "deprecation", "RedundantSuppression"})
    @Override
    public javax.security.cert.X509Certificate[] getPeerCertificateChain() {
      throw unsupported();
    }

    @Override
    public @Nullable String getPeerHost() {
      return null;
    }

    @Override
    public int getPeerPort() {
      return -1;
    }

    @Override
    public int getPacketBufferSize() {
      throw unsupported();
    }

    @Override
    public int getApplicationBufferSize() {
      throw unsupported();
    }

    private static UnsupportedOperationException unsupported() {
      throw new UnsupportedOperationException(
          "SSLSession recovered from cache doesn't support this method");
    }
  }
}
