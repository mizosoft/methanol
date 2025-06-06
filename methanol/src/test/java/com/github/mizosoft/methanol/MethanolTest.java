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

import static com.github.mizosoft.methanol.MutableRequest.GET;
import static com.github.mizosoft.methanol.MutableRequest.POST;
import static com.github.mizosoft.methanol.testing.TestUtils.headers;
import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.from;

import com.github.mizosoft.methanol.BodyAdapter.Hints;
import com.github.mizosoft.methanol.Methanol.Interceptor;
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter;
import com.github.mizosoft.methanol.internal.adapter.BodyDiscardTimeoutHint;
import com.github.mizosoft.methanol.internal.extensions.HeadersBuilder;
import com.github.mizosoft.methanol.testing.HttpClientStub;
import com.github.mizosoft.methanol.testing.HttpResponseStub;
import com.github.mizosoft.methanol.testing.ImmutableResponseInfo;
import com.github.mizosoft.methanol.testing.MockClock;
import com.github.mizosoft.methanol.testing.MockDelayer;
import com.github.mizosoft.methanol.testing.RecordingHttpClient;
import com.github.mizosoft.methanol.testing.RecordingHttpClient.Call;
import com.github.mizosoft.methanol.testing.TestSubscription;
import com.github.mizosoft.methanol.testing.TestUtils;
import java.net.Authenticator;
import java.net.CookieManager;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@SuppressWarnings("resource")
class MethanolTest {
  @Test
  void defaultFields() {
    var client = Methanol.create();
    assertThat(client.baseUri()).isEmpty();
    assertThat(client.userAgent()).isEmpty();
    assertThat(client.requestTimeout()).isEmpty();
    assertThat(client.headersTimeout()).isEmpty();
    assertThat(client.readTimeout()).isEmpty();
    assertThat(client.cache()).isEmpty();
    assertThat(client.interceptors()).isEmpty();
    assertThat(client.backendInterceptors()).isEmpty();
    assertThat(client.defaultHeaders().map()).isEmpty();
    assertThat(client.autoAcceptEncoding()).isTrue();
    assertThat(client.cache()).isEmpty();
    assertThat(client.caches()).isEmpty();
    assertThat(client.adapterCodec()).isEmpty();
  }

  @Test
  void settingBasicExtensionFields() {
    var interceptor = Interceptor.create(UnaryOperator.identity());
    var backendInterceptor = Interceptor.create(UnaryOperator.identity());
    var adapterCodec = AdapterCodec.newBuilder().build();
    var client =
        Methanol.newBuilder()
            .userAgent("Will Smith")
            .baseUri("https://example.com")
            .requestTimeout(Duration.ofSeconds(1))
            .readTimeout(Duration.ofSeconds(2))
            .headersTimeout(Duration.ofSeconds(3))
            .defaultHeader("Accept", "text/html")
            .autoAcceptEncoding(false)
            .interceptor(interceptor)
            .backendInterceptor(backendInterceptor)
            .adapterCodec(adapterCodec)
            .build();
    assertThat(client.userAgent()).hasValue("Will Smith");
    assertThat(client.baseUri()).hasValue(URI.create("https://example.com"));
    assertThat(client.requestTimeout()).hasValue(Duration.ofSeconds(1));
    assertThat(client.readTimeout()).hasValue(Duration.ofSeconds(2));
    assertThat(client.headersTimeout()).hasValue(Duration.ofSeconds(3));
    assertThat(client.defaultHeaders())
        .isEqualTo(
            headers(
                "Accept", "text/html",
                "User-Agent", "Will Smith")); // The user agent is treated as a default header
    assertThat(client.autoAcceptEncoding()).isFalse();
    assertThat(client.interceptors()).containsExactly(interceptor);
    assertThat(client.backendInterceptors()).containsExactly(backendInterceptor);
    assertThat(client.adapterCodec()).hasValue(adapterCodec);
  }

