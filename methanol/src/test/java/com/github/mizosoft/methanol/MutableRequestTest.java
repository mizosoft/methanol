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

import static com.github.mizosoft.methanol.MutableRequest.GET;
import static com.github.mizosoft.methanol.MutableRequest.POST;
import static com.github.mizosoft.methanol.MutableRequest.create;
import static com.github.mizosoft.methanol.testutils.TestUtils.headers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient.Version;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MutableRequestTest {

  private static final URI uri = URI.create("https://example.com");
  private static final String method = "PUT";
  private static final String[] headersArray = {
      "Content-Type", "application/x-bruh",
      "Content-Length", "69"
  };
  private static final HttpHeaders headers = headers(headersArray);
  private static final BodyPublisher publisher = BodyPublishers.noBody();
  private static final Duration timeout = Duration.ofSeconds(420);
  private static final Version version = Version.HTTP_1_1;
  private static final boolean expectContinue = true;

  @Test
  void setFields() {
    assertHasFields(createWithFields());
  }

  @Test
  void setFieldsAndBuild() {
    assertHasFields(createWithFields().build());
  }

  @Test
  void uriFromString() {
    var uriStr = uri.toString();
    assertEquals(uri, create().uri(uriStr).uri());
    assertEquals(uri, create(uriStr).uri());
    assertEquals(uri, GET(uriStr).uri());
    assertEquals(uri, POST(uriStr, BodyPublishers.noBody()).uri());
  }

  @Test
  void invalidateCachedHeaders() {
    var request = create().header("Content-Length", "69");
    assertEquals(headers("Content-Length", "69"), request.headers());
    request.header("Content-Type", "application/x-bruh");
    assertEquals(
        headers(
            "Content-Length", "69",
            "Content-Type", "application/x-bruh"),
        request.headers());
  }

  @Test
  void copyIsStructural() {
    var request1 = create().header("Content-Length", "69");
    var request2 = request1.copy().header("Content-Type", "application/x-bruh");
    assertEquals(headers("Content-Length", "69"), request1.headers());
    assertEquals(
        headers(
            "Content-Length", "69",
            "Content-Type", "application/x-bruh"),
        request2.headers());
  }

  @Test
  void defaultFields() {
    var request = create();
    assertEquals("GET", request.method());
    assertEquals(URI.create("/"), request.uri());
    assertEquals(headers(/* empty */), request.headers());
    assertEquals(Optional.empty(), request.bodyPublisher());
    assertEquals(Optional.empty(), request.timeout());
    assertEquals(Optional.empty(), request.version());
    assertFalse(request.expectContinue());
  }

  @Test
  void changeHeaders() {
    var request = create().headers(
        "Content-Length", "69",
        "Content-Type", "application/x-bruh");
    assertEquals(
        headers(
            "Content-Length", "69",
            "Content-Type", "application/x-bruh"),
        request.headers());
    assertEquals(
        headers("Content-Type", "application/x-bruh"),
        request.removeHeader("Content-Length").headers());
    assertEquals(
        headers("Content-Type", "application/x-bruh-moment"),
        request.setHeader("Content-Type", "application/x-bruh-moment").headers());
    assertEquals(headers(/* empty */), request.removeHeaders().headers());
  }

  @Test
  void testApply() {
    var request = create().apply(b -> b.uri(uri));
    assertEquals(uri, request.uri());
  }

  @Test
  void testToString() {
    var request = GET("https://google.com");
    assertEquals("https://google.com GET", request.toString());
    assertEquals("https://google.com GET", request.build().toString());
  }

  @Test
  void staticFactories() {
    assertEquals(uri, create(uri).uri());
    var getRequest = GET(uri);
    assertEquals("GET", getRequest.method());
    assertEquals(uri, getRequest.uri());
    var postRequest = POST(uri, publisher);
    assertEquals("POST", postRequest.method());
    assertSame(publisher, postRequest.bodyPublisher().orElseThrow());
  }

  @Test
  void methodShortcuts() {
    var request = create().method("POST", publisher);
    assertEquals("GET", request.GET().method());
    assertTrue(request.bodyPublisher().isEmpty());
    assertEquals("POST", request.POST(publisher).method());
    assertSame(publisher, request.bodyPublisher().orElseThrow());
    assertEquals("DELETE", request.DELETE().method());
    assertTrue(request.bodyPublisher().isEmpty());
    assertEquals("PUT", request.PUT(publisher).method());
    assertSame(publisher, request.bodyPublisher().orElseThrow());
  }

  @Test
  void headers_invalidLength() {
    assertThrows(IllegalArgumentException.class, () -> create().headers(new String[0]));
    assertThrows(
        IllegalArgumentException.class,
        () -> create().headers("Content-Length", "69", "Orphan"));
  }

  @Test
  void illegalHeaders() {
    create().header("foo", "fa \t"); // valid
    assertThrows(IllegalArgumentException.class, () -> create().header("ba\r", "foo"));
    assertThrows(IllegalArgumentException.class, () -> create().headers("Name", "…"));
  }

  @Test
  void illegalTimeout() {
    assertThrows(IllegalArgumentException.class, () -> create().timeout(Duration.ofSeconds(0)));
    assertThrows(IllegalArgumentException.class, () -> create().timeout(Duration.ofSeconds(-1)));
  }

  @Test
  void illegalMethodName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> create().method("ba\r", BodyPublishers.noBody()));
  }

  private static void assertHasFields(HttpRequest req) {
    assertEquals(method, req.method());
    assertEquals(uri, req.uri());
    assertEquals(headers, req.headers());
    assertSame(publisher, req.bodyPublisher().orElseThrow());
    assertEquals(timeout, req.timeout().orElseThrow());
    assertEquals(version, req.version().orElseThrow());
    assertEquals(expectContinue, req.expectContinue());
  }

  private static MutableRequest createWithFields() {
    return MutableRequest.create()
        .uri(uri)
        .method(method, publisher)
        .headers(headersArray)
        .timeout(timeout)
        .version(version)
        .expectContinue(expectContinue);
  }
}
