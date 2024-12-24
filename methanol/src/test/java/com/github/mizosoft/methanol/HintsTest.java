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

package com.github.mizosoft.methanol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.from;
import static org.assertj.core.api.InstanceOfAssertFactories.OPTIONAL;

import com.github.mizosoft.methanol.BodyAdapter.Hints;
import com.github.mizosoft.methanol.internal.extensions.HeadersBuilder;
import com.github.mizosoft.methanol.testing.ImmutableResponseInfo;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.ResponseInfo;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HintsTest {
  @Test
  void emptyHints() {
    assertThat(Hints.empty())
        .matches(hints -> hints.toMap().isEmpty(), "Has empty map")
        .matches(hints -> hints.mediaType().isEmpty(), "Has no MediaType hint")
        .matches(hints -> hints.request().isEmpty(), "Has no HttpRequest hint")
        .matches(hints -> hints.responseInfo().isEmpty(), "Has no ResponseInfo hint")
        .matches(hints -> hints.get(Integer.class).isEmpty(), "Has not custom hints")
        .isEqualTo(Hints.newBuilder().build())
        .hasSameHashCodeAs(Hints.newBuilder().build());
  }

  @Test
  void mediaTypeHint() {
    assertThat(Hints.of(MediaType.TEXT_PLAIN))
        .extracting(Hints::mediaType, OPTIONAL)
        .hasValue(MediaType.TEXT_PLAIN);
  }

  @Test
  void mediaTypeHintFromBuilder() {
    assertThat(Hints.newBuilder().put(MediaType.class, MediaType.TEXT_PLAIN).build())
        .extracting(Hints::mediaType, OPTIONAL)
        .hasValue(MediaType.TEXT_PLAIN);
  }

  @Test
  void requestHintFromBuilder() {
    assertThat(
            Hints.newBuilder()
                .put(HttpRequest.class, MutableRequest.GET("https://example.com"))
                .build())
        .extracting(Hints::request, OPTIONAL)
        .hasValue(MutableRequest.GET("https://example.com"));
  }

  @Test
  void responseInfoHintFromBuilder() {
    var headersBuilder = new HeadersBuilder();
    headersBuilder.add("X", "A");
    var responseInfo = new ImmutableResponseInfo(200, headersBuilder.build(), Version.HTTP_1_1);
    assertThat(Hints.newBuilder().put(ResponseInfo.class, responseInfo).build())
        .extracting(Hints::responseInfo)
        .satisfies(optional -> assertHasResponseInfo(optional, responseInfo));
  }

  @Test
  void allKnownHints() {
    var headersBuilder = new HeadersBuilder();
    headersBuilder.add("X", "A");
    var responseInfo = new ImmutableResponseInfo(200, headersBuilder.build(), Version.HTTP_1_1);
    assertThat(
            Hints.newBuilder()
                .put(MediaType.class, MediaType.TEXT_PLAIN)
                .put(HttpRequest.class, MutableRequest.GET("https://example.com"))
                .put(ResponseInfo.class, responseInfo)
                .build())
        .returns(Optional.of(MediaType.TEXT_PLAIN), from(Hints::mediaType))
        .returns(Optional.of(MutableRequest.GET("https://example.com")), from(Hints::request))
        .extracting(Hints::responseInfo)
        .satisfies(optional -> assertHasResponseInfo(optional, responseInfo));
  }

  @Test
  void unknownHints() {
    assertThat(Hints.newBuilder().put(Integer.class, 1).build())
        .extracting(hints -> hints.get(Integer.class), OPTIONAL)
        .hasValue(1);
  }

  @Test
  void knownWithUnknownHints() {
    var headersBuilder = new HeadersBuilder();
    headersBuilder.add("X", "A");
    var responseInfo = new ImmutableResponseInfo(200, headersBuilder.build(), Version.HTTP_1_1);
    assertThat(
            Hints.newBuilder()
                .put(MediaType.class, MediaType.TEXT_PLAIN)
                .put(HttpRequest.class, MutableRequest.GET("https://example.com"))
                .put(ResponseInfo.class, responseInfo)
                .put(Integer.class, 1)
                .build())
        .returns(Optional.of(MediaType.TEXT_PLAIN), from(Hints::mediaType))
        .returns(Optional.of(MutableRequest.GET("https://example.com")), from(Hints::request))
        .returns(Optional.of(1), from(hints -> hints.get(Integer.class)))
        .extracting(Hints::responseInfo)
        .satisfies(optional -> assertHasResponseInfo(optional, responseInfo));
  }

  @Test
  void equalsAndHashCode() {
    var hints1 = Hints.of(MediaType.TEXT_PLAIN);
    var hints1Copy = Hints.newBuilder().put(MediaType.class, MediaType.TEXT_PLAIN).build();
    var hints2 =
        Hints.newBuilder().put(MediaType.class, MediaType.TEXT_PLAIN).put(Integer.class, 1).build();
    var hints2Copy = hints1.mutate().put(Integer.class, 1).build();
    assertThat(hints1).isEqualTo(hints1Copy).isNotEqualTo(hints2).hasSameHashCodeAs(hints1Copy);
    assertThat(hints2).isEqualTo(hints2Copy).isNotEqualTo(hints1).hasSameHashCodeAs(hints2Copy);
  }

  @Test
  void encoderHints() {
    // Infer MediaType from body.
    assertThat(
            Hints.newBuilder()
                .forEncoder(MutableRequest.POST("https://example.com", "abc", MediaType.TEXT_PLAIN))
                .build())
        .returns(
            Optional.of(MutableRequest.POST("https://example.com", "abc", MediaType.TEXT_PLAIN)),
            from(Hints::request))
        .returns(Optional.of(MediaType.TEXT_PLAIN), from(Hints::mediaType));

    // Infer MediaType from headers.
    assertThat(
            Hints.newBuilder()
                .forEncoder(
                    MutableRequest.POST("https://example.com", BodyPublishers.ofString("abc"))
                        .header("Content-Type", "text/plain"))
                .build())
        .returns(
            Optional.of(
                MutableRequest.POST("https://example.com", "abc", MediaType.TEXT_PLAIN)
                    .header("Content-Type", "text/plain")),
            from(Hints::request))
        .returns(Optional.of(MediaType.TEXT_PLAIN), from(Hints::mediaType));

    // No media type.
    assertThat(Hints.newBuilder().forEncoder(MutableRequest.GET("https://example.com")).build())
        .returns(Optional.of(MutableRequest.GET("https://example.com")), from(Hints::request))
        .returns(Optional.empty(), from(Hints::mediaType));
  }

  @Test
  void decoderHints() {
    var headersBuilder = new HeadersBuilder();
    headersBuilder.add("Content-Type", "text/plain");
    var responseInfo = new ImmutableResponseInfo(200, headersBuilder.build(), Version.HTTP_1_1);
    assertThat(Hints.newBuilder().forDecoder(responseInfo).build())
        .returns(Optional.of(MediaType.TEXT_PLAIN), from(Hints::mediaType))
        .extracting(Hints::responseInfo)
        .satisfies(optional -> assertHasResponseInfo(optional, responseInfo));
  }

  @Test
  void clearHints() {
    assertThat(
            Hints.newBuilder()
                .put(MediaType.class, MediaType.TEXT_PLAIN)
                .put(HttpRequest.class, MutableRequest.GET("https://example.com"))
                .put(ResponseInfo.class, new ImmutableResponseInfo())
                .put(String.class, "a")
                .removeAll()
                .build())
        .isEqualTo(Hints.empty());
  }

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  private static void assertHasResponseInfo(Optional<ResponseInfo> actual, ResponseInfo expected) {
    assertThat(actual)
        .hasValueSatisfying(
            responseInfo ->
                assertThat(responseInfo)
                    .returns(expected.statusCode(), from(ResponseInfo::statusCode))
                    .returns(expected.headers(), from(ResponseInfo::headers))
                    .returns(expected.version(), from(ResponseInfo::version)));
  }
}