  @Test
  void settingBackendFields() {
    var cookieHandler = new CookieManager();
    var connectTimeout = Duration.ofSeconds(1);
    var sslContext = TestUtils.localhostSslContext();
    Executor executor =
        r -> {
          throw new RejectedExecutionException();
        };
    var redirect = Redirect.ALWAYS;
    var version = Version.HTTP_1_1;
    var proxy = ProxySelector.of(InetSocketAddress.createUnresolved("localhost", 80));
    var authenticator = new Authenticator() {};
    var client =
        Methanol.newBuilder()
            .cookieHandler(cookieHandler)
            .connectTimeout(connectTimeout)
            .sslContext(sslContext)
            .executor(executor)
            .followRedirects(redirect)
            .version(version)
            .proxy(proxy)
            .authenticator(authenticator)
            .build();
    assertThat(client.cookieHandler()).hasValue(cookieHandler);
    assertThat(client.connectTimeout()).hasValue(connectTimeout);
    assertThat(client.sslContext()).isSameAs(sslContext);
    assertThat(client.executor()).hasValue(executor);
    assertThat(client.followRedirects()).isEqualTo(redirect);
    assertThat(client.version()).isEqualTo(version);
    assertThat(client.proxy()).hasValue(proxy);
    assertThat(client.authenticator()).hasValue(authenticator);
  }

