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

package com.github.mizosoft.methanol;

import static com.github.mizosoft.methanol.CacheAwareResponse.CacheStatus.HIT;
import static com.github.mizosoft.methanol.MutableRequest.GET;
import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static com.github.mizosoft.methanol.internal.cache.HttpDates.toHttpDateString;
import static com.github.mizosoft.methanol.testing.TestUtils.deflate;
import static com.github.mizosoft.methanol.testing.TestUtils.gzip;
import static com.github.mizosoft.methanol.testing.junit.ExecutorExtension.ExecutorType.FIXED_POOL;
import static com.github.mizosoft.methanol.testing.junit.StoreSpec.FileSystemType.SYSTEM;
import static com.github.mizosoft.methanol.testing.junit.StoreSpec.StoreType.DISK;
import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static java.time.ZoneOffset.UTC;
import static java.util.function.Predicate.isEqual;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

import com.github.mizosoft.methanol.HttpCache.Listener;
import com.github.mizosoft.methanol.HttpCache.Stats;
import com.github.mizosoft.methanol.HttpCache.StatsRecorder;
import com.github.mizosoft.methanol.HttpCacheTest.RecordingListener.EventType;
import com.github.mizosoft.methanol.HttpCacheTest.RecordingListener.OnNetworkUse;
import com.github.mizosoft.methanol.HttpCacheTest.RecordingListener.OnReadFailure;
import com.github.mizosoft.methanol.HttpCacheTest.RecordingListener.OnReadSuccess;
import com.github.mizosoft.methanol.HttpCacheTest.RecordingListener.OnRequest;
import com.github.mizosoft.methanol.HttpCacheTest.RecordingListener.OnResponse;
import com.github.mizosoft.methanol.HttpCacheTest.RecordingListener.OnWriteFailure;
import com.github.mizosoft.methanol.HttpCacheTest.RecordingListener.OnWriteSuccess;
import com.github.mizosoft.methanol.Methanol.Interceptor;
import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.CacheWritingPublisher;
import com.github.mizosoft.methanol.internal.cache.DiskStore;
import com.github.mizosoft.methanol.internal.cache.MemoryStore;
import com.github.mizosoft.methanol.internal.cache.Store;
import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.cache.Store.EntryReader;
import com.github.mizosoft.methanol.internal.cache.Store.EntryWriter;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.testing.Logging;
import com.github.mizosoft.methanol.testing.MockClock;
import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension.ExecutorConfig;
import com.github.mizosoft.methanol.testing.junit.MockWebServerExtension;
import com.github.mizosoft.methanol.testing.junit.MockWebServerExtension.UseHttps;
import com.github.mizosoft.methanol.testing.junit.StoreContext;
import com.github.mizosoft.methanol.testing.junit.StoreExtension;
import com.github.mizosoft.methanol.testing.junit.StoreExtension.StoreParameterizedTest;
import com.github.mizosoft.methanol.testing.junit.StoreSpec;
import com.github.mizosoft.methanol.testing.verifiers.ResponseVerifier;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Phaser;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.QueueDispatcher;
import mockwebserver3.RecordedRequest;
import mockwebserver3.SocketPolicy;
import okhttp3.Headers;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.api.ObjectAssert;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.ThrowingConsumer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(value = 10, unit = TimeUnit.MINUTES)
@ExtendWith({MockWebServerExtension.class, StoreExtension.class, ExecutorExtension.class})
class HttpCacheTest {
  static {
    Logging.disable(HttpCache.class, DiskStore.class, CacheWritingPublisher.class);
  }

  private Executor executor;
  private Methanol.Builder clientBuilder;
  private Methanol client;
  private MockWebServer server;
  private URI serverUri;
  private MockClock clock;
  private EditAwaiter editAwaiter;
  private HttpCache cache;
  private boolean failOnUnavailableResponses = true;

  @BeforeEach
  @ExecutorConfig(FIXED_POOL)
  void setUp(Executor executor, Methanol.Builder builder, MockWebServer server) {
    this.executor = executor;
    this.server = server;
    this.serverUri = server.url("/").uri();
    this.clock = new MockClock();
    this.clientBuilder =
        builder
            .executor(executor)
            .backendInterceptor(
                new Interceptor() {
                  @Override
                  public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
                      throws IOException, InterruptedException {
                    var response = chain.forward(request);
                    if (failOnUnavailableResponses) {
                      assertThat(response.statusCode()).isNotEqualTo(HTTP_UNAVAILABLE);
                    }
                    return response;
                  }

                  @Override
                  public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
                      HttpRequest request, Chain<T> chain) {
                    return chain
                        .forwardAsync(request)
                        .thenApply(
                            response -> {
                              if (failOnUnavailableResponses) {
                                assertThat(response.statusCode()).isNotEqualTo(HTTP_UNAVAILABLE);
                              }
                              return response;
                            });
                  }
                });

