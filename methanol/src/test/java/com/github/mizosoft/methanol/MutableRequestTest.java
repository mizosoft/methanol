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

import static com.github.mizosoft.methanol.testing.TestUtils.headers;
import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.github.mizosoft.methanol.BodyAdapter.Hints;
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter;
import com.github.mizosoft.methanol.testing.TestUtils;
import java.net.URI;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class MutableRequestTest {
  @Test
  void setBasicFields() {
    var publisher = BodyPublishers.ofString("XYZ");
    var request =
        MutableRequest.create()
            .uri("https://example.com")
            .method("PUT", publisher)
            .header("Content-Type", "text/plain")
            .timeout(Duration.ofSeconds(20))
            .version(Version.HTTP_2)
            .expectContinue(true);
    verifyThat(request)
        .hasUri("https://example.com")
        .isPUT()
        .hasBodyPublisher(publisher)
        .containsHeadersExactly("Content-Type", "text/plain")
        .hasTimeout(Duration.ofSeconds(20))
        .hasVersion(Version.HTTP_2)
        .hasExpectContinue(true);
  }

  @Test
  void setBasicFieldsBeforeImmutableCopy() {
    var publisher = BodyPublishers.ofString("XYZ");
    var request =
        MutableRequest.create()
            .uri("https://example.com")
            .method("PUT", publisher)
            .header("Content-Type", "text/plain")
            .timeout(Duration.ofSeconds(20))
            .version(Version.HTTP_2)
            .expectContinue(true)
            .toImmutableRequest();
    verifyThat(request)
        .hasUri("https://example.com")
        .isPUT()
        .hasBodyPublisher(publisher)
        .containsHeadersExactly("Content-Type", "text/plain")
        .hasTimeout(Duration.ofSeconds(20))
        .hasVersion(Version.HTTP_2)
        .hasExpectContinue(true);
  }

  @Test
  void staticFactories() {
    var uriString = "https://example.com";
    var uri = URI.create(uriString);

    verifyThat(MutableRequest.create(uriString)).hasUri(uri).isGET().hasNoBody();
    verifyThat(MutableRequest.create(uri)).hasUri(uri).isGET().hasNoBody();

    verifyThat(MutableRequest.GET(uriString)).hasUri(uri).isGET().hasNoBody();
    verifyThat(MutableRequest.GET(uri)).hasUri(uri).isGET().hasNoBody();

    verifyThat(MutableRequest.HEAD(uriString)).hasUri(uri).isHEAD().hasNoBody();
    verifyThat(MutableRequest.HEAD(uri)).hasUri(uri).isHEAD().hasNoBody();

    verifyThat(MutableRequest.DELETE(uriString)).hasUri(uri).isDELETE().hasNoBody();
    verifyThat(MutableRequest.DELETE(uri)).hasUri(uri).isDELETE().hasNoBody();

    var publisher = BodyPublishers.ofString("something");
    verifyThat(MutableRequest.POST(uriString, publisher))
        .hasUri(uri)
        .isPOST()
        .hasBodyPublisher(publisher);
    verifyThat(MutableRequest.POST(uri, publisher))
        .hasUri(uri)
        .isPOST()
        .hasBodyPublisher(publisher);

    verifyThat(MutableRequest.PUT(uriString, publisher))
        .hasUri(uri)
        .isPUT()
        .hasBodyPublisher(publisher);
    verifyThat(MutableRequest.PUT(uri, publisher)) //
        .hasUri(uri)
        .isPUT()
        .hasBodyPublisher(publisher);

    verifyThat(MutableRequest.PATCH(uriString, publisher))
        .hasUri(uri)
        .isPATCH()
        .hasBodyPublisher(publisher);
    verifyThat(MutableRequest.PATCH(uri, publisher))
        .hasUri(uri)
        .isPATCH()
        .hasBodyPublisher(publisher);
  }

  @Test
  @Timeout(TestUtils.SLOW_TIMEOUT_SECONDS) // Mockito seems to take some time to load.
  void staticFactoriesWithPayload() {
    var payload = new Object();
    var typeRef = TypeRef.of(Object.class);
    var publisher = BodyPublishers.ofString("abc");
    var encoder =
        AdapterMocker.mockEncoder(
            payload, TypeRef.of(Object.class), Hints.of(MediaType.TEXT_PLAIN), publisher);

    var uriString = "https://example.com";
    var uri = URI.create(uriString);
    var adapterCodec = AdapterCodec.newBuilder().encoder(encoder).build();
    verifyThat(
            MutableRequest.POST(uriString, payload, MediaType.TEXT_PLAIN)
                .adapterCodec(adapterCodec))
        .hasUri(uri)
        .isPOST()
        .hasBodyPublisher(publisher);
    verifyThat(MutableRequest.POST(uri, payload, MediaType.TEXT_PLAIN).adapterCodec(adapterCodec))
        .hasUri(uri)
        .isPOST()
        .hasBodyPublisher(publisher);
    verifyThat(
            MutableRequest.POST(uriString, payload, typeRef, MediaType.TEXT_PLAIN)
                .adapterCodec(adapterCodec))
        .hasUri(uri)
        .isPOST()
        .hasBodyPublisher(publisher);
    verifyThat(
            MutableRequest.POST(uri, payload, typeRef, MediaType.TEXT_PLAIN)
                .adapterCodec(adapterCodec))
        .hasUri(uri)
        .isPOST()
        .hasBodyPublisher(publisher);

    verifyThat(
            MutableRequest.PUT(uriString, payload, MediaType.TEXT_PLAIN).adapterCodec(adapterCodec))
        .hasUri(uri)
        .isPUT()
        .hasBodyPublisher(publisher);
    verifyThat(MutableRequest.PUT(uri, payload, MediaType.TEXT_PLAIN).adapterCodec(adapterCodec))
        .hasUri(uri)
        .isPUT()
        .hasBodyPublisher(publisher);
    verifyThat(
            MutableRequest.PUT(uriString, payload, typeRef, MediaType.TEXT_PLAIN)
                .adapterCodec(adapterCodec))
        .hasUri(uri)
        .isPUT()
        .hasBodyPublisher(publisher);
    verifyThat(
            MutableRequest.PUT(uri, payload, typeRef, MediaType.TEXT_PLAIN)
                .adapterCodec(adapterCodec))
        .hasUri(uri)
        .isPUT()
        .hasBodyPublisher(publisher);

    verifyThat(
            MutableRequest.PATCH(uriString, payload, MediaType.TEXT_PLAIN)
                .adapterCodec(adapterCodec))
        .hasUri(uri)
        .isPATCH()
        .hasBodyPublisher(publisher);
    verifyThat(MutableRequest.PATCH(uri, payload, MediaType.TEXT_PLAIN).adapterCodec(adapterCodec))
        .hasUri(uri)
        .isPATCH()
        .hasBodyPublisher(publisher);
    verifyThat(
            MutableRequest.PATCH(uriString, payload, typeRef, MediaType.TEXT_PLAIN)
                .adapterCodec(adapterCodec))
        .hasUri(uri)
        .isPATCH()
        .hasBodyPublisher(publisher);
    verifyThat(
            MutableRequest.PATCH(uri, payload, typeRef, MediaType.TEXT_PLAIN)
                .adapterCodec(adapterCodec))
        .hasUri(uri)
        .isPATCH()
        .hasBodyPublisher(publisher);
  }

  @Test
  void encoderReceivesRequestAndEncoderHints() {
    final class HintRecordingEncoder extends AbstractBodyAdapter
        implements AbstractBodyAdapter.BaseEncoder {
      Hints lastCallHints = Hints.empty();

      HintRecordingEncoder() {
        super(MediaType.ANY);
      }

      @Override
      public boolean supportsType(TypeRef<?> typeRef) {
        return typeRef.rawType() == Object.class;
      }

      @Override
      public <T> HttpRequest.BodyPublisher toBody(T value, TypeRef<T> typeRef, Hints hints) {
        requireSupport(typeRef, hints);
        lastCallHints = hints;
        return BodyPublishers.ofString("a");
      }
    }

    var encoder = new HintRecordingEncoder();
    var request =
        MutableRequest.POST("https://example.com", new Object(), MediaType.of("text", "very-plain"))
            .adapterCodec(AdapterCodec.newBuilder().encoder(encoder).build())
            .hint(Integer.class, 1)
            .hints(builder -> builder.put(String.class, "a"));
    assertThat(request.bodyPublisher()).isPresent(); // Resolve.
    assertThat(encoder.lastCallHints)
        .isEqualTo(
            Hints.newBuilder()
                .put(Integer.class, 1)
                .put(String.class, "a")
                .put(MediaType.class, MediaType.of("text", "very-plain"))
                .put(HttpRequest.class, request)
                .build());
  }

  @Test
  void addHeaders() {
    var request =
        MutableRequest.create().header("Content-Length", "1").header("Accept-Encoding", "gzip");
    verifyThat(request)
        .containsHeadersExactly(
            "Content-Length", "1",
            "Accept-Encoding", "gzip");

    request.headers("Content-Type", "text/plain", "Accept-Language", "fr-FR");
    verifyThat(request)
        .containsHeadersExactly(
            "Content-Length", "1",
            "Accept-Encoding", "gzip",
            "Content-Type", "text/plain",
            "Accept-Language", "fr-FR");
  }

  @Test
  void removeHeader() {
    var request =
        MutableRequest.create()
            .header("Content-Length", "1")
            .header("Accept-Encoding", "gzip")
            .removeHeader("Content-Length");
    verifyThat(request).containsHeadersExactly("Accept-Encoding", "gzip");
  }

  @Test
  void removeHeaders() {
    var request = MutableRequest.create().header("Content-Length", "1").removeHeaders();
    verifyThat(request).hasEmptyHeaders();
  }

  @Test
  void setHeader() {
    var request = MutableRequest.create().header("Accept-Encoding", "gzip");
    verifyThat(request).containsHeadersExactly("Accept-Encoding", "gzip");

    request.setHeader("Accept-Encoding", "deflate");
    verifyThat(request).containsHeadersExactly("Accept-Encoding", "deflate");
  }

  @Test
  void mutateHeaders() {
    var request =
        MutableRequest.create().header("Content-Length", "1").header("Accept-Encoding", "gzip");
    verifyThat(request)
        .containsHeadersExactly(
            "Content-Length", "1",
            "Accept-Encoding", "gzip");

    request.removeHeader("Content-Length");
    verifyThat(request).containsHeadersExactly("Accept-Encoding", "gzip");

    request.setHeader("Accept-Encoding", "deflate");
    verifyThat(request).containsHeadersExactly("Accept-Encoding", "deflate");

    request.setHeader("Content-Length", "2");
    verifyThat(request)
        .containsHeadersExactly(
            "Accept-Encoding", "deflate",
            "Content-Length", "2");

    request.headers(
        "Content-Type", "text/plain",
        "Accept-Language", "fr-FR");
    verifyThat(request)
        .containsHeadersExactly(
            "Accept-Encoding", "deflate",
            "Content-Length", "2",
            "Content-Type", "text/plain",
            "Accept-Language", "fr-FR");

    request.removeHeaders();
    verifyThat(request).hasEmptyHeaders();
  }

  @Test
  void addPrebuiltHttpHeaders() {
    var headers =
        headers(
            "Accept", "text/html",
            "Cookie", "sessionid=123",
            "Cookie", "password=321");
    var request = MutableRequest.create().header("X-My-Header", "oyy").headers(headers);
    verifyThat(request)
        .containsHeadersExactly(
            "X-My-Header", "oyy",
            "Accept", "text/html",
            "Cookie", "sessionid=123",
            "Cookie", "password=321");

    request.header("Accept-Encoding", "gzip");
    verifyThat(request)
        .containsHeadersExactly(
            "X-My-Header", "oyy",
            "Accept", "text/html",
            "Cookie", "sessionid=123",
            "Cookie", "password=321",
            "Accept-Encoding", "gzip");
  }

  @Test
  void mutableCopyOfBasicFields() {
    var request =
        MutableRequest.create()
            .POST(BodyPublishers.ofString("something"))
            .headers(
                "Content-Length", "1",
                "Accept-Encoding", "gzip")
            .timeout(Duration.ofSeconds(20))
            .version(Version.HTTP_1_1)
            .expectContinue(true);
    verifyThat(request.copy()).isDeeplyEqualTo(request);
    verifyThat(MutableRequest.copyOf(request)).isDeeplyEqualTo(request);
    verifyThat(MutableRequest.copyOf(request.toImmutableRequest())).isDeeplyEqualTo(request);
  }

  @Test
  void immutableCopyOfBasicFields() {
    var request =
        MutableRequest.create()
            .POST(BodyPublishers.ofString("something"))
            .headers(
                "Content-Length", "1",
                "Accept-Encoding", "gzip")
            .timeout(Duration.ofSeconds(20))
            .version(Version.HTTP_1_1)
            .expectContinue(true);
    verifyThat(request.toImmutableRequest()).isDeeplyEqualTo(request);
    verifyThat(request.copy().toImmutableRequest()).isDeeplyEqualTo(request);
    verifyThat(MutableRequest.copyOf(request).toImmutableRequest()).isDeeplyEqualTo(request);
    verifyThat(MutableRequest.copyOf(request.toImmutableRequest()).toImmutableRequest())
        .isDeeplyEqualTo(request);
  }

  @Test
  void changeHeadersAfterCopy() {
    var request = MutableRequest.create().header("Content-Length", "1");
    var requestCopy = request.copy().header("Accept-Encoding", "gzip");
    verifyThat(request).containsHeadersExactly("Content-Length", "1");
    verifyThat(requestCopy)
        .containsHeadersExactly(
            "Content-Length", "1",
            "Accept-Encoding", "gzip");
  }

  @Test
  void defaultValues() {
    verifyThat(MutableRequest.create())
        .isGET()
        .hasUri("")
        .hasEmptyHeaders()
        .hasNoBody()
        .hasNoTimeout()
        .hasNoVersion()
        .hasExpectContinue(false);
  }

  @Test
  void applyConsumer() {
    var request = MutableRequest.create().apply(r -> r.uri("https://example.com"));
    verifyThat(request).hasUri("https://example.com");
  }

  @Test
  void testToString() {
    assertThat(MutableRequest.GET("https://example.com").header("Accept-Encoding", "gzip"))
        .hasToString("https://example.com GET")
        .extracting(MutableRequest::toImmutableRequest)
        .hasToString("https://example.com GET");
  }

  @Test
  void setAdapterCodec() {
    var adapterCodec = AdapterCodec.newBuilder().build();
    assertThat(MutableRequest.create().adapterCodec(adapterCodec).adapterCodec())
        .hasValue(adapterCodec);
  }

  @Test
  void httpMethodSetters() {
    var request = MutableRequest.create();
    var publisher = BodyPublishers.ofString("something");

    verifyThat(request.POST(publisher)).isPOST().hasBodyPublisher(publisher);

    verifyThat(request.GET()).isGET().hasNoBody();

    verifyThat(request.HEAD()).isHEAD().hasNoBody();

    verifyThat(request.PUT(publisher)).isPUT().hasBodyPublisher(publisher);

    verifyThat(request.PATCH(publisher)).isPATCH().hasBodyPublisher(publisher);

    verifyThat(request.DELETE()).isDELETE().hasNoBody();
  }

  @Test
  @Timeout(TestUtils.SLOW_TIMEOUT_SECONDS) // Mockito seems to take some time to load.
  void httpMethodSettersWithPayload() {
    var payload = new Object();
    var publisher = BodyPublishers.ofString("abc");
    var encoder =
        AdapterMocker.mockEncoder(
            payload, TypeRef.of(Object.class), Hints.of(MediaType.TEXT_PLAIN), publisher);
    var request =
        MutableRequest.create().adapterCodec(AdapterCodec.newBuilder().encoder(encoder).build());

    verifyThat(request.POST(payload, MediaType.TEXT_PLAIN)).isPOST().hasBodyPublisher(publisher);

    verifyThat(request.PUT(payload, MediaType.TEXT_PLAIN)).isPUT().hasBodyPublisher(publisher);

    verifyThat(request.PATCH(payload, MediaType.TEXT_PLAIN)).isPATCH().hasBodyPublisher(publisher);

    verifyThat(request.GET()).isGET().hasNoBody();

    verifyThat(request.POST(payload, MediaType.TEXT_PLAIN).HEAD()).isHEAD().hasNoBody();
  }

  @Test
  void removeHeadersIf() {
    var request =
        MutableRequest.create()
            .headers(
                "X-My-First-Header", "val1",
                "X-My-First-Header", "val2",
                "X-My-Second-Header", "val1",
                "X-My-Second-Header", "val2");

    request.removeHeadersIf((name, __) -> "X-My-First-Header".equals(name));
    verifyThat(request)
        .containsHeadersExactly(
            "X-My-Second-Header", "val1",
            "X-My-Second-Header", "val2");

    request.removeHeadersIf(
        (name, value) -> "X-My-Second-Header".equals(name) && "val1".equals(value));
    verifyThat(request).containsHeadersExactly("X-My-Second-Header", "val2");

    request.removeHeadersIf((__, ___) -> true);
    verifyThat(request).hasEmptyHeaders();
  }

  @Test
  void addTags() {
    var request =
        MutableRequest.create().tag(Integer.class, 1).tag(new TypeRef<>() {}, List.of("a", "b"));
    verifyThat(request)
        .containsTag(Integer.class, 1)
        .containsTag(new TypeRef<>() {}, List.of("a", "b"))
        .doesNotContainTag(String.class);
    assertThat(request.tags())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                TypeRef.of(Integer.class), 1, new TypeRef<List<String>>() {}, List.of("a", "b")));

    var immutableRequest = request.toImmutableRequest();
    verifyThat(immutableRequest)
        .containsTag(Integer.class, 1)
        .containsTag(new TypeRef<>() {}, List.of("a", "b"))
        .doesNotContainTag(String.class);
    assertThat(immutableRequest.tags())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                TypeRef.of(Integer.class), 1, new TypeRef<List<String>>() {}, List.of("a", "b")));
  }

  @Test
  void copyAfterAddingTags() {
    var request = MutableRequest.create().tag(Integer.class, 1);
    verifyThat(request.copy()).containsTag(Integer.class, 1);
    verifyThat(MutableRequest.copyOf(request)).containsTag(Integer.class, 1);
    verifyThat(MutableRequest.copyOf(request.toImmutableRequest())).containsTag(Integer.class, 1);
  }

  @Test
  void removeTag() {
    var request = MutableRequest.create().tag(1);
    verifyThat(request).containsTag(Integer.class, 1);

    request.removeTag(Integer.class);
    verifyThat(request).doesNotContainTag(Integer.class);
  }

  @Test
  void getEmptyHints() {
    assertThat(MutableRequest.create().hints()).isEqualTo(Hints.empty());
  }

  @Test
  void addHints() {
    assertThat(
            MutableRequest.create()
                .hint(Integer.class, 1)
                .hint(MediaType.class, MediaType.TEXT_PLAIN)
                .hints())
        .isEqualTo(
            Hints.newBuilder()
                .put(Integer.class, 1)
                .put(MediaType.class, MediaType.TEXT_PLAIN)
                .build());
  }

  @Test
  void addHintsWithBuilderConsumer() {
    assertThat(
            MutableRequest.create()
                .hints(
                    builder ->
                        builder.put(Integer.class, 1).put(MediaType.class, MediaType.TEXT_PLAIN))
                .hints())
        .isEqualTo(
            Hints.newBuilder()
                .put(Integer.class, 1)
                .put(MediaType.class, MediaType.TEXT_PLAIN)
                .build());
  }

  @Test
  void hintsPersist() {
    assertThat(MutableRequest.create().hint(Integer.class, 1).hints())
        .isEqualTo(Hints.newBuilder().put(Integer.class, 1).build());
  }

  @Test
  void hintsPersistThroughMutableCopy() {
    assertThat(MutableRequest.create().hint(Integer.class, 1).copy().hints())
        .isEqualTo(Hints.newBuilder().put(Integer.class, 1).build());
  }

  @Test
  void hintsPersistThroughImmutableCopy() {
    assertThat(MutableRequest.create().hint(Integer.class, 1).toImmutableRequest().hints())
        .isEqualTo(Hints.newBuilder().put(Integer.class, 1).build());
  }

  @Test
  void hintsPersistThroughMutableCopyOfImmutableCopy() {
    assertThat(
            MutableRequest.copyOf(
                    MutableRequest.create().hint(Integer.class, 1).toImmutableRequest())
                .hints())
        .isEqualTo(Hints.newBuilder().put(Integer.class, 1).build());
  }

  @Test
  void setCacheControl() {
    var request =
        MutableRequest.GET("https://example.com")
            .cacheControl(CacheControl.newBuilder().maxAge(Duration.ofSeconds(1)).build());
    verifyThat(request).containsHeadersExactly("Cache-Control", "max-age=1");
  }

  @Test
  void setCacheControlAfterSettingHeader() {
    var request =
        MutableRequest.GET("https://example.com")
            .header("Cache-Control", "max-age=1")
            .cacheControl(CacheControl.newBuilder().maxAge(Duration.ofSeconds(2)).build());
    verifyThat(request).containsHeadersExactly("Cache-Control", "max-age=2");
  }

  @Test
  @Timeout(TestUtils.SLOW_TIMEOUT_SECONDS) // Mockito seems to take some time to load.
  void bodyPublisherIsCreatedOnce() {
    var payload = new Object();
    var encoder =
        AdapterMocker.mockEncoder(
            payload,
            TypeRef.of(Object.class),
            Hints.of(MediaType.TEXT_PLAIN),
            () -> BodyPublishers.ofString("xyz")); // Return a new publisher each call.

    var request =
        MutableRequest.POST("https://example.com", payload, MediaType.TEXT_PLAIN)
            .adapterCodec(AdapterCodec.newBuilder().encoder(encoder).build());
    var firstPublisher = request.bodyPublisher().orElseThrow();
    verifyThat(request).hasBodyPublisher(firstPublisher);
    verifyThat(request.copy()).hasBodyPublisher(firstPublisher);
    verifyThat(request.toImmutableRequest()).hasBodyPublisher(firstPublisher);
    verifyThat(MutableRequest.copyOf(request.toImmutableRequest()))
        .hasBodyPublisher(firstPublisher);
  }

  @Test
  @Timeout(TestUtils.SLOW_TIMEOUT_SECONDS) // Mockito seems to take some time to load.
  void copyingPreservesPayload() {
    var payload = new Object();
    var publisher = BodyPublishers.ofString("abc");
    var encoder =
        AdapterMocker.mockEncoder(
            payload, TypeRef.of(Object.class), Hints.of(MediaType.TEXT_PLAIN), publisher);
    var adapterCodec = AdapterCodec.newBuilder().encoder(encoder).build();

    verifyThat(
            MutableRequest.POST("https://example.com", payload, MediaType.TEXT_PLAIN)
                .adapterCodec(adapterCodec)
                .copy())
        .hasBodyPublisher(publisher);

    verifyThat(
            MutableRequest.POST("https://example.com", payload, MediaType.TEXT_PLAIN)
                .adapterCodec(adapterCodec)
                .toImmutableRequest())
        .hasBodyPublisher(publisher);

    verifyThat(
            MutableRequest.copyOf(
                MutableRequest.POST("https://example.com", payload, MediaType.TEXT_PLAIN)
                    .adapterCodec(adapterCodec)
                    .toImmutableRequest()))
        .hasBodyPublisher(publisher);

    verifyThat(
            MutableRequest.copyOf(
                    MutableRequest.POST("https://example.com", payload, MediaType.TEXT_PLAIN)
                        .adapterCodec(adapterCodec)
                        .toImmutableRequest())
                .toImmutableRequest())
        .hasBodyPublisher(publisher);
  }

  @Test
  void copyingPreservesMethodWithoutPayload() {
    verifyThat(
            MutableRequest.copyOf(MutableRequest.DELETE("https://example.com").toImmutableRequest())
                .toImmutableRequest())
        .isDELETE();
  }

  @Test
  void setBodyPublisherAsPayload() {
    var publisher = BodyPublishers.ofString("abc");
    assertThat(
            MutableRequest.POST("https://example.com", publisher, MediaType.TEXT_PLAIN)
                .bodyPublisher())
        .hasValueSatisfying(
            requestPublisher ->
                assertThat(requestPublisher)
                    .asInstanceOf(InstanceOfAssertFactories.type(MimeBodyPublisher.class))
                    .extracting(MimeBodyPublisher::mediaType)
                    .isEqualTo(MediaType.TEXT_PLAIN));
  }

  @Test
  @Timeout(TestUtils.SLOW_TIMEOUT_SECONDS) // Mockito seems to take some time to load.
  void changeAdapterCodecAfterResolvingPayload() {
    var payload = new Object();
    var firstPublisher = BodyPublishers.ofString("abc");
    var firstEncoder =
        AdapterMocker.mockEncoder(
            payload, TypeRef.of(Object.class), Hints.of(MediaType.TEXT_PLAIN), firstPublisher);
    var request = MutableRequest.POST("https://example.com", payload, MediaType.TEXT_PLAIN);
    verifyThat(request.adapterCodec(AdapterCodec.newBuilder().encoder(firstEncoder).build()))
        .hasBodyPublisher(firstPublisher);

    var secondPublisher = BodyPublishers.ofString("xyz");
    var secondEncoder =
        AdapterMocker.mockEncoder(
            payload, TypeRef.of(Object.class), Hints.of(MediaType.TEXT_PLAIN), secondPublisher);
    verifyThat(request.adapterCodec(AdapterCodec.newBuilder().encoder(secondEncoder).build()))
        .hasBodyPublisher(secondPublisher);
  }

  @Test
  void headersWithInvalidNumberOfArguments() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MutableRequest.create().headers(new String[0]));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MutableRequest.create().headers("Content-Length", "1", "Orphan"));
  }

  @Test
  void illegalHeaders() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MutableRequest.create().header("ba\r", "foo"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MutableRequest.create().headers("Name", "…"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MutableRequest.create().headers(headers("ba\r..", "foo")));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MutableRequest.create().headers(headers("Name", "…")));
  }

  @Test
  void illegalTimeout() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MutableRequest.create().timeout(Duration.ofSeconds(0)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MutableRequest.create().timeout(Duration.ofSeconds(-1)));
  }

  @Test
  void illegalMethodName() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MutableRequest.create().method("ba\r", BodyPublishers.noBody()));
  }
}