  @Test
  void buildWithPrebuiltBackend() {
    var backend =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager())
            .connectTimeout(Duration.ofSeconds(1))
            .sslContext(TestUtils.localhostSslContext())
            .executor(
                r -> {
                  throw new RejectedExecutionException();
                })
            .followRedirects(Redirect.ALWAYS)
            .version(Version.HTTP_1_1)
            .proxy(ProxySelector.of(InetSocketAddress.createUnresolved("localhost", 80)))
            .authenticator(new Authenticator() {})
            .build();
    var client = Methanol.newBuilder(backend).build();
    assertThat(client.underlyingClient()).isSameAs(backend);
    assertThat(client)
        .returns(backend.cookieHandler(), from(HttpClient::cookieHandler))
        .returns(backend.connectTimeout(), from(HttpClient::connectTimeout))
        .returns(backend.sslContext(), from(HttpClient::sslContext))
        .returns(backend.executor(), from(HttpClient::executor))
        .returns(backend.followRedirects(), from(HttpClient::followRedirects))
        .returns(backend.version(), from(HttpClient::version))
        .returns(backend.proxy(), from(HttpClient::proxy))
        .returns(backend.authenticator(), from(HttpClient::authenticator));
  }

  @Test
  void applyConsumer() {
    var client = Methanol.newBuilder().apply(b -> b.defaultHeader("Accept", "text/html")).build();
    assertThat(client.defaultHeaders()).isEqualTo(headers("Accept", "text/html"));
  }

  @Test
  void addUserAgentAsDefaultHeader() {
    var client = Methanol.newBuilder().defaultHeader("User-Agent", "Will Smith").build();
    assertThat(client.userAgent()).hasValue("Will Smith");
  }

  @Test
  void defaultHeadersAreAppliedToRequests() throws Exception {
    var backend = new RecordingClient();
    var client =
        Methanol.newBuilder(backend)
            .autoAcceptEncoding(false)
            .defaultHeaders(
                "Accept", "text/html",
                "Cookie", "password=123")
            .build();
    client.send(GET("https://example.com").header("X-Foo", "bar"), BodyHandlers.discarding());
    verifyThat(backend.request)
        .containsHeadersExactly(
            "Accept", "text/html",
            "Cookie", "password=123",
            "X-Foo", "bar");
  }

  @Test
  void userAgentIsAppliedToRequests() throws Exception {
    var backend = new RecordingClient();
    var client =
        Methanol.newBuilder(backend).autoAcceptEncoding(false).userAgent("Will Smith").build();
    client.send(GET("https://example.com"), BodyHandlers.discarding());
    verifyThat(backend.request).containsHeadersExactly("User-Agent", "Will Smith");
  }

  @Test
  void baseUriIsAppliedToRequests() throws Exception {
    var backend = new RecordingClient();
    var client = Methanol.newBuilder(backend).baseUri("https://example.com/").build();

    client.send(GET(""), BodyHandlers.discarding());
    verifyThat(backend.request).hasUri("https://example.com/");

    client.send(GET("/b?q=value"), BodyHandlers.discarding());
    verifyThat(backend.request).hasUri("https://example.com/b?q=value");

    client.send(GET("b?q=value"), BodyHandlers.discarding());
    verifyThat(backend.request).hasUri("https://example.com/b?q=value");

    client.send(GET("?q=value"), BodyHandlers.discarding());
    verifyThat(backend.request).hasUri("https://example.com/?q=value");
  }

  @Test
  void autoAcceptEncoding() throws Exception {
    var backend = new RecordingClient();
    var client = Methanol.newBuilder(backend).build();
    client.send(GET("https://example.com"), BodyHandlers.discarding());
    verifyThat(backend.request).containsHeadersExactly("Accept-Encoding", acceptEncodingValue());
  }

  @Test
  void requestWithMimeBodyPublisher() throws Exception {
    var backend = new RecordingClient();
    var client = Methanol.newBuilder(backend).build();
    var mimeBody =
        MoreBodyPublishers.ofMediaType(
            BodyPublishers.ofString("something"), MediaType.of("text", "plain"));
    client.send(POST("https://example.com", mimeBody), BodyHandlers.discarding());
    verifyThat(backend.request).containsHeader("Content-Type", "text/plain");
  }

  @Test
  void defaultRequestTimeoutIsApplied() throws Exception {
    var timeout = Duration.ofSeconds(1);
    var backend = new RecordingClient();
    var client = Methanol.newBuilder(backend).requestTimeout(timeout).build();
    client.send(GET("https://example.com"), BodyHandlers.discarding());
    verifyThat(backend.request).hasTimeout(Duration.ofSeconds(1));
  }

  @Test
  void requestPropertiesAreNotOverwrittenByDefaultOnes() throws Exception {
    var backend = new RecordingClient();
    var client =
        Methanol.newBuilder(backend)
            .userAgent("Will Smith")
            .defaultHeaders("Accept", "text/html")
            .requestTimeout(Duration.ofSeconds(1))
            .build();
    var request =
        GET("https://localhost")
            .headers(
                "Accept", "application/json",
                "User-Agent", "Dave Bautista",
                "Accept-Encoding", "gzip")
            .timeout(Duration.ofSeconds(2))
            .build();
    client.send(request, BodyHandlers.discarding());
    verifyThat(request)
        .containsHeadersExactly(
            "Accept", "application/json",
            "User-Agent", "Dave Bautista",
            "Accept-Encoding", "gzip")
        .hasTimeout(Duration.ofSeconds(2));
  }

  @Test
  void mutableRequestIsCopiedWhenSent() throws Exception {
    var backend = new RecordingClient();
    var client =
        Methanol.newBuilder(backend)
            .userAgent("Will Smith")
            .baseUri("https://example.com")
            .defaultHeader("Accept", "text/html")
            .build();
    var mutableRequest =
        POST("/a", BodyPublishers.ofString("something"))
            .header("Content-Type", "text/plain")
            .timeout(Duration.ofSeconds(1))
            .version(Version.HTTP_1_1)
            .expectContinue(true);
    var snapshot = mutableRequest.toImmutableRequest();
    client.send(mutableRequest, BodyHandlers.discarding());

    // Verify that original request properties are copied
    verifyThat(backend.request)
        .containsHeaders(snapshot.headers())
        .hasBodyPublisher(snapshot.bodyPublisher())
        .hasTimeout(snapshot.timeout())
        .hasVersion(snapshot.version())
        .hasExpectContinue(snapshot.expectContinue());

    // Request passed to the client isn't mutated
    verifyThat(mutableRequest).isDeeplyEqualTo(snapshot);
  }

  @Test
  void immutableRequestIsWhenSent() throws Exception {
    var backend = new RecordingClient();
    var client =
        Methanol.newBuilder(backend)
            .userAgent("Will Smith")
            .baseUri("https://example.com")
            .defaultHeader("Accept", "text/html")
            .build();
    var immutableRequest =
        POST("/a", BodyPublishers.ofString("something"))
            .header("Content-Type", "text/plain")
            .timeout(Duration.ofSeconds(1))
            .version(Version.HTTP_1_1)
            .expectContinue(true)
            .toImmutableRequest();
    client.send(immutableRequest, BodyHandlers.discarding());

    // Verify that original request properties are copied
    verifyThat(backend.request)
        .containsHeaders(immutableRequest.headers())
        .hasBodyPublisher(immutableRequest.bodyPublisher())
        .hasTimeout(immutableRequest.timeout())
        .hasVersion(immutableRequest.version())
        .hasExpectContinue(immutableRequest.expectContinue());
  }

  @Test
  void tagsArePassedOverSent() throws Exception {
    var backend = new RecordingClient();
    var client = Methanol.newBuilder(backend).build();
    var request = GET("https://example.com").tag(Integer.class, 1);
    client.send(request, BodyHandlers.discarding());
    verifyThat(backend.request).containsTag(Integer.class, 1);
  }

  @Test
  @Timeout(TestUtils.SLOW_TIMEOUT_SECONDS) // Mockito seems to take some time to load.
  void requestPayloadIsMappedToBodyPublisher() {
    var payload = new Object();
    var publisher = BodyPublishers.ofString("abc");
    var encoder =
        AdapterMocker.mockEncoder(
            payload, TypeRef.of(Object.class), Hints.of(MediaType.TEXT_PLAIN), publisher);
    var backend = new RecordingHttpClient();
    var client =
        Methanol.newBuilder(backend)
            .adapterCodec(AdapterCodec.newBuilder().encoder(encoder).build())
            .build();
    client.sendAsync(
        POST("https://example.com", payload, MediaType.TEXT_PLAIN), BodyHandlers.discarding());
    verifyThat(backend.lastCall().request()).hasBodyPublisher(publisher);
  }

  @Test
  void decoderReceivesRequestAndDecoderHints() throws Exception {
    final class HintRecordingDecoder extends AbstractBodyAdapter
        implements AbstractBodyAdapter.BaseDecoder {
      Hints lastCallHints = Hints.empty();

      HintRecordingDecoder() {
        super(MediaType.ANY);
      }

      @Override
      public boolean supportsType(TypeRef<?> typeRef) {
        return typeRef.type() == Object.class;
      }

      @SuppressWarnings("unchecked")
      @Override
      public <T> BodySubscriber<T> toObject(TypeRef<T> typeRef, Hints hints) {
        requireSupport(typeRef, hints);
        lastCallHints = hints;
        return (BodySubscriber<T>) BodySubscribers.discarding();
      }
    }

    var decoder = new HintRecordingDecoder();
    var request =
        GET("https://example.com")
            .hint(Integer.class, 1)
            .hints(builder -> builder.put(String.class, "a"));
    var backend = new RecordingHttpClient().handleCalls(Call::complete);
    var client =
        Methanol.newBuilder(backend)
            .adapterCodec(AdapterCodec.newBuilder().decoder(decoder).build())
            .build();
    var headersBuilder = new HeadersBuilder();
    headersBuilder.add("Content-Type", "text/plain");
    var responseInfo = new ImmutableResponseInfo(200, headersBuilder.build(), Version.HTTP_1_1);

    client.send(request, Object.class);
    backend.lastCall().bodyHandler().apply(responseInfo); // Trigger decoder call.
    assertThat(decoder.lastCallHints)
        .isEqualTo(
            Hints.newBuilder()
                .put(Integer.class, 1)
                .put(String.class, "a")
                .put(MediaType.class, MediaType.of("text", "plain"))
                .put(ResponseInfo.class, responseInfo)
                .build());

    // Send an immutable copy.
    client.send(request.toImmutableRequest(), Object.class);
    assertThat(backend.lastCall().bodyHandler()).isNotNull();
    backend.lastCall().bodyHandler().apply(responseInfo); // Trigger decoder call.
    assertThat(decoder.lastCallHints)
        .isEqualTo(
            Hints.newBuilder()
                .put(Integer.class, 1)
                .put(String.class, "a")
                .put(MediaType.class, MediaType.of("text", "plain"))
                .put(ResponseInfo.class, responseInfo)
                .build());
  }

  @Test
  void requestAdapterCodecOverridesThatOfClientForEncoding() throws Exception {
    final class ReplacingEncoder extends AbstractBodyAdapter
        implements AbstractBodyAdapter.BaseEncoder {
      private final String result;

      ReplacingEncoder(String result) {
        super(MediaType.ANY);
        this.result = result;
      }

      @Override
      public boolean supportsType(TypeRef<?> typeRef) {
        return typeRef.type() == Object.class;
      }

      @Override
      public <T> BodyPublisher toBody(T value, TypeRef<T> typeRef, Hints hints) {
        requireSupport(typeRef, hints);
        return BodyPublishers.ofString(result);
      }
    }

    var clientCodec = AdapterCodec.newBuilder().encoder(new ReplacingEncoder("OfClient")).build();
    var requestCodec = AdapterCodec.newBuilder().encoder(new ReplacingEncoder("OfRequest")).build();
    var backend = new RecordingHttpClient().handleCalls(Call::complete);
    var client = Methanol.newBuilder(backend).adapterCodec(clientCodec).build();

    client.send(
        POST("https://example.com", new Object(), MediaType.TEXT_PLAIN), BodyHandlers.discarding());
    verifyThat(backend.lastCall().request().bodyPublisher().orElseThrow()).succeedsWith("OfClient");

    client.send(
        POST("https://example.com", new Object(), MediaType.TEXT_PLAIN).adapterCodec(requestCodec),
        BodyHandlers.discarding());
    verifyThat(backend.lastCall().request().bodyPublisher().orElseThrow())
        .succeedsWith("OfRequest");

    client.send(
        POST("https://example.com", new Object(), MediaType.TEXT_PLAIN)
            .adapterCodec(requestCodec)
            .toImmutableRequest(),
        BodyHandlers.discarding());
    verifyThat(backend.lastCall().request().bodyPublisher().orElseThrow())
        .succeedsWith("OfRequest");
  }

  @Test
  void requestAdapterCodecOverridesThatOfClientForDecoding() throws Exception {
    final class ReplacingDecoder extends AbstractBodyAdapter
        implements AbstractBodyAdapter.BaseDecoder {
      private final String result;

      ReplacingDecoder(String result) {
        super(MediaType.ANY);
        this.result = result;
      }

      @Override
      public boolean supportsType(TypeRef<?> typeRef) {
        return typeRef.type() == String.class;
      }

      @SuppressWarnings("unchecked")
      @Override
      public <T> BodySubscriber<T> toObject(TypeRef<T> typeRef, Hints hints) {
        requireSupport(typeRef, hints);
        return (BodySubscriber<T>) BodySubscribers.replacing(result);
      }
    }

    var clientCodec = AdapterCodec.newBuilder().decoder(new ReplacingDecoder("OfClient")).build();
    var requestCodec = AdapterCodec.newBuilder().decoder(new ReplacingDecoder("OfRequest")).build();
    var client =
        Methanol.newBuilder(new RecordingHttpClient().handleCalls(Call::complete))
            .adapterCodec(clientCodec)
            .build();
    assertThat(client.send(GET("https://example.com"), String.class).body()).isEqualTo("OfClient");
    assertThat(
            client.send(GET("https://example.com").adapterCodec(requestCodec), String.class).body())
        .isEqualTo("OfRequest");
    assertThat(
            client
                .send(
                    GET("https://example.com").adapterCodec(requestCodec).toImmutableRequest(),
                    String.class)
                .body())
        .isEqualTo("OfRequest");
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void responsePayloadClosureWithHttp2() throws Exception {
    var subscriberRef = new AtomicReference<BodySubscriber<?>>();
    var backend =
        new RecordingHttpClient()
            .handleCalls(
                call -> {
                  var responseInfo = new ImmutableResponseInfo(HTTP_OK, headers(), Version.HTTP_2);
                  var subscriber = call.bodyHandler().apply(responseInfo);
                  subscriberRef.set(subscriber);
                  subscriber
                      .getBody()
                      .whenComplete(
                          (result, ex) -> {
                            if (result != null) {
                              call.complete((HttpResponse) call.okResponse(responseInfo, result));
                            }
                          });
                });
    var client =
        Methanol.newBuilder(backend)
            .adapterCodec(AdapterCodec.newBuilder().basic().build())
            .build();
    var responseFuture =
        client.sendAsync(MutableRequest.GET("https://examples.com"), ResponsePayload.class);
    var subscription = new TestSubscription();
    var subscriber = subscriberRef.get();
    assertThat(subscriber).isNotNull();
    subscriber.onSubscribe(subscription);
    responseFuture.get(TestUtils.TIMEOUT_SECONDS, TimeUnit.SECONDS).body().close();
    subscription.awaitCancellation();
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void responsePayloadClosureWithHttp1NoTimeout() throws Exception {
    var clock = new MockClock();
    var delayer = new MockDelayer(clock);
    var subscriberRef = new AtomicReference<BodySubscriber<?>>();
    var backend =
        new RecordingHttpClient()
            .handleCalls(
                call -> {
                  var responseInfo =
                      new ImmutableResponseInfo(HTTP_OK, headers(), Version.HTTP_1_1);
                  var subscriber = call.bodyHandler().apply(responseInfo);
                  subscriberRef.set(subscriber);
                  subscriber
                      .getBody()
                      .whenComplete(
                          (result, ex) -> {
                            if (result != null) {
                              call.complete((HttpResponse) call.okResponse(responseInfo, result));
                            }
                          });
                });
    var client =
        Methanol.newBuilder(backend)
            .adapterCodec(AdapterCodec.newBuilder().basic().build())
            .build();
    var responseFuture =
        client.sendAsync(
            MutableRequest.GET("https://examples.com")
                .hint(
                    BodyDiscardTimeoutHint.class,
                    new BodyDiscardTimeoutHint(
                        new com.github.mizosoft.methanol.internal.concurrent.Timeout(
                            Duration.ofSeconds(1), delayer))),
            ResponsePayload.class);
    var subscription = new TestSubscription();
    var subscriber = subscriberRef.get();
    assertThat(subscriber).isNotNull();
    subscriber.onSubscribe(subscription);
    responseFuture.get(TestUtils.TIMEOUT_SECONDS, TimeUnit.SECONDS).body().close();
    subscription.awaitRequest();
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void responsePayloadClosureWithHttp1OnTimeout() throws Exception {
    var clock = new MockClock();
    var delayer = new MockDelayer(clock);
    var subscriberRef = new AtomicReference<BodySubscriber<?>>();
    var backend =
        new RecordingHttpClient()
            .handleCalls(
                call -> {
                  var responseInfo =
                      new ImmutableResponseInfo(HTTP_OK, headers(), Version.HTTP_1_1);
                  var subscriber = call.bodyHandler().apply(responseInfo);
                  subscriberRef.set(subscriber);
                  subscriber
                      .getBody()
                      .whenComplete(
                          (result, ex) -> {
                            if (result != null) {
                              call.complete((HttpResponse) call.okResponse(responseInfo, result));
                            }
                          });
                });
    var client =
        Methanol.newBuilder(backend)
            .adapterCodec(AdapterCodec.newBuilder().basic().build())
            .build();
    var responseFuture =
        client.sendAsync(
            MutableRequest.GET("https://examples.com")
                .hint(
                    BodyDiscardTimeoutHint.class,
                    new BodyDiscardTimeoutHint(
                        new com.github.mizosoft.methanol.internal.concurrent.Timeout(
                            Duration.ofSeconds(1), delayer))),
            ResponsePayload.class);
    var subscription = new TestSubscription();
    var subscriber = subscriberRef.get();
    assertThat(subscriberRef).doesNotHaveNullValue();
    subscriber.onSubscribe(subscription);
    responseFuture.get(TestUtils.TIMEOUT_SECONDS, TimeUnit.SECONDS).body().close();
    subscriber.onNext(List.of(ByteBuffer.allocate(1)));
    clock.advanceSeconds(1); // Trigger timeout to cancel subscription.
    subscriber.onComplete();
    subscription.awaitCancellation();
  }

  @Test
  void responsePayloadMediaTypeMatching() throws Exception {
    var backend =
        new RecordingHttpClient()
            .handleCalls(
                call ->
                    call.complete(
                        new ImmutableResponseInfo(headers("Content-Type", "application/json")),
                        TestUtils.EMPTY_BUFFER));
    var client =
        Methanol.newBuilder(backend)
            .adapterCodec(AdapterCodec.newBuilder().basic().build())
            .build();
    var response = client.send(MutableRequest.GET("https://example.com"), ResponsePayload.class);
    var payload = response.body();
    assertThat(payload.is(MediaType.ANY)).isTrue();
    assertThat(payload.is(MediaType.APPLICATION_ANY)).isTrue();
    assertThat(payload.is(MediaType.APPLICATION_JSON)).isTrue();
    assertThat(payload.is(MediaType.APPLICATION_XML)).isFalse();
    assertThat(payload.isAnyOf(MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON)).isTrue();
    assertThat(payload.isAnyOf(MediaType.APPLICATION_XML, MediaType.APPLICATION_X_PROTOBUF))
        .isFalse();
  }

  @Test
  void createsWebSocketBuilders() {
    Methanol.create().newWebSocketBuilder();
  }

  @Test
  void illegalBaseUri() {
    var builder = Methanol.newBuilder();
    assertThatIllegalArgumentException()
        .isThrownBy(() -> builder.baseUri(new URI(null, "localhost", null, null))); // No scheme
    assertThatIllegalArgumentException()
        .isThrownBy(() -> builder.baseUri("ws://localhost")); // Not http or https
    assertThatIllegalArgumentException()
        .isThrownBy(() -> builder.baseUri(new URI("https", null, "/", ""))); // No host
  }

  /** URI is also checked after being resolved with base URI. */
  @Test
  void illegalResolvedUri() {
    var client = Methanol.newBuilder().build();
    var webSocketRequest = GET("ws://localhost");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> client.send(webSocketRequest, BodyHandlers.discarding()));
  }

  @Test
  void illegalUserAgent() {
    var builder = Methanol.newBuilder();
    assertThatIllegalArgumentException().isThrownBy(() -> builder.userAgent("ba\r"));
    assertThatIllegalArgumentException().isThrownBy(() -> builder.userAgent("…"));
  }

  @Test
  void illegalRequestTimeout() {
    var builder = Methanol.newBuilder();
    assertThatIllegalArgumentException()
        .isThrownBy(() -> builder.requestTimeout(Duration.ofSeconds(0)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> builder.requestTimeout(Duration.ofSeconds(-1)));
  }

  @Test
  void illegalDefaultHeaders() {
    var builder = Methanol.newBuilder();
    assertThatIllegalArgumentException().isThrownBy(() -> builder.defaultHeader("ba\r", "foo"));
    assertThatIllegalArgumentException().isThrownBy(() -> builder.defaultHeader("", "foo"));
    assertThatIllegalArgumentException().isThrownBy(() -> builder.defaultHeaders("name", "…"));
  }

  private static String acceptEncodingValue() {
    return String.join(", ", BodyDecoder.Factory.installedBindings().keySet());
  }

  static final class RecordingClient extends HttpClientStub {
    HttpRequest request;
    BodyHandler<?> handler;
    PushPromiseHandler<?> pushHandler;

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> responseBodyHandler) {
      this.request = request;
      this.handler = responseBodyHandler;
      this.pushHandler = null;
      return new HttpResponseStub<>();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        BodyHandler<T> responseBodyHandler,
        PushPromiseHandler<T> pushPromiseHandler) {
      this.request = request;
      this.handler = responseBodyHandler;
      this.pushHandler = pushPromiseHandler;
      return CompletableFuture.completedFuture(new HttpResponseStub<>());
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, BodyHandler<T> responseBodyHandler) {
      return sendAsync(request, responseBodyHandler, null);
    }

    // Override these methods to not crash when Methanol's constructor calls them.

    @Override
    public Redirect followRedirects() {
      return Redirect.NORMAL;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }
  }
}
