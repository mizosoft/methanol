/*
 * Copyright (c) 2022 Moataz Abdelnasser
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

import static com.github.mizosoft.methanol.testing.TestUtils.headers;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.TaggableRequest;
import com.github.mizosoft.methanol.TypeRef;
import java.net.URI;
import java.net.http.HttpClient.Version;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.time.Duration;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.InstanceOfAssertFactories;

/** A small DSL for testing {@code HttpRequests}. */
@SuppressWarnings({"UnusedReturnValue", "OptionalUsedAsFieldOrParameterType"})
public final class RequestVerifier {
  private final HttpRequest request;

  public RequestVerifier(HttpRequest request) {
    this.request = request;
  }

  public RequestVerifier hasMethod(String method) {
    assertThat(request.method()).isEqualTo(method);
    return this;
  }

  public RequestVerifier isGET() {
    return hasMethod("GET");
  }

  public RequestVerifier isPOST() {
    return hasMethod("POST");
  }

  public RequestVerifier isPUT() {
    return hasMethod("PUT");
  }

  public RequestVerifier isDELETE() {
    return hasMethod("DELETE");
  }

  public RequestVerifier hasBodyPublisher(BodyPublisher publisher) {
    assertThat(request.bodyPublisher()).hasValue(publisher);
    return this;
  }

  public RequestVerifier hasBodyPublisher(Optional<BodyPublisher> publisher) {
    assertThat(request.bodyPublisher()).isEqualTo(publisher);
    return this;
  }

  public RequestVerifier hasUri(String uri) {
    return hasUri(URI.create(uri));
  }

  public RequestVerifier hasUri(URI uri) {
    assertThat(request.uri()).isEqualTo(uri);
    return this;
  }

  public RequestVerifier hasExpectContinue(boolean value) {
    assertThat(request.expectContinue()).isEqualTo(value);
    return this;
  }

  public RequestVerifier hasVersion(Version version) {
    assertThat(request.version()).hasValue(version);
    return this;
  }

  public RequestVerifier hasVersion(Optional<Version> version) {
    assertThat(request.version()).isEqualTo(version);
    return this;
  }

  public RequestVerifier containsHeader(String name, String value) {
    assertThat(request.headers().allValues(name)).singleElement().isEqualTo(value);
    return this;
  }

  public RequestVerifier containsHeadersExactly(String... headers) {
    return containsHeadersExactly(headers(headers));
  }

  public RequestVerifier containsHeadersExactly(HttpHeaders headers) {
    assertThat(request.headers()).isEqualTo(headers);
    return this;
  }

  public RequestVerifier hasEmptyHeaders() {
    assertThat(request.headers().map()).isEmpty();
    return this;
  }

  public RequestVerifier containsHeaders(HttpHeaders headers) {
    assertThat(request.headers().map()).containsAllEntriesOf(headers.map());
    return this;
  }

  public <T> RequestVerifier containsTag(Class<T> type, T value) {
    assertThat(request)
        .asInstanceOf(InstanceOfAssertFactories.type(TaggableRequest.class))
        .extracting(request -> request.tag(type), Assertions.OPTIONAL)
        .hasValue(value);
    return this;
  }

  public <T> RequestVerifier containsTag(TypeRef<T> type, T value) {
    assertThat(request)
        .asInstanceOf(InstanceOfAssertFactories.type(TaggableRequest.class))
        .extracting(request -> request.tag(type), Assertions.OPTIONAL)
        .hasValue(value);
    return this;
  }

  public RequestVerifier doesNotContainTag(Class<?> type) {
    assertThat(TaggableRequest.from(request))
        .extracting(request -> request.tag(type), Assertions.OPTIONAL)
        .isEmpty();
    return this;
  }

  public RequestVerifier hasTimeout(Duration timeout) {
    assertThat(request.timeout()).hasValue(timeout);
    return this;
  }

  public RequestVerifier hasTimeout(Optional<Duration> timeout) {
    assertThat(request.timeout()).isEqualTo(timeout);
    return this;
  }

  public RequestVerifier hasNoBody() {
    assertThat(request.bodyPublisher()).isEmpty();
    return this;
  }

  public RequestVerifier hasNoTimeout() {
    assertThat(request.timeout()).isEmpty();
    return this;
  }

  public RequestVerifier hasNoVersion() {
    assertThat(request.version()).isEmpty();
    return this;
  }

  public RequestVerifier isEqualTo(HttpRequest other) {
    assertThat(request).isEqualTo(other);
    return this;
  }

  public RequestVerifier isDeeplyEqualTo(HttpRequest other) {
    return hasUri(other.uri())
        .hasMethod(other.method())
        .containsHeadersExactly(other.headers())
        .hasBodyPublisher(other.bodyPublisher())
        .hasTimeout(other.timeout())
        .hasVersion(other.version())
        .hasExpectContinue(other.expectContinue());
  }
}