    ((QueueDispatcher) server.getDispatcher())
        .setFailFast(new MockResponse().setResponseCode(HTTP_UNAVAILABLE));
  }

  private void setUpCache(Store store) {
    setUpCache(store, null, null);
  }

  private void setUpCache(Store store, @Nullable StatsRecorder statsRecorder) {
    setUpCache(store, statsRecorder, null);
  }

  private void setUpCache(
      Store store, @Nullable StatsRecorder statsRecorder, @Nullable Listener listener) {
    editAwaiter = new EditAwaiter();

    var cacheBuilder =
        HttpCache.newBuilder()
            .clock(clock)
            .store(new EditAwaiterStore(store, editAwaiter))
            .executor(executor);
    if (statsRecorder != null) {
      cacheBuilder.statsRecorder(statsRecorder);
    }
    if (listener != null) {
      cacheBuilder.listener(listener);
    }
    cache = cacheBuilder.build();
    client = clientBuilder.cache(cache).build();
  }

  @AfterEach
  void tearDown() throws IOException {
    if (cache != null) {
      cache.close();
    }
  }

  @Test
  void buildWithMemoryStore() throws IOException {
    var cache = HttpCache.newBuilder().cacheOnMemory(12).build();
    var store = cache.store();
    assertThat(store).isInstanceOf(MemoryStore.class);
    assertThat(store.maxSize()).isEqualTo(12);
    assertThat(store.executor()).isEmpty();
    assertThat(cache.directory()).isEmpty();
    assertThat(cache.size()).isZero();
  }

  @Test
  void buildWithDiskStore(@TempDir Path dir) {
    var cache =
        HttpCache.newBuilder()
            .cacheOnDisk(dir, 12)
            .executor(
                r -> {
                  throw new RejectedExecutionException("NO!");
                })
            .build();
    var store = cache.store();
    assertThat(store).isInstanceOf(DiskStore.class);
    assertThat(store.maxSize()).isEqualTo(12);
    assertThat(cache.directory()).hasValue(dir);
    assertThat(cache.executor()).isEqualTo(store.executor());
  }

  @StoreParameterizedTest
  void cacheGetWithMaxAge(Store store) throws Exception {
    setUpCache(store);
    assertCachedGet(Duration.ofSeconds(1), "Cache-Control", "max-age=2");
  }

  @StoreParameterizedTest
  void cacheGetWithExpires(Store store) throws Exception {
    setUpCache(store);

    var now = toUtcDateTime(clock.instant());
    assertCachedGet(Duration.ofHours(12), "Expires", toHttpDateString(now.plusDays(1)));
  }

  @StoreParameterizedTest
  void cacheGetWithExpiresAndDate(Store store) throws Exception {
    setUpCache(store);

    var date = toUtcDateTime(clock.instant());
    assertCachedGet(
        Duration.ofDays(1), // Advance clock so freshness is == 0 (response is still servable)
        "Date",
        toHttpDateString(date),
        "Expires",
        toHttpDateString(date.plusDays(1)));
  }

  @StoreParameterizedTest
  @UseHttps
  void cacheSecureGetWithMaxAge(Store store) throws Exception {
    setUpCache(store);
    assertCachedGet(Duration.ofSeconds(1), "Cache-Control", "max-age=2").isCachedWithSsl();
  }

  private ResponseVerifier<String> assertCachedGet(Duration clockAdvance, String... headers)
      throws Exception {
    server.enqueue(new MockResponse().setHeaders(Headers.of(headers)).setBody("Pikachu"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Pikachu");

    clock.advance(clockAdvance);
    return verifyThat(get(serverUri)).isCacheHit().hasBody("Pikachu");
  }

  @StoreParameterizedTest
  void conditionalHitForGetWithExpires(Store store) throws Exception {
    setUpCache(store);

    // Expire one day from "now"
    var oneDayFromNow = clock.instant().plus(Duration.ofDays(1));
    server.enqueue(
        new MockResponse().setHeader("Expires", formatInstant(oneDayFromNow)).setBody("Pikachu"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Pikachu");

    // Make response stale
    clock.advance(Duration.ofDays(2));
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(get(serverUri)).isConditionalHit().hasBody("Pikachu");
  }

  @StoreParameterizedTest
  @UseHttps
  void conditionalHitForSecureGetWithExpires(Store store) throws Exception {
    setUpCache(store);

    // Expire one day from "now"
    var oneDayFromNow = clock.instant().plus(Duration.ofDays(1));
    server.enqueue(
        new MockResponse().setHeader("Expires", formatInstant(oneDayFromNow)).setBody("Pikachu"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Pikachu");

    // Make response stale
    clock.advance(Duration.ofDays(2));
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(get(serverUri)).isConditionalHit().hasBody("Pikachu").isCachedWithSsl();
  }

  @StoreParameterizedTest
  void responseIsFreshenedOnConditionalHit(Store store) throws Exception {
    setUpCache(store);

    // Warning 113 is stored (e.g. may come from a proxy's cache) but it's removed on freshening
    server.enqueue(
        new MockResponse()
            .setHeader("Warning", "113 - \"Heuristic Expiration\"")
            .setHeader("X-Version", "v1")
            .setHeader("Content-Type", "text/plain")
            .setHeader("Cache-Control", "max-age=1")
            .setBody("Jigglypuff"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Jigglypuff");

    // Make response stale
    clock.advanceSeconds(2);
    server.enqueue(
        new MockResponse().setResponseCode(HTTP_NOT_MODIFIED).setHeader("X-Version", "v2"));
    verifyThat(get(serverUri)).isConditionalHit().hasBody("Jigglypuff");

    var instantRevalidationSentAndReceived = clock.instant();
    verifyThat(awaitCacheHit())
        .hasBody("Jigglypuff")
        .containsHeader("X-Version", "v2")
        .requestWasSentAt(instantRevalidationSentAndReceived)
        .responseWasReceivedAt(instantRevalidationSentAndReceived);
  }

  @StoreParameterizedTest
  void successfulRevalidationWithZeroContentLength(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse()
            .setHeader("X-Version", "v1")
            .setHeader("Cache-Control", "max-age=1")
            .setBody("Pikachu"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Pikachu");

    // Make response stale
    clock.advanceSeconds(2);
    server.enqueue(
        new MockResponse()
            .setResponseCode(HTTP_NOT_MODIFIED)
            .setHeader("X-Version", "v2")
            .setHeader("Content-Length", "0")); // This is wrong, but some servers do it
    verifyThat(get(serverUri))
        .isConditionalHit()
        .hasBody("Pikachu")
        .containsHeader("X-Version", "v2")
        .containsHeader(
            "Content-Length", "Pikachu".length()) // Correct Content-Length isn't replaced
        .networkResponse()
        .containsHeader("Content-Length", "0");

    verifyThat(awaitCacheHit())
        .hasBody("Pikachu")
        .containsHeader("X-Version", "v2")
        .containsHeader("Content-Length", "Pikachu".length());
  }

  @StoreParameterizedTest
  void prohibitNetworkOnRequiredValidation(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("123"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("123");

    // Make response stale
    clock.advanceSeconds(2);
    verifyThat(get(GET(serverUri).header("Cache-Control", "only-if-cached")))
        .isCacheUnsatisfaction()
        .hasBody("");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Connection",
        "Proxy-Connection",
        "Keep-Alive",
        "WWW-Authenticate",
        "Proxy-Authenticate",
        "Proxy-Authorization",
        "TE",
        "Trailer",
        "Transfer-Encoding",
        "Upgrade",
        "Content-Location",
        "Content-MD5",
        "ETag",
        "Content-Encoding",
        "Content-Range",
        "Content-Type",
        "Content-Length",
        "X-Frame-Options",
        "X-XSS-Protection",
        "X-Content-*",
        "X-Webkit-*"
      })
  @StoreSpec(store = DISK, fileSystem = SYSTEM)
  void retainedStoredHeadersOnRevalidation(String headerName, Store store) throws Exception {
    clientBuilder.autoAcceptEncoding(false);
    setUpCache(store);

    // Replace '*' in header prefixes.
    headerName = headerName.replace("*", "Something");

    // Validity of the value's format isn't relevant for this test. The HTTP client however
    // complains if Content-Length isn't correct.
    var cacheHeaderValue =
        "Content-Length".equalsIgnoreCase(headerName) ? "Pikachu".length() : "v1";
    var networkHeaderValue = "Content-Length".equalsIgnoreCase(headerName) ? 0 : "v2";
    server.enqueue(
        new MockResponse()
            .setHeader(headerName, cacheHeaderValue)
            .setHeader("Cache-Control", "max-age=1")
            .setBody("Pikachu"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Pikachu");

    // Make response stale.
    clock.advanceSeconds(2);
    server.enqueue(
        new MockResponse()
            .setResponseCode(HTTP_NOT_MODIFIED)
            .setHeader(headerName, networkHeaderValue));
    verifyThat(get(serverUri))
        .isConditionalHit()
        .containsHeader(headerName, cacheHeaderValue.toString()) // The stored header is retained.
        .hasBody("Pikachu");
  }

  private enum ValidatorConfig {
    ETAG(true, false),
    LAST_MODIFIED(false, true),
    ALL(true, true),
    NONE(false, false);

    final boolean etag;
    final boolean lastModified;

    ValidatorConfig(boolean etag, boolean lastModified) {
      this.etag = etag;
      this.lastModified = lastModified;
    }

    Headers getValidators(int etagVersion, Instant now) {
      var validators = new Headers.Builder();
      if (etag) {
        validators.add("ETag", Integer.toString(etagVersion));
      }
      if (lastModified) {
        validators.add("Last-Modified", now);
      }
      return validators.build();
    }
  }

  @StoreParameterizedTest
  void revalidationFromStale(StoreContext storeContext) throws Throwable {
    testForEachValidator(
        storeContext,
        config -> {
          var request = GET(serverUri);
          assertRevalidation(request, config, true);
        });
  }

  @StoreParameterizedTest
  void failedRevalidationFromStale(StoreContext storeContext) throws Throwable {
    testForEachValidator(
        storeContext,
        config -> {
          var request = GET(serverUri);
          assertFailedRevalidation(request, config, true);
        });
  }

  @StoreParameterizedTest
  void revalidationForcedByNoCache(StoreContext storeContext) throws Throwable {
    testForEachValidator(
        storeContext,
        config -> {
          var request = GET(serverUri).header("Cache-Control", "no-cache");
          assertRevalidation(request, config, false);
        });
  }

  @StoreParameterizedTest
  void failedRevalidationForcedByNoCache(StoreContext storeContext) throws Throwable {
    testForEachValidator(
        storeContext,
        config -> {
          var request = GET(serverUri).header("Cache-Control", "no-cache");
          assertFailedRevalidation(request, config, false);
        });
  }

  private void testForEachValidator(
      StoreContext storeContext, ThrowingConsumer<ValidatorConfig> tester) throws Throwable {
    for (var config : ValidatorConfig.values()) {
      try {
        setUpCache(storeContext.createAndRegisterStore());
        tester.accept(config);

        // Clean workspace for next config
        storeContext.drainQueuedTasksIfNeeded();
        cache.dispose();
      } catch (AssertionError failed) {
        fail(config.toString(), failed);
      }
    }
  }

  private void assertRevalidation(
      HttpRequest triggeringRequest, ValidatorConfig config, boolean makeStale)
      throws IOException, InterruptedException {
    var validators = config.getValidators(1, clock.instant());

    // Make Last-Modified 1 second prior to "now"
    clock.advanceSeconds(1);

    // First response is received at this tick
    var timeInitiallyReceived = clock.instant();
    server.enqueue(
        new MockResponse()
            .setHeaders(validators)
            .setHeader("Cache-Control", "max-age=2")
            .setHeader("X-Version", "v1")
            .setBody("STONKS!"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("STONKS!").containsHeader("X-Version", "v1");
    server.takeRequest(); // Remove initial request

    // Make stale or retain freshness
    clock.advanceSeconds(makeStale ? 3 : 1);

    server.enqueue(
        new MockResponse().setResponseCode(HTTP_NOT_MODIFIED).setHeader("X-Version", "v2"));
    verifyThat(get(triggeringRequest))
        .isConditionalHit()
        .hasBody("STONKS!")
        .containsHeader("X-Version", "v2");

    // Time response received is used in If-Modified-Since if Last-Modified is absent
    var effectiveLastModified =
        config.lastModified ? validators.getInstant("Last-Modified") : timeInitiallyReceived;
    var sentRequest = server.takeRequest();
    assertThat(sentRequest.getHeaders().getInstant("If-Modified-Since"))
        .isEqualTo(effectiveLastModified);
    if (config.etag) {
      assertThat(sentRequest.getHeader("If-None-Match")).isEqualTo("1");
    }
  }

  private void assertFailedRevalidation(
      HttpRequest triggeringRequest, ValidatorConfig config, boolean makeStale)
      throws IOException, InterruptedException {
    var validators1 = config.getValidators(1, clock.instant());
    clock.advanceSeconds(1);

    // Use different etag and Last-Modified for the revalidation response
    var validators2 = config.getValidators(2, clock.instant());
    clock.advanceSeconds(1);

    // First response is received at this tick
    var timeInitiallyReceived = clock.instant();
    server.enqueue(
        new MockResponse()
            .setHeaders(validators1)
            .setHeader("Cache-Control", "max-age=2")
            .setHeader("X-Version", "v1")
            .setBody("STONKS!"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("STONKS!").containsHeader("X-Version", "v1");
    server.takeRequest(); // Remove initial request

    // Make stale or retain freshness
    clock.advanceSeconds(makeStale ? 3 : 1);

    server.enqueue(
        new MockResponse()
            .setHeaders(validators2)
            .setHeader("Cache-Control", "max-age=2")
            .setHeader("X-Version", "v2")
            .setBody("DOUBLE STONKS!"));
    verifyThat(get(triggeringRequest))
        .isConditionalMiss()
        .hasBody("DOUBLE STONKS!")
        .containsHeader("X-Version", "v2")
        .containsHeaders(validators2.toMultimap())
        .cacheResponse() // This is the invalidated cache response
        .containsHeader("X-Version", "v1")
        .containsHeaders(validators1.toMultimap());

    // Retain updated response's freshness
    clock.advanceSeconds(1);

    verifyThat(get(serverUri))
        .isCacheHit()
        .hasBody("DOUBLE STONKS!")
        .containsHeader("X-Version", "v2")
        .containsHeaders(validators2.toMultimap());

    // Time response received is used in If-Modified-Since if Last-Modified is absent
    var effectiveLastModified =
        config.lastModified ? validators1.getInstant("Last-Modified") : timeInitiallyReceived;
    var sentRequest = server.takeRequest();
    assertThat(sentRequest.getHeaders().getInstant("If-Modified-Since"))
        .isEqualTo(effectiveLastModified);
    if (config.etag) {
      assertThat(sentRequest.getHeader("If-None-Match")).isEqualTo("1");
    }
  }

  @StoreParameterizedTest
  void preconditionFieldsAreNotVisibleOnServedResponse(Store store) throws Exception {
    setUpCache(store);

    var lastModifiedString = formatInstant(clock.instant().minusSeconds(1));
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("ETag", "1")
            .setHeader("Last-Modified", lastModifiedString));
    verifyThat(get(serverUri)).isCacheMiss();

    // Make response stale by 1 second
    clock.advanceSeconds(2);

    // Precondition fields aren't visible on the served response's request.
    // The preconditions are however visible from the network response's request.
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(get(serverUri))
        .isConditionalHit()
        .doesNotContainRequestHeader("If-None-Match")
        .doesNotContainRequestHeader("If-Modified-Since")
        .networkResponse()
        .containsRequestHeader("If-None-Match", "1")
        .containsRequestHeader("If-Modified-Since", lastModifiedString);
  }

  @StoreParameterizedTest
  void lastModifiedDefaultsToDateWhenRevalidating(Store store) throws Exception {
    setUpCache(store);

    var dateInstant = clock.instant();
    clock.advanceSeconds(1);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("Date", formatInstant(dateInstant))
            .setBody("FLUX"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("FLUX");
    server.takeRequest(); // Remove initial request

    // Make response stale
    clock.advanceSeconds(2);

    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(get(serverUri)).isConditionalHit().hasBody("FLUX");

    var sentRequest = server.takeRequest();
    assertThat(sentRequest.getHeaders().getInstant("If-Modified-Since")).isEqualTo(dateInstant);
  }

  @StoreParameterizedTest
  void pastExpires(Store store) throws Exception {
    setUpCache(store);

    var date = toUtcDateTime(clock.instant());
    server.enqueue(
        new MockResponse()
            .setHeader("Date", toHttpDateString(date))
            .setHeader("Expires", toHttpDateString(date.minusSeconds(10)))
            .setBody("Pikachu"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Pikachu");

    // Negative freshness lifetime caused by past Expires triggers revalidation
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Psyduck"));
    verifyThat(get(serverUri)).isConditionalMiss().hasBody("Psyduck");
    verifyThat(get(serverUri)).isCacheHit().hasBody("Psyduck");
  }

  @StoreParameterizedTest
  void futureLastModified(Store store) throws Exception {
    setUpCache(store);

    var date = toUtcDateTime(clock.instant());
    var lastModified = date.plusSeconds(10);
    // Don't include explicit freshness to trigger heuristics, which relies on Last-Modified
    server.enqueue(
        new MockResponse()
            .setHeader("Date", toHttpDateString(date))
            .setHeader("Last-Modified", toHttpDateString(lastModified))
            .setBody("Pikachu"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Pikachu");

    // Negative heuristic lifetime caused by future Last-Modified triggers revalidation
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Psyduck"));
    verifyThat(get(serverUri))
        .isConditionalMiss()
        .hasBody("Psyduck")
        .networkResponse()
        .containsRequestHeader("If-Modified-Since", toHttpDateString(lastModified));
    verifyThat(get(serverUri)).isCacheHit().hasBody("Psyduck");
  }

  @StoreParameterizedTest
  void relaxMaxAgeWithRequest(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("tesla"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("tesla");

    // Make response stale
    clock.advanceSeconds(2);

    // Relaxed max-age retains freshness
    var request = GET(serverUri).header("Cache-Control", "max-age=2");
    verifyThat(get(request)).isCacheHit().hasBody("tesla");
  }

  @StoreParameterizedTest
  void constrainMaxAgeWithRequest(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=2").setBody("tesla"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("tesla");

    // Retain freshness
    clock.advanceSeconds(2);
    verifyThat(get(serverUri)).isCacheHit().hasBody("tesla");

    // Constrain max-age so that the response becomes stale
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    var request = GET(serverUri).header("Cache-Control", "max-age=1");
    verifyThat(get(request)).isConditionalHit().hasBody("tesla");
  }

  @StoreParameterizedTest
  void constrainFreshnessWithMinFresh(Store store) throws Exception {
    setUpCache(store);

    // Last-Modified: 2 seconds from "now"
    var lastModifiedInstant = clock.instant();
    clock.advanceSeconds(2);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=3")
            .setHeader("Last-Modified", formatInstant(lastModifiedInstant))
            .setBody("spaceX"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("spaceX");
    server.takeRequest(); // Drop request

    // Set freshness to 2 seconds
    clock.advanceSeconds(1);
    verifyThat(get(serverUri)).isCacheHit().hasBody("spaceX");

    var request1 = GET(serverUri).header("Cache-Control", "min-fresh=2");
    verifyThat(get(request1))
        .isCacheHit() // min-fresh is satisfied
        .hasBody("spaceX");

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=3")
            .setHeader("Last-Modified", formatInstant(lastModifiedInstant))
            .setBody("tesla"));

    var request2 = GET(serverUri).header("Cache-Control", "min-fresh=3");
    verifyThat(get(request2))
        .isConditionalMiss() // min-fresh isn't satisfied
        .hasBody("tesla");

    var sentRequest = server.takeRequest();
    assertThat(sentRequest.getHeaders().getInstant("If-Modified-Since"))
        .isEqualTo(lastModifiedInstant);
  }

  @StoreParameterizedTest
  void acceptingStalenessWithMaxStale(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("stale on a scale"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("stale on a scale");

    // Make response stale by 2 seconds
    clock.advanceSeconds(3);

    BiConsumer<CacheControl, UnaryOperator<ResponseVerifier<String>>> assertStaleness =
        (cacheControl, cacheStatusAssert) -> {
          var request = GET(serverUri).cacheControl(cacheControl);
          var response =
              cacheStatusAssert
                  .apply(verifyThat(getUnchecked(request)))
                  .hasBody("stale on a scale");
          // Must put a warning only if not revalidated
          if (response.getCacheAwareResponse().cacheStatus() == HIT) {
            response.containsHeader("Warning", "110 - \"Response is Stale\"");
          } else {
            response.doesNotContainHeader("Warning");
          }
        };

    // Allow any staleness -> HIT
    assertStaleness.accept(CacheControl.parse("max-stale"), ResponseVerifier::isCacheHit);

    // Allow 3 seconds of staleness -> HIT
    assertStaleness.accept(CacheControl.parse("max-stale=3"), ResponseVerifier::isCacheHit);

    // Allow 2 seconds of staleness -> HIT
    assertStaleness.accept(CacheControl.parse("max-stale=2"), ResponseVerifier::isCacheHit);

    // Allow 1 second of staleness -> CONDITIONAL_HIT as staleness is 2
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    assertStaleness.accept(CacheControl.parse("max-stale=1"), ResponseVerifier::isConditionalHit);
  }

  @StoreParameterizedTest
  void imposeRevalidationWhenStaleByMustRevalidate(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, must-revalidate")
            .setBody("popeye"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("popeye");

    // Make response stale
    clock.advanceSeconds(2);

    // A revalidation is made despite request's max-stale=2
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    var request = GET(serverUri).header("Cache-Control", "max-stale=2");
    verifyThat(get(request)).isConditionalHit().hasBody("popeye");
  }

  @StoreParameterizedTest
  void cacheTwoPathsSameUri(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("alpha"));
    verifyThat(get(serverUri.resolve("/a"))).isCacheMiss().hasBody("alpha");

    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("beta"));
    verifyThat(get(serverUri.resolve("/b"))).isCacheMiss().hasBody("beta");

    verifyThat(get(serverUri.resolve("/a"))).isCacheHit().hasBody("alpha");
    verifyThat(get(serverUri.resolve("/b"))).isCacheHit().hasBody("beta");
  }

  @StoreParameterizedTest
  void preventCachingByNoStoreInResponse(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse().setHeader("Cache-Control", "no-store").setBody("alpha"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("alpha");
    assertNotCached(serverUri);
  }

  @StoreParameterizedTest
  void preventCachingByNoStoreInRequest(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("alpha"));

    var request = GET(serverUri).header("Cache-Control", "no-store");
    verifyThat(get(request)).isCacheMiss().hasBody("alpha");
    assertNotCached(serverUri);
  }

  @StoreParameterizedTest
  void preventCachingByWildcardVary(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("Vary", "*")
            .setBody("Cache me if you can!"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Cache me if you can!");
    assertNotCached(serverUri);
  }

  @StoreParameterizedTest
  void varyingResponse(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("Vary", "X-My-Header")
            .setBody("alpha"));

    var requestAlpha = GET(serverUri).header("X-My-Header", "a");
    verifyThat(get(requestAlpha)).isCacheMiss().hasBody("alpha");
    verifyThat(get(requestAlpha))
        .isCacheHit()
        .hasBody("alpha")
        .cacheResponse()
        .containsRequestHeadersExactly("X-My-Header", "a");

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("Vary", "X-My-Header")
            .setBody("beta"));

    var requestBeta = GET(serverUri).header("X-My-Header", "b");
    verifyThat(get(requestBeta)).isCacheMiss().hasBody("beta");
    verifyThat(get(requestBeta))
        .isCacheHit()
        .hasBody("beta")
        .cacheResponse()
        .containsRequestHeadersExactly("X-My-Header", "b");

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("Vary", "X-My-Header")
            .setBody("ϕ"));

    // Varying header is absent -> another variant!
    var requestPhi = GET(serverUri);
    verifyThat(get(requestPhi)).isCacheMiss().hasBody("ϕ");
    verifyThat(get(requestPhi))
        .isCacheHit()
        .hasBody("ϕ")
        .cacheResponse()
        .doesNotContainRequestHeader("X-My-Header");
  }

  /**
   * Responses that vary on header fields that can be added implicitly by the HttpClient are
   * rendered as uncacheable.
   */
  @ParameterizedTest
  @ValueSource(strings = {"Cookie", "Cookie2", "Authorization", "Proxy-Authorization"})
  @StoreSpec(store = DISK, fileSystem = SYSTEM)
  void responsesVaryingOnImplicitHeadersAreNotStored(String implicitField, Store store)
      throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("Vary", "Accept-Encoding, " + implicitField)
            .setBody("aaa"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("aaa");
    assertNotCached(serverUri);
  }

  @StoreParameterizedTest
  void varyOnAcceptEncoding(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("Vary", "Accept-Encoding")
            .setHeader("Content-Encoding", "gzip")
            .setBody(new okio.Buffer().write(gzip("Jigglypuff"))));

    var gzipRequest = GET(serverUri).header("Accept-Encoding", "gzip");
    verifyThat(get(gzipRequest)).isCacheMiss().hasBody("Jigglypuff");
    verifyThat(get(gzipRequest)).isCacheHit().hasBody("Jigglypuff");

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("Vary", "Accept-Encoding")
            .setHeader("Content-Encoding", "deflate")
            .setBody(new okio.Buffer().write(deflate("Jigglypuff"))));

    var deflateRequest = GET(serverUri).header("Accept-Encoding", "deflate");
    verifyThat(get(deflateRequest))
        .isCacheMiss() // Gzip variant is replaced
        .hasBody("Jigglypuff");
    verifyThat(get(deflateRequest)).isCacheHit().hasBody("Jigglypuff");
  }

  @StoreParameterizedTest
  void varyOnMultipleFields(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .addHeader("Vary", "Accept-Encoding, Accept-Language")
            .addHeader("Vary", "Accept")
            .setHeader("Content-Language", "fr-FR")
            .setBody("magnifique"));

    var jeNeParlePasAnglais =
        GET(serverUri).header("Accept-Language", "fr-FR").header("Accept-Encoding", "identity");
    verifyThat(get(jeNeParlePasAnglais)).isCacheMiss().hasBody("magnifique");
    verifyThat(get(jeNeParlePasAnglais))
        .isCacheHit()
        .containsHeader("Content-Language", "fr-FR")
        .hasBody("magnifique")
        .cacheResponse()
        .containsRequestHeadersExactly(
            "Accept-Language", "fr-FR",
            "Accept-Encoding", "identity");
    verifyThat(get(jeNeParlePasAnglais.header("My-Header", "a")))
        .isCacheHit()
        .hasBody("magnifique");

    // Current variant has no Accept header, so this won't match
    var withTextHtml =
        jeNeParlePasAnglais.header("Accept", "text/html").header("Cache-Control", "only-if-cached");
    verifyThat(get(withTextHtml)).isCacheUnsatisfaction();

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .addHeader("Vary", "Accept-Encoding, Accept-Language")
            .addHeader("Vary", "Accept")
            .setHeader("Content-Language", "es-ES")
            .setBody("magnífico"));

    var noHabloIngles =
        GET(serverUri)
            .header("Accept-Language", "es-ES")
            .header("Accept-Encoding", "identity")
            .header("Accept", "text/html");
    verifyThat(get(noHabloIngles))
        .isCacheMiss() // French variant is replaced
        .hasBody("magnífico");
    verifyThat(get(noHabloIngles))
        .isCacheHit()
        .containsHeader("Content-Language", "es-ES")
        .hasBody("magnífico")
        .cacheResponse()
        .containsRequestHeadersExactly(
            "Accept-Language", "es-ES",
            "Accept-Encoding", "identity",
            "Accept", "text/html");
    verifyThat(get(noHabloIngles.header("My-Header", "a"))).isCacheHit().hasBody("magnífico");

    // Request with different Accept won't match
    var withApplicationJson =
        noHabloIngles
            .header("Accept", "application/json")
            .header("Cache-Control", "only-if-cached");
    verifyThat(get(withApplicationJson)).isCacheUnsatisfaction();

    // Absent varying fields won't match a request containing them
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .addHeader("Vary", "Accept-Encoding, Accept-Language")
            .addHeader("Vary", "Accept")
            .setHeader("Content-Language", "en-US")
            .setBody("Lit!"));
    verifyThat(get(serverUri))
        .isCacheMiss() // Spanish variant is replaced
        .hasBody("Lit!");
    verifyThat(get(serverUri))
        .isCacheHit()
        .containsHeader("Content-Language", "en-US")
        .hasBody("Lit!");
  }

  @StoreParameterizedTest
  void varyOnMultipleFieldValues(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("Vary", "My-Header")
            .setBody("alpha"));

    var requestAlpha =
        GET(serverUri)
            .header("My-Header", "val1")
            .header("My-Header", "val2")
            .header("My-Header", "val3");
    verifyThat(get(requestAlpha)).isCacheMiss().hasBody("alpha");
    verifyThat(get(requestAlpha)).isCacheHit().hasBody("alpha");

    // This matches as values are only different in order
    var requestBeta =
        GET(serverUri)
            .header("My-Header", "val2")
            .header("My-Header", "val3")
            .header("My-Header", "val1");
    verifyThat(get(requestBeta)).isCacheHit().hasBody("alpha");

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("Vary", "My-Header")
            .setBody("beta"));

    // This doesn't match as there're 2 values vs alpha variant's 3
    var requestBeta2 = GET(serverUri).header("My-Header", "val1").header("My-Header", "val2");
    verifyThat(get(requestBeta2)).isCacheMiss().hasBody("beta");
    verifyThat(get(requestBeta2)).isCacheHit().hasBody("beta");

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("Vary", "My-Header")
            .setBody("charlie"));

    // Request with no varying header values doesn't match
    verifyThat(get(serverUri)).isCacheMiss().hasBody("charlie");
    verifyThat(get(serverUri)).isCacheHit().hasBody("charlie");
  }

  @StoreParameterizedTest
  void cacheMovedPermanently(Store store) throws Exception {
    setUpCache(store);
    client = clientBuilder.followRedirects(Redirect.ALWAYS).build();

    server.enqueue(
        new MockResponse()
            .setResponseCode(301) // 301 is cacheable by default
            .setHeader("Location", "/redirect"));
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "no-store") // Prevent caching
            .setBody("Ey yo"));
    verifyThat(get(serverUri)).hasCode(200).isCacheMiss().hasBody("Ey yo");

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "no-store") // Prevent caching
            .setBody("Ey yo"));
    verifyThat(get(serverUri))
        .hasCode(200)
        .hasUri(serverUri.resolve("/redirect"))
        .isCacheMiss() // Target response isn't cacheable
        .hasBody("Ey yo")
        .previousResponse()
        .hasCode(301)
        .isCacheHit() // 301 response is cached
        .containsHeader("Location", "/redirect");

    // Disable auto redirection
    client = clientBuilder.followRedirects(Redirect.NEVER).build();

    verifyThat(get(serverUri)).hasCode(301).isCacheHit();
  }

  @StoreParameterizedTest
  void cacheTemporaryRedirectAndRedirectTarget(Store store) throws Exception {
    setUpCache(store);
    client = clientBuilder.followRedirects(Redirect.ALWAYS).build();

    server.enqueue(
        new MockResponse()
            .setResponseCode(307)
            .setHeader("Cache-Control", "max-age=2")
            .setHeader("Location", "/redirect"));
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Ey yo"));
    verifyThat(get(serverUri)).hasCode(200).isCacheMiss().hasBody("Ey yo");

    verifyThat(get(serverUri))
        .hasCode(200)
        .hasUri(serverUri.resolve("/redirect"))
        .isCacheHit()
        .hasBody("Ey yo")
        .previousResponse()
        .hasCode(307)
        .isCacheHit()
        .containsHeader("Location", "/redirect");
    verifyThat(get(serverUri.resolve("/redirect"))).hasCode(200).isCacheHit().hasBody("Ey yo");

    // Disable auto redirection
    client = clientBuilder.followRedirects(Redirect.NEVER).build();

    verifyThat(get(serverUri)).hasCode(307).isCacheHit();
    verifyThat(get(serverUri.resolve("/redirect"))).hasCode(200).isCacheHit().hasBody("Ey yo");

    // Make 200 response stale but retain 307 response's freshness
    clock.advanceSeconds(2);

    // Enable auto redirection
    client = clientBuilder.followRedirects(Redirect.ALWAYS).build();

    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=2").setBody("Hey there"));
    verifyThat(get(serverUri))
        .hasCode(200)
        .isConditionalMiss()
        .hasBody("Hey there")
        .previousResponse()
        .hasCode(307)
        .isCacheHit();
  }

  @StoreParameterizedTest
  void cacheRedirectTarget(Store store) throws Exception {
    setUpCache(store);
    client = clientBuilder.followRedirects(Redirect.ALWAYS).build();

    server.enqueue(
        new MockResponse()
            .setResponseCode(307) // 307 won't be cached as it isn't cacheable by default
            .setHeader("Location", "/redirect"));
    server.enqueue(
        new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Wakanda forever"));
    verifyThat(get(serverUri)).hasCode(200).isCacheMiss().hasBody("Wakanda forever");

    server.enqueue(new MockResponse().setResponseCode(307).setHeader("Location", "/redirect"));
    verifyThat(get(serverUri))
        .hasCode(200)
        .isCacheHit() // 200 response is cached
        .hasBody("Wakanda forever")
        .previousResponse()
        .hasCode(307)
        .isCacheMiss();
  }

  @ParameterizedTest
  @StoreSpec(store = DISK, fileSystem = SYSTEM)
  @ValueSource(ints = {301, 302, 303, 307, 308})
  void cacheableRedirectWithUncacheableTarget(int code, Store store) throws Exception {
    setUpCache(store);
    client = clientBuilder.followRedirects(Redirect.ALWAYS).build();

    // Make redirect cacheable & target uncacheable
    server.enqueue(
        new MockResponse()
            .setResponseCode(code)
            .setHeader("Location", "/redirect")
            .setHeader("Cache-Control", "max-age=1"));
    server.enqueue(
        new MockResponse().setHeader("Cache-Control", "no-store").setBody("Wakanda forever"));
    verifyThat(get(serverUri)).hasCode(200).isCacheMiss().hasBody("Wakanda forever");

    server.enqueue(
        new MockResponse().setHeader("Cache-Control", "no-store").setBody("Wakanda forever"));
    verifyThat(get(serverUri))
        .hasCode(200)
        .isCacheMiss() // Target response isn't cached
        .hasBody("Wakanda forever")
        .previousResponse()
        .hasCode(code)
        .isCacheHit(); // Redirecting response is cached

    // Disable auto redirection
    client = clientBuilder.followRedirects(Redirect.NEVER).build();

    server.enqueue(
        new MockResponse().setHeader("Cache-Control", "no-store").setBody("Wakanda forever"));
    verifyThat(get(serverUri)).hasCode(code).isCacheHit();
    verifyThat(get(serverUri.resolve("/redirect")))
        .hasCode(200)
        .isCacheMiss()
        .hasBody("Wakanda forever");
  }

  @ParameterizedTest
  @StoreSpec(store = DISK, fileSystem = SYSTEM)
  @ValueSource(ints = {301, 302, 303, 307, 308})
  void uncacheableRedirectWithCacheableTarget(int code, Store store) throws Exception {
    setUpCache(store);
    client = clientBuilder.followRedirects(Redirect.ALWAYS).build();

    // Make redirect uncacheable & target cacheable
    var redirectingResponse =
        new MockResponse().setResponseCode(code).setHeader("Location", "/redirect");
    if (code == 301) {
      // 301 is cacheable by default so explicitly disallow caching
      redirectingResponse.setHeader("Cache-Control", "no-store");
    }
    server.enqueue(redirectingResponse);
    server.enqueue(
        new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Wakanda forever"));
    verifyThat(get(serverUri)).hasCode(200).isCacheMiss().hasBody("Wakanda forever");

    server.enqueue(redirectingResponse);
    verifyThat(get(serverUri))
        .hasCode(200)
        .isCacheHit() // Target response is cached
        .hasBody("Wakanda forever")
        .previousResponse()
        .hasCode(code)
        .isCacheMiss(); // Redirecting response isn't cached

    // Disable auto redirection
    client = clientBuilder.followRedirects(Redirect.NEVER).build();

    server.enqueue(redirectingResponse);
    verifyThat(get(serverUri)).hasCode(code).isCacheMiss();
    verifyThat(get(serverUri.resolve("/redirect")))
        .hasCode(200)
        .isCacheHit()
        .hasBody("Wakanda forever");
  }

  @ParameterizedTest
  @StoreSpec(store = DISK, fileSystem = SYSTEM)
  @ValueSource(ints = {301, 302, 303, 307, 308})
  void uncacheableRedirectWithUncacheableTarget(int code, Store store) throws Exception {
    setUpCache(store);
    client = clientBuilder.followRedirects(Redirect.ALWAYS).build();

    // Make both redirect & target uncacheable
    var redirectingResponse =
        new MockResponse().setResponseCode(code).setHeader("Location", "/redirect");
    if (code == 301) {
      // 301 is cacheable by default so explicitly disallow caching
      redirectingResponse.setHeader("Cache-Control", "no-store");
    }
    server.enqueue(redirectingResponse);
    server.enqueue(
        new MockResponse().setHeader("Cache-Control", "no-store").setBody("Wakanda forever"));
    verifyThat(get(serverUri)).hasCode(200).isCacheMiss().hasBody("Wakanda forever");

    server.enqueue(redirectingResponse);
    server.enqueue(
        new MockResponse().setHeader("Cache-Control", "no-store").setBody("Wakanda forever"));
    verifyThat(get(serverUri))
        .hasCode(200)
        .isCacheMiss() // Target response isn't cached
        .hasBody("Wakanda forever")
        .previousResponse()
        .hasCode(code)
        .isCacheMiss(); // Redirecting response isn't cached

    // Disable auto redirection
    client = clientBuilder.followRedirects(Redirect.NEVER).build();

    server.enqueue(redirectingResponse);
    server.enqueue(
        new MockResponse().setHeader("Cache-Control", "no-store").setBody("Wakanda forever"));
    verifyThat(get(serverUri)).hasCode(code).isCacheMiss();
    verifyThat(get(serverUri.resolve("/redirect")))
        .hasCode(200)
        .isCacheMiss()
        .hasBody("Wakanda forever");
  }

  @ParameterizedTest
  @StoreSpec(store = DISK, fileSystem = SYSTEM)
  @ValueSource(ints = {301, 302, 303, 307, 308})
  void cacheableRedirectWithCacheableTarget(int code, Store store) throws Exception {
    setUpCache(store);
    client = clientBuilder.followRedirects(Redirect.ALWAYS).build();

    // Make both redirect & target cacheable
    server.enqueue(
        new MockResponse()
            .setResponseCode(code)
            .setHeader("Location", "/redirect")
            .setHeader("Cache-Control", "max-age=1"));
    server.enqueue(
        new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Wakanda forever"));
    verifyThat(get(serverUri)).hasCode(200).isCacheMiss().hasBody("Wakanda forever");
    verifyThat(get(serverUri))
        .hasCode(200)
        .isCacheHit() // Target response is cached
        .hasBody("Wakanda forever")
        .previousResponse()
        .hasCode(code)
        .isCacheHit(); // Redirecting response is cached

    // Disable auto redirection
    client = clientBuilder.followRedirects(Redirect.NEVER).build();

    verifyThat(get(serverUri)).hasCode(code).isCacheHit();
    verifyThat(get(serverUri.resolve("/redirect")))
        .hasCode(200)
        .isCacheHit()
        .hasBody("Wakanda forever");
  }

  /** Ensure the cache doesn't store responses that disagree with their requests' URIs. */
  @StoreParameterizedTest
  void responseWithDifferentUriFromThatOfRequest(Store store) throws Exception {
    setUpCache(store);

    // Don't let the cache intercept redirects
    clientBuilder =
        Methanol.newBuilder()
            .interceptor(cache.interceptor(executor))
            .followRedirects(Redirect.ALWAYS);
    client = clientBuilder.build();

    // The cache only sees the second response
    server.enqueue(new MockResponse().setResponseCode(301).setHeader("Location", "/redirect"));
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Pikachu"));

    // Offer the response to cache
    verifyThat(get(serverUri))
        .hasCode(200)
        .hasUri(serverUri.resolve("/redirect"))
        .isCacheMiss()
        .hasBody("Pikachu");

    // The cache refuses the response as its URI is different from that of the request it
    // intercepted.
    assertNotCached(serverUri.resolve("/redirect"));
  }

  @StoreParameterizedTest
  void staleWhileRevalidate(Store store) throws Exception {
    setUpCache(store);
    var dateInstant = clock.instant();
    var lastModifiedInstant = dateInstant.minusSeconds(1);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, stale-while-revalidate=2")
            .setHeader("ETag", "1")
            .setHeader("Last-Modified", formatInstant(lastModifiedInstant))
            .setHeader("Date", formatInstant(dateInstant))
            .setBody("Pikachu"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Pikachu");
    verifyThat(get(serverUri)).isCacheHit().hasBody("Pikachu");
    server.takeRequest(); // Remove initial request

    // Make response stale by 2 seconds
    clock.advanceSeconds(3);

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, stale-while-revalidate=2")
            .setHeader("ETag", "2")
            .setHeader("Last-Modified", formatInstant(clock.instant().minusSeconds(1)))
            .setHeader("Date", formatInstant(clock.instant()))
            .setBody("Ricardo"));
    verifyThat(get(serverUri))
        .isCacheHit()
        .hasBody("Pikachu")
        .containsHeader("ETag", "1")
        .containsHeader("Warning", "110 - \"Response is Stale\"")
        .containsHeader("Age", "3");

    // A revalidation request is sent in background
    var sentRequest = server.takeRequest();
    assertThat(sentRequest.getHeader("If-None-Match")).isEqualTo("1");
    assertThat(sentRequest.getHeaders().getInstant("If-Modified-Since"))
        .isEqualTo(lastModifiedInstant);

    verifyThat(awaitCacheHit())
        .isCacheHit()
        .hasBody("Ricardo")
        .containsHeader("ETag", "2")
        .doesNotContainHeader("Warning")
        .containsHeader("Age", "0");
  }

  @StoreParameterizedTest
  void unsatisfiedStaleWhileRevalidate(Store store) throws Exception {
    setUpCache(store);
    var dateInstant = clock.instant();
    var lastModifiedInstant = dateInstant.minusSeconds(1);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, stale-while-revalidate=2")
            .setHeader("ETag", "1")
            .setHeader("Last-Modified", formatInstant(lastModifiedInstant))
            .setHeader("Date", formatInstant(dateInstant))
            .setBody("Pikachu"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Pikachu");
    verifyThat(get(serverUri)).isCacheHit().hasBody("Pikachu");
    server.takeRequest(); // Remove initial request

    // Make response stale by 3 seconds (unsatisfied stale-while-revalidate)
    clock.advanceSeconds(4);

    // Synchronous revalidation is issued when stale-while-revalidate isn't satisfied
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(get(serverUri))
        .isConditionalHit()
        .hasBody("Pikachu")
        .containsHeader("ETag", "1")
        .doesNotContainHeader("Warning");

    // Make response stale by 3 seconds (unsatisfied stale-while-revalidate)
    clock.advanceSeconds(4);

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, stale-while-revalidate=2")
            .setHeader("ETag", "2")
            .setHeader("Last-Modified", formatInstant(clock.instant().minusSeconds(1)))
            .setHeader("Date", formatInstant(clock.instant()))
            .setBody("Ricardo"));
    verifyThat(get(serverUri))
        .isConditionalMiss()
        .hasBody("Ricardo")
        .containsHeader("ETag", "2")
        .doesNotContainHeader("Warning");
  }

  @ParameterizedTest
  @StoreSpec(store = DISK, fileSystem = SYSTEM)
  @ValueSource(ints = {500, 502, 503, 504})
  void staleIfErrorWithServerErrorCodes(int code, Store store) throws Exception {
    setUpCache(store);
    if (code == HTTP_UNAVAILABLE) {
      failOnUnavailableResponses = false;
    }

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, stale-if-error=2")
            .setBody("Ricardo"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Ricardo");
    verifyThat(get(serverUri)).isCacheHit().hasBody("Ricardo");

    // Make response stale by 1 second
    clock.advanceSeconds(2);

    server.enqueue(new MockResponse().setResponseCode(code));
    verifyThat(get(serverUri))
        .hasCode(200)
        .isCacheHit()
        .hasBody("Ricardo")
        .containsHeader("Warning", "110 - \"Response is Stale\"");

    // Make response stale by 2 seconds
    clock.advanceSeconds(1);

    server.enqueue(new MockResponse().setResponseCode(code));
    verifyThat(get(serverUri))
        .hasCode(200)
        .isCacheHit()
        .hasBody("Ricardo")
        .containsHeader("Warning", "110 - \"Response is Stale\"");
  }

  @ParameterizedTest
  @StoreSpec(store = DISK, fileSystem = SYSTEM)
  @ValueSource(ints = {500, 502, 503, 504})
  void unsatisfiedStaleIfErrorWithServerErrorCodes(int code, Store store) throws Exception {
    setUpCache(store);
    if (code == HTTP_UNAVAILABLE) {
      failOnUnavailableResponses = false;
    }

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, stale-if-error=1")
            .setBody("Ditto"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Ditto");
    verifyThat(get(serverUri)).isCacheHit().hasBody("Ditto");

    // Make response stale by 2 seconds
    clock.advanceSeconds(3);

    // stale-if-error isn't satisfied
    server.enqueue(new MockResponse().setResponseCode(code));
    verifyThat(get(serverUri))
        .hasCode(code)
        .isCacheMissWithCacheResponse()
        .hasBody("") // No body in the error response
        .doesNotContainHeader("Warning");
  }

  private static final class FailingInterceptor implements Interceptor {
    private final Supplier<Throwable> failureFactory;

    FailingInterceptor(Supplier<Throwable> failureFactory) {
      this.failureFactory = failureFactory;
    }

    FailingInterceptor(Class<? extends Throwable> failureType) {
      this.failureFactory =
          () -> {
            try {
              return failureType.getConstructor().newInstance();
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          };
    }

    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain) {
      throw throwUnchecked(failureFactory.get());
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
        HttpRequest request, Chain<T> chain) {
      return CompletableFuture.failedFuture(failureFactory.get());
    }

    @SuppressWarnings("unchecked")
    private static <X extends Throwable> X throwUnchecked(Throwable t) throws X {
      throw (X) t;
    }
  }

  @ParameterizedTest
  @StoreSpec(store = DISK, fileSystem = SYSTEM)
  @ValueSource(classes = {ConnectException.class, UnknownHostException.class})
  void staleIfErrorWithConnectionFailure(Class<? extends Throwable> failureType, Store store)
      throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, stale-if-error=2")
            .setBody("Jigglypuff"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Jigglypuff");
    verifyThat(get(serverUri)).isCacheHit().hasBody("Jigglypuff");

    // Make response stale by 1 second
    clock.advanceSeconds(2);

    client = clientBuilder.backendInterceptor(new FailingInterceptor(failureType)).build();
    verifyThat(get(serverUri))
        .hasCode(200)
        .isCacheHit()
        .hasBody("Jigglypuff")
        .containsHeader("Warning", "110 - \"Response is Stale\"");

    // Make response stale by 2 seconds
    clock.advanceSeconds(1);

    server.enqueue(new MockResponse().setBody("huh?"));
    verifyThat(get(serverUri))
        .hasCode(200)
        .isCacheHit()
        .hasBody("Jigglypuff")
        .containsHeader("Warning", "110 - \"Response is Stale\"");
  }

  @ParameterizedTest
  @StoreSpec(store = DISK, fileSystem = SYSTEM)
  @ValueSource(classes = {ConnectException.class, UnknownHostException.class})
  void unsatisfiedStaleIfErrorWithConnectionFailure(
      Class<? extends Throwable> failureType, Store store) throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, stale-if-error=1")
            .setBody("Ricardo"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Ricardo");
    verifyThat(get(serverUri)).isCacheHit().hasBody("Ricardo");

    // Make response stale by 2 seconds
    clock.advanceSeconds(3);

    client = clientBuilder.backendInterceptor(new FailingInterceptor(failureType)).build();

    // stale-if-error isn't satisfied
    assertThatExceptionOfType(failureType).isThrownBy(() -> get(serverUri));
  }

  @StoreParameterizedTest
  void staleIfErrorWithInapplicableErrorCode(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, stale-if-error=1")
            .setBody("Eevee"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Eevee");
    verifyThat(get(serverUri)).isCacheHit().hasBody("Eevee");

    // Make response stale by 1 second
    clock.advanceSeconds(2);

    // Only 5xx error codes are applicable to stale-if-error
    server.enqueue(new MockResponse().setResponseCode(404));
    verifyThat(get(serverUri))
        .hasCode(404)
        .isConditionalMiss()
        .hasBody(""); // Error response has no body
  }

  @StoreParameterizedTest
  void staleIfErrorWithInapplicableException(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, stale-if-error=1")
            .setBody("Charmander"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Charmander");
    verifyThat(get(serverUri)).isCacheHit().hasBody("Charmander");

    // Make requests fail with a inapplicable exception
    client = clientBuilder.backendInterceptor(new FailingInterceptor(TestException::new)).build();

    // Make response stale by 1 second
    clock.advanceSeconds(2);

    // stale-if-error isn't satisfied
    assertThatExceptionOfType(TestException.class).isThrownBy(() -> get(serverUri));
  }

  @StoreParameterizedTest
  void staleIfErrorWithUncheckedIOException(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, stale-if-error=1")
            .setBody("Jynx"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Jynx");
    verifyThat(get(serverUri)).isCacheHit().hasBody("Jynx");

    // Make requests fail with ConnectException disguised as an UncheckedIOException
    client =
        clientBuilder
            .backendInterceptor(
                new FailingInterceptor(() -> new UncheckedIOException(new ConnectException())))
            .build();

    // Make response stale by 1 second
    clock.advanceSeconds(2);

    // stale-if-error is applicable
    var request = GET(serverUri).header("Cache-Control", "stale-if-error=2");
    verifyThat(get(request))
        .isCacheHit()
        .hasCode(200)
        .hasBody("Jynx")
        .containsHeader("Warning", "110 - \"Response is Stale\"");
  }

  @StoreParameterizedTest
  void staleIfErrorInRequestOverridesThatInResponse(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, stale-if-error=2")
            .setBody("Psyduck"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Psyduck");
    verifyThat(get(serverUri)).isCacheHit().hasBody("Psyduck");

    // Make response stale by 3 seconds
    clock.advanceSeconds(4);

    // Only request's stale-if-error is satisfied
    server.enqueue(new MockResponse().setResponseCode(HTTP_INTERNAL_ERROR));
    var request1 = GET(serverUri).header("Cache-Control", "stale-if-error=3");
    verifyThat(get(request1)).hasCode(200).isCacheHit().hasBody("Psyduck");

    // Refresh response
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(get(serverUri)).isConditionalHit();

    // Make response stale by 2 seconds
    clock.advanceSeconds(3);

    // Unsatisfied request's stale-if-error takes precedence
    server.enqueue(new MockResponse().setResponseCode(500));
    var request2 = GET(serverUri).header("Cache-Control", "stale-if-error=1");
    verifyThat(get(request2)).hasCode(500).isConditionalMiss().hasBody("");
  }

  @StoreParameterizedTest
  void warnCodes1xxAreRemovedOnRevalidation(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("Warning", "199 - \"OMG IT'S HAPPENING\"")
            .setHeader("Warning", "299 - \"EVERY BODY STAY CALM\"")
            .setBody("Dwight the trickster"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Dwight the trickster");

    // Make response stale
    clock.advanceSeconds(2);

    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(get(serverUri))
        .isConditionalHit()
        .hasBody("Dwight the trickster")
        .containsHeader("Warning", "299 - \"EVERY BODY STAY CALM\""); // Warn code 199 is removed
  }

  /**
   * Tests that status codes in rfc7231 6.1 without Cache-Control or Expires are only cached if
   * defined as cacheable by default.
   */
  @ParameterizedTest
  @CsvFileSource(resources = "/default_cacheability.csv", numLinesToSkip = 1)
  @StoreSpec(store = DISK, fileSystem = SYSTEM)
  void defaultCacheability(int code, boolean cacheableByDefault, Store store) throws Exception {
    setUpCache(store);
    client =
        clientBuilder
            .version(Version.HTTP_1_1) // HTTP_2 doesn't let 101 pass
            .followRedirects(Redirect.NEVER) // Disable redirections in case code is 3xx
            .build();
    if (code == HTTP_UNAVAILABLE) {
      failOnUnavailableResponses = false;
    }

    // Last-Modified:      20 seconds from date
    // Heuristic lifetime: 2 seconds
    var lastModifiedInstant = clock.instant();
    clock.advanceSeconds(20);
    var dateInstant = clock.instant();
    var body =
        code == 204 || code == 304 ? "" : "Cache me pls!"; // Body with 204 or 304 causes problems
    server.enqueue(
        new MockResponse()
            .setResponseCode(code)
            .setHeader("Last-Modified", formatInstant(lastModifiedInstant))
            .setHeader("Date", formatInstant(dateInstant))
            .setBody(body));
    verifyThat(get(serverUri)).isCacheMiss().hasBody(body);

    // Retrain heuristic freshness
    clock.advanceSeconds(1);

    ResponseVerifier<String> response;
    if (cacheableByDefault) {
      response = verifyThat(get(serverUri)).isCacheHit();
    } else {
      server.enqueue(
          new MockResponse()
              .setResponseCode(code)
              .setHeader("Last-Modified", formatInstant(lastModifiedInstant))
              .setHeader("Date", formatInstant(dateInstant))
              .setBody(body));
      response = verifyThat(get(serverUri)).isCacheMiss();
    }
    response.hasCode(code).hasBody(body);
  }

  @StoreParameterizedTest
  void heuristicExpiration(Store store) throws Exception {
    setUpCache(store);
    // Last-Modified:      20 seconds from date
    // Heuristic lifetime: 2 seconds
    // Age:                1 second
    var lastModifiedInstant = clock.instant();
    clock.advanceSeconds(20);
    var dateInstant = clock.instant();
    clock.advanceSeconds(1);
    server.enqueue(
        new MockResponse()
            .setHeader("Last-Modified", formatInstant(lastModifiedInstant))
            .setHeader("Date", formatInstant(dateInstant))
            .setBody("Cache me pls!"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Cache me pls!");

    // Retain heuristic freshness (age = 2 secs, heuristic lifetime = 2 secs)
    clock.advanceSeconds(1);

    verifyThat(get(serverUri))
        .isCacheHit()
        .hasBody("Cache me pls!")
        .containsHeader("Age", "2")
        .doesNotContainHeader("Warning");

    // Make response stale (age = 3 secs, heuristic lifetime = 2 secs)
    clock.advanceSeconds(1);

    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(get(serverUri))
        .isConditionalHit()
        .hasBody("Cache me pls!")
        .doesNotContainHeader("Age") // The response has no Age as it has just been revalidated
        .doesNotContainHeader("Warning");
  }

  @StoreParameterizedTest
  void warningOnHeuristicFreshnessWithAgeGreaterThanOneDay(Store store) throws Exception {
    setUpCache(store);
    // Last-Modified:      20 days from date
    // Heuristic lifetime: 2 days
    var lastModifiedInstant = clock.instant();
    clock.advance(Duration.ofDays(20));
    var dateInstant = clock.instant();
    server.enqueue(
        new MockResponse()
            .setHeader("Last-Modified", formatInstant(lastModifiedInstant))
            .setHeader("Date", formatInstant(dateInstant))
            .setBody("Cache me pls!"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Cache me pls!");

    // Retain heuristic freshness (age = 1 day + 1 second, heuristic lifetime = 2 days)
    clock.advance(Duration.ofDays(1).plusSeconds(1));

    verifyThat(get(serverUri))
        .isCacheHit()
        .hasBody("Cache me pls!")
        .containsHeader("Age", Duration.ofDays(1).plusSeconds(1).toSeconds())
        .containsHeader("Warning", "113 - \"Heuristic Expiration\"");
  }

  /** See https://tools.ietf.org/html/rfc7234#section-4.2.3 */
  @StoreParameterizedTest
  void computingAge(Store store) throws Exception {
    setUpCache(store);

    // Simulate response taking 3 seconds to arrive
    client =
        clientBuilder
            .backendInterceptor(
                new Interceptor() {
                  @Override
                  public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
                      throws IOException, InterruptedException {
                    clock.advanceSeconds(3);
                    return chain.forward(request);
                  }

                  @Override
                  public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
                      HttpRequest request, Chain<T> chain) {
                    clock.advanceSeconds(3);
                    return chain.forwardAsync(request);
                  }
                })
            .build();

    // date_value = x
    // now = x + 2
    // request_time = x + 2
    // response_time = request_time + 3 = x + 5
    // apparent_age = response_time - date_value = 5
    // age_value = 10
    clock.advanceSeconds(2);
    server.enqueue(
        new MockResponse().setHeader("Cache-Control", "max-age=60").setHeader("Age", "10"));
    // now = x + 5
    verifyThat(get(serverUri)) // Put in cache & advance clock
        .isCacheMiss()
        .requestWasSentAt(clock.instant().minusSeconds(3))
        .responseWasReceivedAt(clock.instant());

    // now = x + 10
    // resident_time = now - responseTime = 5
    clock.advanceSeconds(5);

    // response_delay = 3
    // corrected_age_value = age_value + response_delay = 13
    // corrected_initial_age = max(apparent_age, corrected_age_value) = 13
    // resident_time = now - response_time = 5
    // current_age = corrected_initial_age + resident_time = 18
    verifyThat(get(serverUri)).isCacheHit().containsHeader("Age", "18");
  }

  @ParameterizedTest
  @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
  @StoreSpec(store = DISK, fileSystem = SYSTEM)
  void unsafeMethodsInvalidateCache(String method, StoreContext storeContext) throws Exception {
    assertUnsafeMethodInvalidatesCache(storeContext, method, 200, true);
    assertUnsafeMethodInvalidatesCache(storeContext, method, 302, true);
  }

  @ParameterizedTest
  @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
  @StoreSpec(store = DISK, fileSystem = SYSTEM)
  void unsafeMethodsDoNotInvalidateCacheWithErrorResponse(String method, StoreContext storeContext)
      throws Exception {
    assertUnsafeMethodInvalidatesCache(storeContext, method, 104, false);
    assertUnsafeMethodInvalidatesCache(storeContext, method, 404, false);
    assertUnsafeMethodInvalidatesCache(storeContext, method, 504, false);
  }

  private void assertUnsafeMethodInvalidatesCache(
      StoreContext storeContext, String method, int code, boolean invalidationExpected)
      throws Exception {
    // Perform cleanup for previous call
    if (cache != null) {
      storeContext.drainQueuedTasksIfNeeded();
      cache.dispose();
    }
    setUpCache(storeContext.createAndRegisterStore());

    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Pikachu"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Pikachu");
    verifyThat(get(serverUri)).isCacheHit().hasBody("Pikachu");

    server.enqueue(new MockResponse().setResponseCode(code).setBody("Charmander"));
    var unsafeRequest = MutableRequest.create(serverUri).method(method, BodyPublishers.noBody());
    verifyThat(get(unsafeRequest)).isCacheMiss();

    if (invalidationExpected) {
      assertNotCached(serverUri);
    } else {
      verifyThat(get(serverUri)).hasCode(200).isCacheHit().hasBody("Pikachu");
    }
  }

  /**
   * Test that an invalidated response causes the URIs referenced via Location & Content-Location to
   * also get invalidated (https://tools.ietf.org/html/rfc7234#section-4.4).
   */
  // TODO find a way to test referenced URIs aren't invalidated if they have different hosts
  @ParameterizedTest
  @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
  @StoreSpec(store = DISK, fileSystem = SYSTEM)
  void unsafeMethodsInvalidateReferencedUris(String method, Store store) throws Exception {
    setUpCache(store);
    client = clientBuilder.followRedirects(Redirect.NEVER).build();

    server.enqueue(
        new MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
            .setHeader("Cache-Control", "max-age=1")
            .setBody("Pikachu"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Pikachu");

    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Ditto"));
    verifyThat(get(serverUri.resolve("ditto"))).isCacheMiss().hasBody("Ditto");

    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Eevee"));
    verifyThat(get(serverUri.resolve("eevee"))).isCacheMiss().hasBody("Eevee");

    verifyThat(get(serverUri)).isCacheHit().hasBody("Pikachu");
    verifyThat(get(serverUri.resolve("ditto"))).isCacheHit().hasBody("Ditto");
    verifyThat(get(serverUri.resolve("eevee"))).isCacheHit().hasBody("Eevee");

    server.enqueue(
        new MockResponse()
            .setHeader("Location", "ditto")
            .setHeader("Content-Location", "eevee")
            .setBody("Eevee"));
    var unsafeRequest = MutableRequest.create(serverUri).method(method, BodyPublishers.noBody());
    verifyThat(get(unsafeRequest)).isCacheMiss().hasBody("Eevee");
    assertNotCached(serverUri.resolve("ditto"));
    assertNotCached(serverUri.resolve("eevee"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
  @StoreSpec(store = DISK, fileSystem = SYSTEM)
  void unsafeMethodsAreNotCached(String method, Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=2").setBody("Pikachu"));

    var unsafeRequest = MutableRequest.create(serverUri).method(method, BodyPublishers.noBody());
    verifyThat(get(unsafeRequest)).isCacheMiss().hasBody("Pikachu");
    assertNotCached(serverUri);
  }

  @StoreParameterizedTest
  void headOfCachedGet(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=2").setBody("Mewtwo"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Mewtwo");

    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=2"));
    var head = MutableRequest.create(serverUri).method("HEAD", BodyPublishers.noBody());
    verifyThat(get(head)).isCacheMiss().hasBody("");

    verifyThat(get(serverUri)).isCacheHit().hasBody("Mewtwo");
  }

  @UseHttps
  @StoreParameterizedTest
  void requestsWithPushPromiseHandlersBypassCache(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Steppenwolf"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Steppenwolf");
    verifyThat(get(serverUri)).isCacheHit().hasBody("Steppenwolf");

    // Make cached response stale
    clock.advanceSeconds(2);

    // Requests with push promises aren't served by the cache as it can't know what might be pushed
    // by the server. The main response contributes to updating the cache as usual.
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Darkseid"));
    verifyThat(getWithPushHandler(serverUri)).isCacheMiss().hasBody("Darkseid");
    verifyThat(get(serverUri)).isCacheHit().hasBody("Darkseid");
  }

  private enum PreconditionKind {
    DATE("If-Unmodified-Since", "If-Modified-Since") {
      @Override
      void add(MutableRequest request, String field, Clock clock) {
        request.header(field, formatInstant(clock.instant().minusSeconds(3)));
      }
    },
    TAG("If-Match", "If-None-Match", "If-Range") { // If range can be both
      @Override
      void add(MutableRequest request, String field, Clock clock) {
        request.header(field, "1");
      }
    };

    private final Set<String> fields;

    PreconditionKind(String... fields) {
      var fieldSet = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
      fieldSet.addAll(Set.of(fields));
      this.fields = Collections.unmodifiableSet(fieldSet);
    }

    abstract void add(MutableRequest request, String field, Clock clock);

    static PreconditionKind get(String field) {
      return Stream.of(PreconditionKind.values())
          .filter(kind -> kind.fields.contains(field))
          .findFirst()
          .orElseThrow();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"If-Match", "If-Unmodified-Since", "If-None-Match", "If-Range"})
  void requestsWithPreconditionsAreForwarded(String preconditionField, Store store)
      throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse().setBody("For Darkseid"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("For Darkseid");
    verifyThat(get(serverUri)).isCacheHit().hasBody("For Darkseid");

    server.enqueue(new MockResponse().setBody("For Darkseid"));
    var request = GET(serverUri);
    PreconditionKind.get(preconditionField).add(request, preconditionField, clock);
    verifyThat(get(request)).isCacheMiss().hasBody("For Darkseid");
    verifyThat(get(request.removeHeader(preconditionField))).isCacheHit().hasBody("For Darkseid");
  }

  @StoreParameterizedTest
  void manuallyInvalidateEntries(Store store) throws Exception {
    setUpCache(store);
    var uri1 = serverUri.resolve("/a");
    var uri2 = serverUri.resolve("/b");
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest recordedRequest) {
            var path = recordedRequest.getRequestUrl().pathSegments().get(0);
            switch (path) {
              case "a":
                return new MockResponse().setBody("a");
              case "b":
                return new MockResponse().setBody("b");
              default:
                return fail("unexpected path: " + path);
            }
          }
        });

    verifyThat(get(uri1)).isCacheMiss().hasBody("a");
    verifyThat(get(uri1)).isCacheHit().hasBody("a");

    verifyThat(get(uri2)).isCacheMiss().hasBody("b");
    verifyThat(get(uri2)).isCacheHit().hasBody("b");

    assertThat(cache.remove(uri1)).isTrue();
    assertNotCached(uri1);

    assertThat(cache.remove(MutableRequest.GET(uri2))).isTrue();
    assertNotCached(uri2);

    verifyThat(get(uri1)).isCacheMiss().hasBody("a");
    verifyThat(get(uri1)).isCacheHit().hasBody("a");

    verifyThat(get(uri2)).isCacheMiss().hasBody("b");
    verifyThat(get(uri2)).isCacheHit().hasBody("b");

    cache.clear();
    assertNotCached(uri1);
    assertNotCached(uri2);
  }

  @StoreParameterizedTest
  void manuallyInvalidateEntryMatchingASpecificVariant(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("Vary", "Accept-Encoding")
            .setHeader("Content-Encoding", "gzip")
            .setBody(new okio.Buffer().write(gzip("Mew"))));
    verifyThat(get(GET(serverUri).header("Accept-Encoding", "gzip"))).isCacheMiss().hasBody("Mew");

    // Removal only succeeds for the request matching the correct response variant

    assertThat(cache.remove(GET(serverUri).header("Accept-Encoding", "deflate"))).isFalse();
    verifyThat(get(GET(serverUri).header("Accept-Encoding", "gzip"))).isCacheHit().hasBody("Mew");

    assertThat(cache.remove(GET(serverUri).header("Accept-Encoding", "gzip"))).isTrue();
    assertNotCached(GET(serverUri).header("Accept-Encoding", "gzip"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"private", "public"})
  @StoreSpec(store = DISK, fileSystem = SYSTEM)
  void responseWithCacheControlPublicOrPrivateIsCacheableByDefault(String directive, Store store)
      throws Exception {
    setUpCache(store);
    // Last-Modified:      30 seconds from date
    // Heuristic lifetime: 3 seconds
    var lastModifiedInstant = clock.instant();
    clock.advanceSeconds(30);
    var dateInstant = clock.instant();
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", directive)
            .setHeader("Last-Modified", formatInstant(lastModifiedInstant))
            .setHeader("Date", formatInstant(dateInstant))
            .setBody("Mew"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Mew");
    server.takeRequest(); // Drop first request

    // Retain freshness (heuristic lifetime = 3 seconds, age = 2 seconds)
    clock.advanceSeconds(2);

    verifyThat(get(serverUri)).isCacheHit().hasBody("Mew");

    // Make response stale by 1 second (heuristic lifetime = 3 seconds, age = 4 seconds)
    clock.advanceSeconds(2);

    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(get(serverUri)).isConditionalHit().hasBody("Mew");

    var sentRequest = server.takeRequest();
    assertThat(sentRequest.getHeaders().getInstant("If-Modified-Since"))
        .isEqualTo(lastModifiedInstant);
  }

  @UseHttps // Test SSLSession persistence
  @StoreParameterizedTest
  @StoreSpec(store = DISK)
  void cachePersistence(StoreContext storeContext) throws Exception {
    setUpCache(storeContext.createAndRegisterStore());
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Eevee"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Eevee");

    cache.close();

    // Retain freshness between sessions
    clock.advanceSeconds(1);

    setUpCache(storeContext.createAndRegisterStore());
    verifyThat(get(serverUri)).isCacheHit().hasBody("Eevee").isCachedWithSsl();

    cache.close();

    // Make response stale by 1 second between sessions
    clock.advanceSeconds(1);

    setUpCache(storeContext.createAndRegisterStore());
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(get(serverUri)).isConditionalHit().hasBody("Eevee").isCachedWithSsl();
  }

  @StoreParameterizedTest
  void cacheSize(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Pikachu"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Pikachu");
    assertThat(cache.size()).isEqualTo(cache.store().size());
  }

  @StoreParameterizedTest
  void networkFailureDuringTransmission(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setBody("Jigglypuff")
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
    assertThatIOException().isThrownBy(() -> get(serverUri));

    server.enqueue(
        new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Jigglypuff"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Jigglypuff");

    // Make response stale by 1 second
    clock.advanceSeconds(2);

    // Attempted revalidation throws & cache update is discarded
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setBody("Jigglypuff")
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
    assertThatIOException().isThrownBy(() -> get(serverUri));

    // Stale cache response is still there
    var request = GET(serverUri).header("Cache-Control", "max-stale=1");
    verifyThat(get(request))
        .isCacheHit()
        .hasBody("Jigglypuff")
        .containsHeader("Age", "2")
        .containsHeader("Warning", "110 - \"Response is Stale\"");
  }

  @StoreParameterizedTest
  void prematurelyCloseResponseBody(Store store) throws Exception {
    setUpCache(store);

    // Ensure we have a body that spans multiple onNext signals
    var body = "Pikachu\n".repeat(12 * 1024);
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody(body));

    // Prematurely close the response body. This causes cache writing to continue in background.
    try (var reader = client.send(GET(serverUri), MoreBodyHandlers.ofReader()).body()) {
      var chars = new char["Pikachu".length()];
      int read = reader.read(chars);
      assertThat(read).isEqualTo(chars.length);
      assertThat(new String(chars)).isEqualTo("Pikachu");
    }

    // Wait till opened editors are closed so all writing is completed
    editAwaiter.await();
    verifyThat(get(serverUri)).isCacheHit().hasBody(body);
  }

  @StoreParameterizedTest
  void errorsWhileWritingDiscardsCaching(Store store) throws Exception {
    var failingStore = new FailingStore(store);
    failingStore.allowReads = true;
    setUpCache(failingStore);

    // Write failure is ignored & the response completes normally nevertheless.
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Pikachu"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Pikachu");
    assertNotCached(serverUri);

    // Allow the response to be cached
    failingStore.allowWrites = true;
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Pikachu"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Pikachu");
    verifyThat(get(serverUri)).isCacheHit().hasBody("Pikachu");

    // Make response stale by 1 second
    clock.advanceSeconds(2);

    // Attempted revalidation throws & cache update is discarded
    failingStore.allowWrites = false;
    server.enqueue(
        new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Charmander"));
    verifyThat(get(serverUri)).isConditionalMiss().hasBody("Charmander");

    // Stale cache response is still there
    var request = GET(serverUri).header("Cache-Control", "max-stale=1");
    verifyThat(get(request))
        .isCacheHit()
        .hasBody("Pikachu")
        .containsHeader("Age", "2")
        .containsHeader("Warning", "110 - \"Response is Stale\"");
  }

  @StoreParameterizedTest
  void errorsWhileReadingArePropagated(Store store) throws Exception {
    var failingStore = new FailingStore(store);
    failingStore.allowWrites = true;
    setUpCache(failingStore);
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Pikachu"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Pikachu");

    // Read failure is propagated
    assertThatThrownBy(() -> get(serverUri)).isInstanceOf(TestException.class);
  }

  @StoreParameterizedTest
  void uriIterator(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("a"));
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("b"));
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("c"));
    verifyThat(get(serverUri.resolve("/a"))).isCacheMiss().hasBody("a");
    verifyThat(get(serverUri.resolve("/b"))).isCacheMiss().hasBody("b");
    verifyThat(get(serverUri.resolve("/c"))).isCacheMiss().hasBody("c");

    var iter = cache.uris();
    assertThat(iter)
        .toIterable()
        .containsExactlyInAnyOrder(
            serverUri.resolve("/a"), serverUri.resolve("/b"), serverUri.resolve("/c"));
    assertThatThrownBy(iter::next).isInstanceOf(NoSuchElementException.class);
  }

  @StoreParameterizedTest
  void iteratorRemove(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("a"));
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("b"));
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("c"));
    verifyThat(get(serverUri.resolve("/a"))).isCacheMiss().hasBody("a");
    verifyThat(get(serverUri.resolve("/b"))).isCacheMiss().hasBody("b");
    verifyThat(get(serverUri.resolve("/c"))).isCacheMiss().hasBody("c");

    // Remove a & c
    var iter = cache.uris();
    assertThatThrownBy(iter::remove).isInstanceOf(IllegalStateException.class);
    while (iter.hasNext()) {
      assertThatThrownBy(iter::remove).isInstanceOf(IllegalStateException.class);

      var uri = iter.next();
      if (uri.equals(serverUri.resolve("/a")) || uri.equals(serverUri.resolve("/c"))) {
        iter.remove();
      }

      // Check hasNext prohibits removing the wrong entry as it causes the iterator to advance.
      iter.hasNext();
      assertThatIllegalStateException().isThrownBy(iter::remove);
    }

    assertNotCached(serverUri.resolve("/a"));
    assertNotCached(serverUri.resolve("/c"));
    verifyThat(get(serverUri.resolve("/b"))).isCacheHit().hasBody("b");
  }

  @StoreParameterizedTest
  void recordStats(Store store) throws Exception {
    setUpCache(store);
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest recordedRequest) {
            var path = recordedRequest.getRequestUrl().pathSegments().get(0);
            switch (path) {
              case "hit":
                return new MockResponse().setHeader("Cache-Control", "max-age=60");
              case "miss":
                return new MockResponse().setHeader("Cache-Control", "no-store");
              default:
                return fail("unexpected path: " + path);
            }
          }
        });

    var hitUri = serverUri.resolve("/hit");
    var missUri = serverUri.resolve("/miss");

    // requestCount = 1, missCount = 1, networkUseCount = 1
    verifyThat(get(hitUri)).isCacheMiss();

    // requestCount = 2, hitCount = 1
    verifyThat(get(hitUri)).isCacheHit();

    // requestCount = 3, missCount = 2, networkUseCount = 2
    verifyThat(get(missUri)).isCacheMiss();

    // requestCount = 13, missCount = 12, networkUseCount = 12
    for (int i = 0; i < 10; i++) {
      verifyThat(get(missUri)).isCacheMiss();
    }

    assertThat(cache.remove(hitUri)).isTrue();

    // requestCount = 14, missCount = 13, networkUseCount = 13
    verifyThat(get(hitUri)).isCacheMiss();

    // requestCount = 24, hitCount = 11
    for (int i = 0; i < 10; i++) {
      verifyThat(get(hitUri)).isCacheHit();
    }

    // requestCount = 25, missCount = 14 (no network)
    verifyThat(get(GET(missUri).header("Cache-Control", "only-if-cached"))).isCacheUnsatisfaction();

    var stats = cache.stats();
    assertThat(stats.requestCount()).isEqualTo(25);
    assertThat(stats.hitCount()).isEqualTo(11);
    assertThat(stats.missCount()).isEqualTo(14);
    assertThat(stats.networkUseCount()).isEqualTo(13);
    assertThat(stats.hitRate()).isEqualTo(11 / 25.0);
    assertThat(stats.missRate()).isEqualTo(14 / 25.0);
  }

  @StoreParameterizedTest
  void perUriStats(Store store) throws Exception {
    setUpCache(store, StatsRecorder.createConcurrentPerUriRecorder());
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest recordedRequest) {
            return new MockResponse().setHeader("Cache-Control", "max-age=2");
          }
        });

    var aUri = serverUri.resolve("/a");
    var bUri = serverUri.resolve("/b");

    // a.requestCount = 1, a.missCount = 1, a.networkUseCount = 1
    verifyThat(get(aUri)).isCacheMiss();

    // a.requestCount = 2, a.hitCount = 1
    verifyThat(get(aUri)).isCacheHit();

    // a.requestCount = 3, a.missCount = 2, a.networkUseCount = 2
    verifyThat(get(GET(aUri).header("Cache-Control", "no-cache"))).isConditionalMiss();

    // a.requestCount = 4, a.hitCount = 2
    verifyThat(get(aUri)).isCacheHit();

    assertThat(cache.remove(aUri)).isTrue();

    // a.requestCount = 5, a.missCount = 3, a.networkUseCount = 2 (network isn't accessed)
    verifyThat(get(GET(aUri).header("Cache-Control", "only-if-cached"))).isCacheUnsatisfaction();

    // b.requestCount = 1, b.missCount = 1, b.networkUseCount = 1
    verifyThat(get(bUri)).isCacheMiss();

    // b.requestCount = 6, b.missCount = 6, b.networkUseCount = 6
    for (int i = 0; i < 5; i++) {
      verifyThat(get(GET(bUri).header("Cache-Control", "no-cache"))).isConditionalMiss();
    }

    // b.requestCount = 7, b.hitCount = 1
    verifyThat(get(bUri)).isCacheHit();

    var aStats = cache.stats(aUri);
    assertThat(aStats.requestCount()).isEqualTo(5);
    assertThat(aStats.hitCount()).isEqualTo(2);
    assertThat(aStats.missCount()).isEqualTo(3);
    assertThat(aStats.networkUseCount()).isEqualTo(2);

    var bStats = cache.stats(bUri);
    assertThat(bStats.requestCount()).isEqualTo(7);
    assertThat(bStats.hitCount()).isEqualTo(1);
    assertThat(bStats.missCount()).isEqualTo(6);
    assertThat(bStats.networkUseCount()).isEqualTo(6);

    var untrackedUriStats = cache.stats(serverUri.resolve("/c"));
    assertThat(untrackedUriStats.requestCount()).isZero();
    assertThat(untrackedUriStats.hitCount()).isZero();
    assertThat(untrackedUriStats.missCount()).isZero();
    assertThat(untrackedUriStats.networkUseCount()).isZero();
  }

  @StoreParameterizedTest
  void writeStats(Store store) throws Exception {
    var failingStore = new FailingStore(store);
    failingStore.allowReads = true;
    failingStore.allowWrites = true;
    setUpCache(failingStore, StatsRecorder.createConcurrentPerUriRecorder());
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest recordedRequest) {
            return new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Pikachu");
          }
        });

    // writeSuccessCount = 1, a.writeSuccessCount = 1
    verifyThat(get(serverUri.resolve("/a"))).isCacheMiss();

    assertThat(cache.remove(serverUri.resolve("/a"))).isTrue();

    // writeSuccessCount = 2, a.writeSuccessCount = 2
    verifyThat(get(serverUri.resolve("/a"))).isCacheMiss();

    // writeSuccessCount = 3, b.writeSuccessCount = 1
    verifyThat(get(serverUri.resolve("/b"))).isCacheMiss();

    failingStore.allowWrites = false;

    assertThat(cache.remove(serverUri.resolve("/b"))).isTrue();

    // writeFailureCount = 1, b.writeFailureCount = 1
    verifyThat(get(serverUri.resolve("/b"))).isCacheMiss();

    // writeFailureCount = 2, c.writeFailureCount = 1
    verifyThat(get(serverUri.resolve("/c"))).isCacheMiss();

    await().pollDelay(Duration.ZERO).until(() -> cache.stats().writeSuccessCount(), isEqual(3L));
    await().pollDelay(Duration.ZERO).until(() -> cache.stats().writeFailureCount(), isEqual(2L));

    await()
        .pollDelay(Duration.ZERO)
        .until(() -> cache.stats(serverUri.resolve("/a")).writeSuccessCount(), isEqual(2L));
    await()
        .pollDelay(Duration.ZERO)
        .until(() -> cache.stats(serverUri.resolve("/a")).writeFailureCount(), isEqual(0L));

    await()
        .pollDelay(Duration.ZERO)
        .until(() -> cache.stats(serverUri.resolve("/b")).writeSuccessCount(), isEqual(1L));
    await().until(() -> cache.stats(serverUri.resolve("/b")).writeFailureCount(), isEqual(1L));

    await()
        .pollDelay(Duration.ZERO)
        .until(() -> cache.stats(serverUri.resolve("/c")).writeSuccessCount(), isEqual(0L));
    await()
        .pollDelay(Duration.ZERO)
        .until(() -> cache.stats(serverUri.resolve("/c")).writeFailureCount(), isEqual(1L));
  }

  @StoreParameterizedTest
  void disabledStatsRecorder(Store store) throws Exception {
    setUpCache(store, StatsRecorder.disabled());
    server.enqueue(new MockResponse().setBody("Pikachu"));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Pikachu");
    verifyThat(get(serverUri)).isCacheHit().hasBody("Pikachu");
    assertThat(cache.stats()).isEqualTo(Stats.empty());
    assertThat(cache.stats(serverUri)).isEqualTo(Stats.empty());
  }

  @StoreParameterizedTest
  void compressedCacheResponse(Store store) throws Exception {
    setUpCache(store);

    var gzippedBytes = gzip("Will Smith");
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("Content-Encoding", "gzip")
            .setBody(new okio.Buffer().write(gzippedBytes)));
    verifyThat(get(serverUri)).isCacheMiss().hasBody("Will Smith");
    verifyThat(get(serverUri))
        .isCacheHit()
        .hasBody("Will Smith")
        .doesNotContainHeader("Content-Encoding")
        .doesNotContainHeader("Content-Length");
  }

  @StoreParameterizedTest
  void requestResponseListener(Store store) throws Exception {
    var listener = new RecordingListener(EventType.REQUEST_RESPONSE);
    setUpCache(store, null, listener);

    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Pikachu"));
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Eevee"));

    var request = GET(serverUri).tag(Integer.class, 1);
    get(request);
    listener.assertNext(OnRequest.class, request);
    listener
        .assertNext(OnNetworkUse.class, request)
        .extracting(event -> event.cacheResponse)
        .isNull();
    listener
        .assertNext(OnResponse.class, request)
        .satisfies(event -> verifyThat(event.response).isCacheMiss());

    get(request);
    listener.assertNext(OnRequest.class, request);
    listener
        .assertNext(OnResponse.class, request)
        .satisfies(event -> verifyThat(event.response).isCacheHit());

    // Make response stale
    clock.advanceSeconds(2);

    get(request);
    listener.assertNext(OnRequest.class, request);
    listener
        .assertNext(OnNetworkUse.class, request)
        .extracting(event -> event.cacheResponse)
        .isNotNull();
    listener
        .assertNext(OnResponse.class, request)
        .satisfies(event -> verifyThat(event.response).isConditionalHit());

    // Make response stale
    clock.advanceSeconds(2);

    get(request);
    listener.assertNext(OnRequest.class, request);
    listener
        .assertNext(OnNetworkUse.class, request)
        .extracting(event -> event.cacheResponse)
        .isNotNull();
    listener
        .assertNext(OnResponse.class, request)
        .satisfies(event -> verifyThat(event.response).isConditionalMiss());

    // Make response stale
    clock.advanceSeconds(2);

    get(request.header("Cache-Control", "only-if-cached"));
    listener.assertNext(OnRequest.class, request);
    listener
        .assertNext(OnResponse.class, request)
        .satisfies(event -> verifyThat(event.response).isCacheUnsatisfaction());
  }

  @StoreParameterizedTest
  void readWriteListener(Store store) throws Exception {
    var listener = new RecordingListener(EventType.READ_WRITE);
    var failingStore = new FailingStore(store);
    setUpCache(failingStore, null, listener);

    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Pikachu"));
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Pikachu"));

    var request = GET(serverUri);
    get(request);
    await().pollDelay(Duration.ZERO).until(() -> !listener.events.isEmpty());
    listener
        .assertNext(OnWriteFailure.class, request)
        .extracting(
            event -> Utils.getDeepCompletionCause(event.error)) // Can be a CompletionException
        .isInstanceOf(TestException.class);

    failingStore.allowWrites = true;
    get(request);
    await().pollDelay(Duration.ZERO).until(() -> !listener.events.isEmpty());
    listener.assertNext(OnWriteSuccess.class, request);

    assertThatExceptionOfType(TestException.class).isThrownBy(() -> get(request));
    listener
        .assertNext(OnReadFailure.class, request)
        .extracting(
            event -> Utils.getDeepCompletionCause(event.error)) // Can be a CompletionException
        .isInstanceOf(TestException.class);

    failingStore.allowReads = true;
    get(request);
    listener.assertNext(OnReadSuccess.class, request);
  }

  static final class RecordingListener implements Listener {
    final EventType toRecord;
    final Queue<Event> events = new ConcurrentLinkedQueue<>();

    enum EventType {
      READ_WRITE,
      REQUEST_RESPONSE
    }

    RecordingListener(EventType toRecord) {
      this.toRecord = toRecord;
    }

    <T extends Event> ObjectAssert<T> assertNext(Class<T> expected) {
      return assertThat(events.poll()).asInstanceOf(InstanceOfAssertFactories.type(expected));
    }

    <T extends Event> ObjectAssert<T> assertNext(Class<T> expected, TaggableRequest request) {
      return assertNext(expected)
          .satisfies(
              event -> {
                verifyThat(event.request).hasUri(request.uri()).containsHeaders(request.headers());

                // Make sure tags aren't lost
                assertThat(TaggableRequest.from(event.request).tags())
                    .containsAllEntriesOf(request.tags());
              });
    }

    @Override
    public void onRequest(HttpRequest request) {
      if (toRecord == EventType.REQUEST_RESPONSE) {
        events.add(new OnRequest(request));
      }
    }

    @Override
    public void onNetworkUse(HttpRequest request, @Nullable TrackedResponse<?> cacheResponse) {
      if (toRecord == EventType.REQUEST_RESPONSE) {
        events.add(new OnNetworkUse(request, cacheResponse));
      }
    }

    @Override
    public void onResponse(HttpRequest request, CacheAwareResponse<?> response) {
      if (toRecord == EventType.REQUEST_RESPONSE) {
        events.add(new OnResponse(request, response));
      }
    }

    @Override
    public void onReadSuccess(HttpRequest request) {
      if (toRecord == EventType.READ_WRITE) {
        events.add(new OnReadSuccess(request));
      }
    }

    @Override
    public void onReadFailure(HttpRequest request, Throwable exception) {
      if (toRecord == EventType.READ_WRITE) {
        events.add(new OnReadFailure(request, exception));
      }
    }

    @Override
    public void onWriteSuccess(HttpRequest request) {
      if (toRecord == EventType.READ_WRITE) {
        events.add(new OnWriteSuccess(request));
      }
    }

    @Override
    public void onWriteFailure(HttpRequest request, Throwable exception) {
      if (toRecord == EventType.READ_WRITE) {
        events.add(new OnWriteFailure(request, exception));
      }
    }

    static class Event {
      final HttpRequest request;

      Event(HttpRequest request) {
        this.request = request;
      }
    }

    static final class OnRequest extends Event {
      OnRequest(HttpRequest request) {
        super(request);
      }
    }

    static final class OnNetworkUse extends Event {
      final @Nullable TrackedResponse<?> cacheResponse;

      OnNetworkUse(HttpRequest request, @Nullable TrackedResponse<?> cacheResponse) {
        super(request);
        this.cacheResponse = cacheResponse;
      }
    }

    static final class OnResponse extends Event {
      final CacheAwareResponse<?> response;

      OnResponse(HttpRequest request, CacheAwareResponse<?> response) {
        super(request);
        this.response = response;
      }
    }

    static final class OnReadSuccess extends Event {
      OnReadSuccess(HttpRequest request) {
        super(request);
      }
    }

    static final class OnReadFailure extends Event {
      final Throwable error;

      OnReadFailure(HttpRequest request, Throwable error) {
        super(request);
        this.error = error;
      }
    }

    static final class OnWriteSuccess extends Event {
      OnWriteSuccess(HttpRequest request) {
        super(request);
      }
    }

    static final class OnWriteFailure extends Event {
      final Throwable error;

      OnWriteFailure(HttpRequest request, Throwable error) {
        super(request);
        this.error = error;
      }
    }
  }

  private HttpResponse<String> get(URI uri) throws IOException, InterruptedException {
    return get(GET(uri));
  }

  private HttpResponse<String> getUnchecked(HttpRequest request) {
    try {
      return get(request);
    } catch (IOException | InterruptedException e) {
      return Assertions.fail(e);
    }
  }

  private HttpResponse<String> get(HttpRequest request) throws IOException, InterruptedException {
    var response = client.send(request, BodyHandlers.ofString());
    editAwaiter.await();
    return response;
  }

  private HttpResponse<String> getWithPushHandler(URI uri) {
    var response =
        client
            .sendAsync(
                GET(uri),
                BodyHandlers.ofString(),
                PushPromiseHandler.of(__ -> BodyHandlers.ofString(), new ConcurrentHashMap<>()))
            .join();
    editAwaiter.await();
    return response;
  }

  /**
   * Ensures requests to serverUri result in a cache hit, retrying if necessary in case a stale
   * response is being updated in background.
   */
  private HttpResponse<String> awaitCacheHit() {
    var request = GET(serverUri).header("Cache-Control", "max-stale=0, only-if-cached");
    return await()
        .atMost(Duration.ofMinutes(2))
        .until(
            () -> get(request),
            response -> ((CacheAwareResponse<String>) response).cacheStatus() == HIT);
  }

  private void assertNotCached(URI uri) throws Exception {
    assertNotCached(GET(uri));
  }

  private void assertNotCached(MutableRequest request) throws Exception {
    var cacheControl = CacheControl.newBuilder().onlyIfCached().anyMaxStale().build();
    verifyThat(get(request.cacheControl(cacheControl))).isCacheUnsatisfaction();

    var dispatcher = server.getDispatcher();
    try {
      server.setDispatcher(new QueueDispatcher());
      server.enqueue(new MockResponse().setResponseCode(HTTP_INTERNAL_ERROR));
      verifyThat(get(request.copy().removeHeader("Cache-Control")))
          .hasCode(HTTP_INTERNAL_ERROR)
          .isCacheMiss();
    } finally {
      server.setDispatcher(dispatcher);
    }
  }

  private static LocalDateTime toUtcDateTime(Instant instant) {
    return LocalDateTime.ofInstant(instant, UTC);
  }

  private static String formatInstant(Instant instant) {
    return toHttpDateString(toUtcDateTime(instant));
  }

  private static class ForwardingStore implements Store {
    final Store delegate;

    ForwardingStore(Store delegate) {
      this.delegate = delegate;
    }

    @Override
    public long maxSize() {
      return delegate.maxSize();
    }

    @Override
    public Optional<Executor> executor() {
      return delegate.executor();
    }

    @Override
    public void initialize() throws IOException {
      delegate.initialize();
    }

    @Override
    public CompletableFuture<Void> initializeAsync() {
      return delegate.initializeAsync();
    }

    @Override
    public Optional<Viewer> view(String key) throws IOException, InterruptedException {
      return delegate.view(key);
    }

    @Override
    public CompletableFuture<Optional<Viewer>> viewAsync(String key) {
      return delegate.viewAsync(key);
    }

    @Override
    public Optional<Editor> edit(String key) throws IOException, InterruptedException {
      return delegate.edit(key);
    }

    @Override
    public CompletableFuture<Optional<Editor>> editAsync(String key) {
      return delegate.editAsync(key);
    }

    @Override
    public CompletableFuture<Void> removeAllAsync(List<String> keys) {
      return delegate.removeAllAsync(keys);
    }

    @Override
    public Iterator<Viewer> iterator() throws IOException {
      return delegate.iterator();
    }

    @Override
    public boolean remove(String key) throws IOException, InterruptedException {
      return delegate.remove(key);
    }

    @Override
    public void clear() throws IOException {
      delegate.clear();
    }

    @Override
    public long size() throws IOException {
      return delegate.size();
    }

    @Override
    public void dispose() throws IOException {
      delegate.dispose();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }

    @Override
    public void flush() throws IOException {
      delegate.flush();
    }
  }

  private static class ForwardingEditor implements Editor {
    private final Editor delegate;

    ForwardingEditor(Editor delegate) {
      this.delegate = delegate;
    }

    @Override
    public String key() {
      return delegate.key();
    }

    @Override
    public EntryWriter writer() {
      return delegate.writer();
    }

    @Override
    public CompletableFuture<Boolean> commitAsync(ByteBuffer metadata) {
      return delegate.commitAsync(metadata);
    }

    @Override
    public void close() {
      delegate.close();
    }
  }

  private static class ForwardingViewer implements Viewer {
    final Viewer delegate;

    ForwardingViewer(Viewer delegate) {
      this.delegate = delegate;
    }

    @Override
    public String key() {
      return delegate.key();
    }

    @Override
    public ByteBuffer metadata() {
      return delegate.metadata();
    }

    @Override
    public EntryReader newReader() {
      return delegate.newReader();
    }

    @Override
    public long dataSize() {
      return delegate.dataSize();
    }

    @Override
    public long entrySize() {
      return delegate.entrySize();
    }

    @Override
    public CompletableFuture<Optional<Editor>> editAsync() {
      return delegate.editAsync();
    }

    @Override
    public boolean removeEntry() throws IOException {
      return delegate.removeEntry();
    }

    @Override
    public void close() {
      delegate.close();
    }
  }

  /**
   * Awaits ongoing edits to be completed. By design, {@link
   * com.github.mizosoft.methanol.internal.cache.CacheWritingPublisher} doesn't make downstream
   * completion wait for the whole body to be written. So if writes take time, the response entry is
   * committed a while after the response is completed. This however agitates tests as they expect
   * things to happen sequentially. This is solved by waiting for all open editors to close after a
   * client.send(...) is issued.
   */
  private static final class EditAwaiter {
    private final Phaser phaser = new Phaser(1); // Register self.

    EditAwaiter() {}

    Editor register(Editor editor) {
      return new NotifyingEditor(editor, phaser);
    }

    void await() {
      try {
        phaser.awaitAdvanceInterruptibly(phaser.arrive(), 20, TimeUnit.SECONDS);
      } catch (InterruptedException | TimeoutException e) {
        fail("timed out while waiting for editors to be closed", e);
      }
    }

    /** An Editor that notifies (arrives at) a Phaser when closed or committed. */
    private static final class NotifyingEditor extends ForwardingEditor {
      private final Phaser phaser;

      NotifyingEditor(Editor delegate, Phaser phaser) {
        super(delegate);
        this.phaser = phaser;
        requireState(phaser.register() >= 0, "phaser terminated");
      }

      @Override
      public CompletableFuture<Boolean> commitAsync(ByteBuffer metadata) {
        return super.commitAsync(metadata).whenComplete((__, ___) -> phaser.arriveAndDeregister());
      }

      @Override
      public void close() {
        try {
          super.close();
        } finally {
          phaser.arriveAndDeregister();
        }
      }
    }
  }

  private static final class EditAwaiterStore extends ForwardingStore {
    private final EditAwaiter editAwaiter;

    EditAwaiterStore(Store delegate, EditAwaiter editAwaiter) {
      super(delegate);
      this.editAwaiter = editAwaiter;
    }

    @Override
    public Optional<Viewer> view(String key) throws IOException, InterruptedException {
      return super.view(key).map(EditAwaiterViewer::new);
    }

    @Override
    public CompletableFuture<Optional<Viewer>> viewAsync(String key) {
      return super.viewAsync(key).thenApply(viewer -> viewer.map(EditAwaiterViewer::new));
    }

    @Override
    public Optional<Editor> edit(String key) throws IOException, InterruptedException {
      return super.edit(key).map(editAwaiter::register);
    }

    @Override
    public CompletableFuture<Optional<Editor>> editAsync(String key) {
      return super.editAsync(key).thenApply(editor -> editor.map(editAwaiter::register));
    }

    private final class EditAwaiterViewer extends ForwardingViewer {
      EditAwaiterViewer(Viewer delegate) {
        super(delegate);
      }

      @Override
      public CompletableFuture<Optional<Editor>> editAsync() {
        return super.editAsync().thenApply(editor -> editor.map(editAwaiter::register));
      }
    }
  }

  private static final class FailingStore extends ForwardingStore {
    volatile boolean allowReads = false;
    volatile boolean allowWrites = false;

    FailingStore(Store delegate) {
      super(delegate);
    }

    @Override
    public Optional<Viewer> view(String key) throws IOException, InterruptedException {
      return super.view(key).map(FailingViewer::new);
    }

    @Override
    public CompletableFuture<Optional<Viewer>> viewAsync(String key) {
      return super.viewAsync(key).thenApply(viewer -> viewer.map(FailingViewer::new));
    }

    @Override
    public Optional<Editor> edit(String key) throws IOException, InterruptedException {
      return super.edit(key).map(FailingEditor::new);
    }

    @Override
    public CompletableFuture<Optional<Editor>> editAsync(String key) {
      return super.editAsync(key).thenApply(editor -> editor.map(FailingEditor::new));
    }

    private final class FailingEditor extends ForwardingEditor {
      private volatile boolean committed;

      FailingEditor(Editor delegate) {
        super(delegate);
      }

      @Override
      public EntryWriter writer() {
        var delegate = super.writer();
        return src -> {
          // To simulate delays, fire an actual read on delegate even if reading is prohibited.
          return delegate
              .write(src)
              .thenCompose(
                  read ->
                      allowWrites
                          ? CompletableFuture.completedFuture(read)
                          : CompletableFuture.failedFuture(new TestException()));
        };
      }

      @Override
      public CompletableFuture<Boolean> commitAsync(ByteBuffer metadata) {
        committed = true;
        return super.commitAsync(metadata);
      }

      @Override
      public void close() {
        super.close();
        if (committed && !allowWrites) {
          fail("edit is committed despite prohibited writes");
        }
      }
    }

    private final class FailingViewer extends ForwardingViewer {
      FailingViewer(Viewer delegate) {
        super(delegate);
      }

      @Override
      public EntryReader newReader() {
        var delegate = super.newReader();
        return dst -> {
          // To simulate delays, fire an actual write on delegate even if writing is prohibited.
          return delegate
              .read(dst)
              .thenCompose(
                  read ->
                      allowReads
                          ? CompletableFuture.completedFuture(read)
                          : CompletableFuture.failedFuture(new TestException()));
        };
      }

      @Override
      public CompletableFuture<Optional<Editor>> editAsync() {
        return super.editAsync().thenApply(editor -> editor.map(FailingEditor::new));
      }
    }
  }
}
