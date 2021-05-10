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

package com.github.mizosoft.methanol;

import static com.github.mizosoft.methanol.CacheAwareResponse.CacheStatus.HIT;
import static com.github.mizosoft.methanol.MutableRequest.GET;
import static com.github.mizosoft.methanol.internal.cache.DateUtils.formatHttpDate;
import static com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType.FIXED_POOL;
import static com.github.mizosoft.methanol.testing.StoreConfig.FileSystemType.SYSTEM;
import static com.github.mizosoft.methanol.testing.StoreConfig.StoreType.DISK;
import static com.github.mizosoft.methanol.testutils.ResponseVerifier.verifying;
import static com.github.mizosoft.methanol.testutils.TestUtils.deflate;
import static com.github.mizosoft.methanol.testutils.TestUtils.gzip;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static java.time.Duration.ofDays;
import static java.time.Duration.ofHours;
import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static java.time.ZoneOffset.UTC;
import static java.util.function.Predicate.isEqual;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

import com.github.mizosoft.methanol.HttpCache.Stats;
import com.github.mizosoft.methanol.HttpCache.StatsRecorder;
import com.github.mizosoft.methanol.Methanol.Interceptor;
import com.github.mizosoft.methanol.internal.cache.CacheWritingPublisher;
import com.github.mizosoft.methanol.internal.cache.DiskStore;
import com.github.mizosoft.methanol.internal.cache.MemoryStore;
import com.github.mizosoft.methanol.internal.cache.Store;
import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorConfig;
import com.github.mizosoft.methanol.testing.MockWebServerExtension;
import com.github.mizosoft.methanol.testing.MockWebServerExtension.UseHttps;
import com.github.mizosoft.methanol.testing.StoreConfig;
import com.github.mizosoft.methanol.testing.StoreContext;
import com.github.mizosoft.methanol.testing.StoreExtension;
import com.github.mizosoft.methanol.testing.StoreExtension.StoreParameterizedTest;
import com.github.mizosoft.methanol.testutils.Logging;
import com.github.mizosoft.methanol.testutils.MockClock;
import com.github.mizosoft.methanol.testutils.ResponseVerifier;
import com.github.mizosoft.methanol.testutils.TestException;
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
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.ThrowingConsumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(value = 10, unit = TimeUnit.MINUTES)
@ExtendWith({MockWebServerExtension.class, StoreExtension.class, ExecutorExtension.class})
class HttpCacheTest {
  static {
    Logging.disable(HttpCache.class, DiskStore.class, CacheWritingPublisher.class);
  }

  private Executor threadPool;
  private Methanol.Builder clientBuilder;
  private Methanol client;
  private MockWebServer server;
  private URI serverUri;
  private MockClock clock;
  private EditAwaiter editAwaiter;
  private HttpCache cache;

  @BeforeEach
  @ExecutorConfig(FIXED_POOL)
  void setUp(Executor threadPool, Methanol.Builder builder, MockWebServer server) {
    this.threadPool = threadPool;
    this.clientBuilder = builder.executor(threadPool);
    this.server = server;
    serverUri = server.url("/").uri();
    clock = new MockClock();

    ((QueueDispatcher) server.getDispatcher())
        .setFailFast(new MockResponse().setResponseCode(HTTP_UNAVAILABLE));
  }

  private void setUpCache(Store store) {
    setUpCache(store, null);
  }

  private void setUpCache(Store store, @Nullable StatsRecorder statsRecorder) {
    editAwaiter = new EditAwaiter();

    var cacheBuilder = HttpCache.newBuilder()
        .clockForTesting(clock)
        .storeForTesting(new EditAwaiterStore(store, editAwaiter))
        .executor(threadPool);
    if (statsRecorder != null) {
      cacheBuilder.statsRecorder(statsRecorder);
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
    var cache = HttpCache.newBuilder()
        .cacheOnMemory(12)
        .build();
    var store = cache.storeForTesting();
    assertThat(store).isInstanceOf(MemoryStore.class);
    assertThat(store.maxSize()).isEqualTo(12);
    assertThat(store.executor()).isEmpty();
    assertThat(cache.directory()).isEmpty();
    assertThat(cache.size()).isZero();
  }

  @Test
  void buildWithDiskStore() {
    var cache = HttpCache.newBuilder()
        .cacheOnDisk(Path.of("cache_dir"), 12)
        .executor(r -> { throw new RejectedExecutionException("NO!"); })
        .build();
    var store = cache.storeForTesting();
    assertThat(store).isInstanceOf(DiskStore.class);
    assertThat(store.maxSize()).isEqualTo(12);
    assertThat(cache.directory()).hasValue(Path.of("cache_dir"));
    assertThat(cache.executor()).isEqualTo(store.executor());
  }

  @StoreParameterizedTest
  void cacheGetWithMaxAge(Store store) throws Exception {
    setUpCache(store);
    assertGetIsCached(ofSeconds(1), "Cache-Control", "max-age=2");
  }

  @StoreParameterizedTest
  void cacheGetWithExpires(Store store) throws Exception {
    setUpCache(store);
    var now = toUtcDateTime(clock.instant());
    assertGetIsCached(
        ofHours(12),                      // Advance clock half a day
        "Expires",
        formatHttpDate(now.plusDays(1))); // Expire a day from "now"
  }

  @StoreParameterizedTest
  void cacheGetWithExpiresAndDate(Store store) throws Exception {
    setUpCache(store);
    var date = toUtcDateTime(clock.instant());
    assertGetIsCached(
        ofDays(1),                         // Advance clock a day (retain freshness)
        "Date",
        formatHttpDate(date),
        "Expires",
        formatHttpDate(date.plusDays(1))); // Expire a day from date
  }

  @StoreParameterizedTest
  @UseHttps
  void cacheSecureGetWithMaxAge(Store store) throws Exception {
    setUpCache(store);
    assertGetIsCached(ofSeconds(1), "Cache-Control", "max-age=2")
        .assertCachedWithSsl();
  }

  private ResponseVerifier<String> assertGetIsCached(
      Duration clockAdvance, String... headers)
      throws Exception {
    server.enqueue(new MockResponse()
        .setHeaders(Headers.of(headers))
        .setBody("Pikachu"));

    get(serverUri)
        .assertMiss()
        .assertBody("Pikachu");

    clock.advance(clockAdvance);

    return get(serverUri)
        .assertHit()
        .assertBody("Pikachu");
  }

  @StoreParameterizedTest
  void cacheGetWithExpiresConditionalHit(Store store) throws Exception {
    setUpCache(store);
    // Expire one day from "now"
    var oneDayFromNow = clock.instant().plus(ofDays(1));
    server.enqueue(new MockResponse()
        .addHeader("Expires", formatInstant(oneDayFromNow))
        .setBody("Pikachu"));
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));

    get(serverUri)
        .assertMiss()
        .assertBody("Pikachu");

    clock.advance(ofDays(2)); // Make response stale

    get(serverUri)
        .assertConditionalHit()
        .assertBody("Pikachu");
  }

  @StoreParameterizedTest
  @UseHttps
  void secureCacheGetWithExpiresConditionalHit(Store store) throws Exception {
    setUpCache(store);
    // Expire one day from "now"
    var oneDayFromNow = clock.instant().plus(ofDays(1));
    server.enqueue(new MockResponse()
        .addHeader("Expires", formatInstant(oneDayFromNow))
        .setBody("Pickachu"));
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));

    get(serverUri)
        .assertMiss()
        .assertBody("Pickachu");

    clock.advance(ofDays(2)); // Make response stale

    get(serverUri)
        .assertConditionalHit()
        .assertBody("Pickachu")
        .assertCachedWithSsl();
  }

  @StoreParameterizedTest
  void responseIsFreshenedOnConditionalHit(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        // Warning 113 will be stored (e.g. may come from a proxy's cache)
        // but will be removed on freshening
        .addHeader("Warning", "113 - \"Heuristic Expiration\"")
        .addHeader("X-Version", "v1")
        .addHeader("Content-Type", "text/plain")
        .addHeader("Cache-Control", "max-age=1")
        .setBody("Jigglypuff"));
    server.enqueue(new MockResponse()
        .setResponseCode(HTTP_NOT_MODIFIED)
        .addHeader("X-Version", "v2"));
    seedCache(serverUri);

    clock.advanceSeconds(2); // Make response stale

    var instantRevalidationSentAndReceived = clock.instant();
    get(serverUri)
        .assertConditionalHit()
        .assertBody("Jigglypuff");

    // Check that response metadata is updated, which is done in
    // background, so keep trying a number of times.
    awaitCacheHit()
        .assertHit()
        .assertBody("Jigglypuff")
        .assertHeader("X-Version", "v2")
        .assertRequestSentAt(instantRevalidationSentAndReceived)
        .assertResponseReceivedAt(instantRevalidationSentAndReceived);
  }

  @StoreParameterizedTest
  void successfulRevalidationWithZeroContentLength(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("X-Version", "v1")
        .addHeader("Cache-Control", "max-age=1")
        .setBody("Pickachu"));
    server.enqueue(new MockResponse()
        .setResponseCode(HTTP_NOT_MODIFIED)
        .addHeader("X-Version", "v2")
        .setHeader("Content-Length", "0")); // This is wrong, but some servers do it
    seedCache(serverUri);

    clock.advanceSeconds(2); // Make response stale

    // The 304 response has 0 Content-Length, but it isn't use to replace that
    // of the stored response.
    var instantRevalidationSentAndReceived = clock.instant();
    get(serverUri)
        .assertConditionalHit()
        .assertBody("Pickachu")
        .assertHeader("X-Version", "v2")
        .assertHeader("Content-Length", "Pickachu".length())
        .networkResponse()
        .assertHeader("Content-Length", "0");

    awaitCacheHit()
        .assertHit()
        .assertBody("Pickachu")
        .assertHeader("X-Version", "v2")
        .assertHeader("Content-Length", "Pickachu".length())
        .assertRequestSentAt(instantRevalidationSentAndReceived)
        .assertResponseReceivedAt(instantRevalidationSentAndReceived);
  }

  @StoreParameterizedTest
  void prohibitNetworkOnRequiredValidation(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("123"));
    seedCache(serverUri);

    clock.advanceSeconds(2); // Make response stale

    get(GET(serverUri).header("Cache-Control", "only-if-cached"))
        .assertUnsatisfiable()
        .assertBody(""); // Doesn't have a body
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
    testForEachValidator(storeContext, config -> {
      var request = GET(serverUri);
      assertRevalidation(request, config, true);
    });
  }

  @StoreParameterizedTest
  void failedRevalidationFromStale(StoreContext storeContext) throws Throwable {
    testForEachValidator(storeContext, config -> {
      var request = GET(serverUri);
      assertFailedRevalidation(request, config, true);
    });
  }

  @StoreParameterizedTest
  void revalidationForcedByNoCache(StoreContext storeContext) throws Throwable {
    testForEachValidator(storeContext, config -> {
      var request = GET(serverUri).header("Cache-Control", "no-cache");
      assertRevalidation(request, config, false);
    });
  }

  @StoreParameterizedTest
  void failedRevalidationForcedByNoCache(StoreContext storeContext) throws Throwable {
    testForEachValidator(storeContext, config -> {
      var request = GET(serverUri).header("Cache-Control", "no-cache");
      assertFailedRevalidation(request, config, false);
    });
  }

  private void testForEachValidator(
      StoreContext storeContext, ThrowingConsumer<ValidatorConfig> tester) throws Throwable {
    for (var config : ValidatorConfig.values()) {
      try {
        setUpCache(storeContext.newStore());
        tester.accept(config);

        // Clean workspace for next config
        storeContext.drainQueuedTasks();
        cache.dispose();
      } catch (AssertionError failed) {
        fail(config.toString(), failed);
      }
    }
  }

  private void assertRevalidation(
      HttpRequest triggeringRequest,
      ValidatorConfig config,
      boolean makeStale) throws IOException, InterruptedException {
    var validators = config.getValidators(1, clock.instant());
    clock.advanceSeconds(1); // Make Last-Modified 1 second prior to "now"

    server.enqueue(new MockResponse()
        .setHeaders(validators)
        .addHeader("Cache-Control", "max-age=2")
        .addHeader("X-Version", "v1")
        .setBody("STONKS!"));
    server.enqueue(new MockResponse()
        .setResponseCode(HTTP_NOT_MODIFIED)
        .addHeader("X-Version", "v2"));

    var timeInitiallyReceived = clock.instant(); // First response is received at this tick
    seedCache(serverUri)
        .assertBody("STONKS!")
        .assertHeader("X-Version", "v1");
    server.takeRequest(); // Remove initial request

    clock.advanceSeconds(makeStale ? 3 : 1); // Make stale or retain freshness

    get(triggeringRequest)
        .assertBody("STONKS!")
        .assertHeader("X-Version", "v2");

    var sentRequest = server.takeRequest();
    // Time received is used if Last-Modified is absent
    var effectiveLastModified =
        config.lastModified ? validators.getInstant("Last-Modified") : timeInitiallyReceived;
    assertThat(sentRequest.getHeaders().getInstant("If-Modified-Since"))
        .isEqualTo(effectiveLastModified);
    if (config.etag) {
      assertThat(sentRequest.getHeader("If-None-Match")).isEqualTo("1");
    }
  }

  private void assertFailedRevalidation(
      HttpRequest triggeringRequest,
      ValidatorConfig config,
      boolean makeStale) throws IOException, InterruptedException {
    var validators1 = config.getValidators(1, clock.instant());
    clock.advanceSeconds(1);

    // Use different etag and Last-Modified for the revalidation response
    var validators2 = config.getValidators(2, clock.instant());
    clock.advanceSeconds(1);

    server.enqueue(new MockResponse()
        .setHeaders(validators1)
        .addHeader("Cache-Control", "max-age=2")
        .addHeader("X-Version", "v1")
        .setBody("STONKS!"));
    server.enqueue(new MockResponse()
        .setHeaders(validators2)
        .addHeader("Cache-Control", "max-age=2")
        .addHeader("X-Version", "v2")
        .setBody("DOUBLE STONKS!"));

    var instantInitiallyReceived = clock.instant(); // First response is received at this tick
    seedCache(serverUri)
        .assertBody("STONKS!")
        .assertHeader("X-Version", "v1");
    server.takeRequest(); // Remove initial request

    clock.advanceSeconds(makeStale ? 3 : 1); // Make stale or retain freshness

    get(triggeringRequest)
        .assertConditionalMiss()
        .assertBody("DOUBLE STONKS!")
        .assertHeader("X-Version", "v2")
        .assertHasHeaders(validators2.toMultimap())
        .cacheResponse() // This is the invalidated cache response
        .assertHeader("X-Version", "v1")
        .assertHasHeaders(validators1.toMultimap());

    clock.advanceSeconds(1); // Retain updated response's freshness

    get(serverUri)
        .assertHit()
        .assertBody("DOUBLE STONKS!")
        .assertHeader("X-Version", "v2")
        .assertHasHeaders(validators2.toMultimap());

    var sentRequest = server.takeRequest();
    // Time received is used if Last-Modified is absent
    var effectiveLastModified =
        config.lastModified ? validators1.getInstant("Last-Modified") : instantInitiallyReceived;
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
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("ETag", "1")
        .addHeader("Last-Modified", lastModifiedString));
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    seedCache(serverUri);

    clock.advanceSeconds(2); // Make response stale by 1 second

    // Precondition fields aren't visible on the served response's request.
    // The preconditions are however visible from the network response's request.
    get(serverUri)
        .assertConditionalHit()
        .assertAbsentRequestHeader("If-None-Match")
        .assertAbsentRequestHeader("If-Modified-Since")
        .networkResponse()
        .assertRequestHeader("If-None-Match", "1")
        .assertRequestHeader("If-Modified-Since", lastModifiedString);
  }

  @StoreParameterizedTest
  void lastModifiedDefaultsToDateWhenRevalidating(Store store) throws Exception {
    setUpCache(store);
    var dateInstant = clock.instant();
    clock.advanceSeconds(1);

    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Date", formatInstant(dateInstant))
        .setBody("FLUX"));
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    seedCache(serverUri);
    server.takeRequest(); // Remove initial request

    clock.advanceSeconds(2); // Make stale

    get(serverUri)
        .assertConditionalHit()
        .assertBody("FLUX");

    var sentRequest = server.takeRequest();
    assertThat(sentRequest.getHeaders().getInstant("If-Modified-Since"))
        .isEqualTo(dateInstant);
  }

  @StoreParameterizedTest
  void pastExpires(Store store) throws Exception {
    setUpCache(store);

    var date = toUtcDateTime(clock.instant());
    server.enqueue(new MockResponse()
        .addHeader("Date", formatHttpDate(date))
        .addHeader("Expires", formatHttpDate(date.minusSeconds(10)))
        .setBody("Pickachu"));
    seedCache(serverUri);

    // Negative freshness lifetime caused by past Expires triggers revalidation
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("Psyduck"));
    get(serverUri)
        .assertConditionalMiss()
        .assertBody("Psyduck");

    get(serverUri)
        .assertHit()
        .assertBody("Psyduck");
  }

  @StoreParameterizedTest
  void futureLastModified(Store store) throws Exception {
    setUpCache(store);

    var date = toUtcDateTime(clock.instant());
    var lastModified = date.plusSeconds(10);
    // Don't include explicit freshness to trigger heuristics, which relies on Last-Modified
    server.enqueue(new MockResponse()
        .addHeader("Date", formatHttpDate(date))
        .addHeader("Last-Modified", formatHttpDate(lastModified))
        .setBody("Pickachu"));
    seedCache(serverUri);

    // Negative heuristic lifetime caused by future Last-Modified triggers revalidation
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("Psyduck"));
    get(serverUri)
        .assertConditionalMiss()
        .assertBody("Psyduck")
        .networkResponse()
        .assertRequestHeader("If-Modified-Since", formatHttpDate(lastModified));

    get(serverUri)
        .assertHit()
        .assertBody("Psyduck");
  }

  @StoreParameterizedTest
  void relaxMaxAgeWithRequest(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("tesla"));
    seedCache(serverUri);

    clock.advanceSeconds(2); // Make response stale

    // Relaxed max-age retains freshness
    var request = GET(serverUri).header("Cache-Control", "max-age=2");
    get(request)
        .assertHit()
        .assertBody("tesla");
  }

  @StoreParameterizedTest
  void constrainMaxAgeWithRequest(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=2")
        .setBody("tesla"));
    seedCache(serverUri);

    clock.advanceSeconds(2); // Retain freshness

    get(serverUri).assertHit();

    // Constrain max-age so that the response is stale
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    var request = GET(serverUri).header("Cache-Control", "max-age=1");
    get(request)
        .assertConditionalHit()
        .assertBody("tesla");
  }

  @StoreParameterizedTest
  void constrainFreshnessWithMinFresh(Store store) throws Exception {
    setUpCache(store);
    // Last-Modified: 2 seconds from "now"
    var lastModifiedInstant = clock.instant();
    clock.advanceSeconds(2);

    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=3")
        .addHeader("Last-Modified", formatInstant(lastModifiedInstant))
        .setBody("spaceX"));
    seedCache(serverUri);
    server.takeRequest(); // Drop request

    clock.advanceSeconds(1); // Retain freshness (lifetime = 2 secs)

    get(serverUri).assertHit();

    var request1 = GET(serverUri).header("Cache-Control", "min-fresh=2");
    get(request1) // min-fresh satisfied
        .assertHit()
        .assertBody("spaceX");

    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=3")
        .addHeader("Last-Modified", formatInstant(lastModifiedInstant))
        .setBody("tesla"));
    var request2 = GET(serverUri).header("Cache-Control", "min-fresh=3");
    get(request2) // min-fresh unsatisfied
        .assertConditionalMiss()
        .assertBody("tesla");

    var sentRequest = server.takeRequest();
    assertThat(sentRequest.getHeaders().getInstant("If-Modified-Since"))
        .isEqualTo(lastModifiedInstant);
  }

  @StoreParameterizedTest
  void acceptingStalenessWithMaxStale(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("stale on a scale"));
    seedCache(serverUri);

    clock.advanceSeconds(3); // Make stale by 2 seconds

    BiConsumer<CacheControl, UnaryOperator<ResponseVerifier<String>>> assertStaleness =
        (cacheControl, cacheStatusAssert) -> {
          var request = GET(serverUri).cacheControl(cacheControl);
          var response = cacheStatusAssert.apply(getUnchecked(request))
              .assertBody("stale on a scale");
          // Must put a warning only if not revalidated
          if (response.getCacheAware().cacheStatus() == HIT) {
            response.assertHeader("Warning", "110 - \"Response is Stale\"");
          } else {
            response.assertAbsentHeader("Warning");
          }
        };

    // Allow any staleness -> HIT
    assertStaleness.accept(CacheControl.parse("max-stale"), ResponseVerifier::assertHit);

    // Allow 3 seconds of staleness -> HIT
    assertStaleness.accept(CacheControl.parse("max-stale=3"), ResponseVerifier::assertHit);

    // Allow 2 seconds of staleness -> HIT
    assertStaleness.accept(CacheControl.parse("max-stale=2"), ResponseVerifier::assertHit);

    // Allow 1 second of staleness -> CONDITIONAL_HIT as staleness is 2
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    assertStaleness.accept(
        CacheControl.parse("max-stale=1"), ResponseVerifier::assertConditionalHit);
  }

  @StoreParameterizedTest
  void imposeRevalidationWhenStaleByMustRevalidate(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1, must-revalidate")
        .setBody("popeye"));
    seedCache(serverUri);

    clock.advanceSeconds(2); // Make stale by 1 sec

    // A revalidation is made despite max-stale=2
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    var requestMaxStale2Secs = GET(serverUri).header("Cache-Control", "max-stale=2");
    get(requestMaxStale2Secs).assertConditionalHit();
  }

  @StoreParameterizedTest
  void cacheTwoPathsSameUri(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("alpha"));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("beta"));
    seedCache(serverUri.resolve("/a"));
    seedCache(serverUri.resolve("/b"));

    get(serverUri.resolve("/a"))
        .assertHit()
        .assertBody("alpha");
    get(serverUri.resolve("/b"))
        .assertHit()
        .assertBody("beta");
  }

  @StoreParameterizedTest
  void preventCachingByNoStore(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "no-store")
        .setBody("alpha"));
    seedCache(serverUri.resolve("/a")); // Offer to cache
    assertNotCached(serverUri.resolve("/a"));

    // The request can also prevent caching even if the response is cacheable
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("beta"));
    var request = GET(serverUri.resolve("/b")).header("Cache-Control", "no-store");
    seedCache(request); // Offer to cache
    assertNotCached(serverUri.resolve("/b"));
  }

  @StoreParameterizedTest
  void preventCachingByWildcardVary(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Vary", "*")
        .setBody("Cache me if you can!"));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Vary", "*")
        .setBody("Cache me if you can!"));
    seedCache(serverUri); // Offer to cache
    get(serverUri)
        .assertMiss() // Not cached
        .assertBody("Cache me if you can!");
  }

  @StoreParameterizedTest
  void varyingResponse(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Vary", "X-My-Header")
        .setBody("alpha"));
    var requestAlpha = GET(serverUri).header("X-My-Header", "a");
    seedCache(requestAlpha);
    get(requestAlpha)
        .assertHit()
        .assertBody("alpha")
        .cacheResponse()
        .assertRequestHeaders("X-My-Header", "a");

    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Vary", "X-My-Header")
        .setBody("beta"));
    var requestBeta = GET(serverUri).header("X-My-Header", "b");
    // TODO that'll need to change if we ever support storing multiple variants
    seedCache(requestBeta); // Replace first variant
    get(requestBeta)
        .assertHit()
        .assertBody("beta")
        .cacheResponse()
        .assertRequestHeaders("X-My-Header", "b");

    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Vary", "X-My-Header")
        .setBody("ϕ"));
    var requestPhi = GET(serverUri); // Varying header is absent -> another variant!
    seedCache(requestPhi); // Replace second variant
    get(requestPhi)
        .assertHit()
        .assertBody("ϕ")
        .cacheResponse()
        .assertAbsentRequestHeader("X-My-Header");
  }

  /**
   * Responses that vary on header fields that can be added implicitly by the HttpClient are
   * rendered as uncacheable.
   */
  @ParameterizedTest
  @ValueSource(strings = {"Cookie", "Cookie2", "Authorization", "Proxy-Authorization"})
  @StoreConfig(store = DISK, fileSystem = SYSTEM)
  void responsesVaryingOnImplicitHeadersAreNotStored(String implicitField, Store store)
      throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Vary", "Accept-Encoding, " + implicitField)
        .setBody("aaa"));
    seedCache(serverUri).assertBody("aaa");
    assertNotCached(serverUri);
  }

  @StoreParameterizedTest
  void varyOnAcceptEncoding(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Vary", "Accept-Encoding")
        .addHeader("Content-Encoding", "gzip")
        .setBody(new okio.Buffer().write(gzip("Jigglypuff"))));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Vary", "Accept-Encoding")
        .addHeader("Content-Encoding", "deflate")
        .setBody(new okio.Buffer().write(deflate("Jigglypuff"))));

    var gzipRequest = GET(serverUri).header("Accept-Encoding", "gzip");
    seedCache(gzipRequest).assertBody("Jigglypuff");
    get(gzipRequest)
        .assertHit()
        .assertBody("Jigglypuff");

    var deflateRequest = GET(serverUri).header("Accept-Encoding", "deflate");
    seedCache(deflateRequest).assertBody("Jigglypuff"); // Replace gzip variant
    get(deflateRequest)
        .assertHit()
        .assertBody("Jigglypuff");
  }

  @StoreParameterizedTest
  void varyOnMultipleFields(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Vary", "Accept-Encoding, Accept-Language")
        .addHeader("Vary", "Accept")
        .addHeader("Content-Language", "fr-FR")
        .setBody("magnifique"));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Vary", "Accept-Encoding, Accept-Language")
        .addHeader("Vary", "Accept")
        .addHeader("Content-Language", "es-ES")
        .setBody("magnífico"));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Vary", "Accept-Encoding, Accept-Language")
        .addHeader("Vary", "Accept")
        .addHeader("Content-Language", "en-US")
        .setBody("Lit!"));

    var jeNeParlePasAnglais = GET(serverUri)
        .header("Accept-Language", "fr-FR")
        .header("Accept-Encoding", "identity");
    seedCache(jeNeParlePasAnglais); // Put in cache
    get(jeNeParlePasAnglais)
        .assertHit()
        .assertHeader("Content-Language", "fr-FR")
        .assertBody("magnifique")
        .cacheResponse()
        .assertRequestHeaders(
            "Accept-Language", "fr-FR",
            "Accept-Encoding", "identity");
    get(jeNeParlePasAnglais.header("My-Header", "a"))
        .assertHit()
        .assertBody("magnifique");
    // Current variant has no Accept header, so this won't match
    var withTextHtml = jeNeParlePasAnglais
        .header("Accept", "text/html")
        .header("Cache-Control", "only-if-cached");
    get(withTextHtml).assertUnsatisfiable();

    var noHabloIngles = GET(serverUri)
        .header("Accept-Language", "es-ES")
        .header("Accept-Encoding", "identity")
        .header("Accept", "text/html");
    seedCache(noHabloIngles); // Replace french variant
    get(noHabloIngles)
        .assertHit()
        .assertHeader("Content-Language", "es-ES")
        .assertBody("magnífico")
        .cacheResponse()
        .assertRequestHeaders(
            "Accept-Language", "es-ES",
            "Accept-Encoding", "identity",
            "Accept", "text/html");
    get(noHabloIngles.header("My-Header", "a"))
        .assertHit()
        .assertBody("magnífico");
    // Request with different Accept won't match
    var withApplicationJson = noHabloIngles
        .header("Accept", "application/json")
        .header("Cache-Control", "only-if-cached");
    get(withApplicationJson).assertUnsatisfiable();

    // Absent varying fields won't match a request containing them
    seedCache(serverUri); // Replaces current variant
    get(serverUri)
        .assertHit()
        .assertHeader("Content-Language", "en-US")
        .assertBody("Lit!");
  }

  @StoreParameterizedTest
  void varyOnMultipleFieldValues(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Vary", "My-Header")
        .setBody("alpha"));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Vary", "My-Header")
        .setBody("beta"));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Vary", "My-Header")
        .setBody("charlie"));

    var requestAlpha = GET(serverUri)
        .header("My-Header", "val1")
        .header("My-Header", "val2")
        .header("My-Header", "val3");
    seedCache(requestAlpha); // Put alpha variant
    get(requestAlpha)
        .assertHit()
        .assertBody("alpha");

    // This matches as values are only different in order
    var requestBeta = GET(serverUri)
        .header("My-Header", "val2")
        .header("My-Header", "val3")
        .header("My-Header", "val1");
    get(requestBeta)
        .assertHit()
        .assertBody("alpha");

    // This doesn't match as there're 2 values vs alpha variant's 3
    var requestBeta2 = GET(serverUri)
        .header("My-Header", "val1")
        .header("My-Header", "val2");
    seedCache(requestBeta2);
    get(requestBeta2)
        .assertHit()
        .assertBody("beta");

    // Request with no varying header values doesn't match
    seedCache(serverUri);
    get(serverUri)
        .assertHit()
        .assertBody("charlie");
  }

  @StoreParameterizedTest
  void cacheMovedPermanently(Store store) throws Exception {
    setUpCache(store);
    client = clientBuilder.followRedirects(Redirect.ALWAYS).build();

    server.enqueue(new MockResponse()
        .setResponseCode(301) // 301 is cacheable by default
        .setHeader("Location", "/redirect"));
    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "no-store") // Prevent caching
        .setBody("Ey yo"));
    seedCache(serverUri);

    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "no-store") // Prevent caching
        .setBody("Ey yo"));
    get(serverUri)
        .assertCode(200)
        .assertMiss() // Target response isn't cacheable
        .assertBody("Ey yo")
        .assertUri(serverUri.resolve("/redirect"))
        .previousResponse()
        .assertCode(301)
        .assertHit() // 301 response is cached
        .assertHeader("Location", "/redirect");

    // Disable auto redirection
    client = clientBuilder.followRedirects(Redirect.NEVER).build();

    get(serverUri)
        .assertCode(301)
        .assertHit();
  }

  @StoreParameterizedTest
  void cacheTemporaryRedirectAndRedirectTarget(Store store) throws Exception {
    setUpCache(store);
    client = clientBuilder.followRedirects(Redirect.ALWAYS).build();

    server.enqueue(new MockResponse()
        .setResponseCode(307)
        .setHeader("Cache-Control", "max-age=2")
        .setHeader("Location", "/redirect"));
    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "max-age=1")
        .setBody("Ey yo"));
    seedCache(serverUri);

    get(serverUri)
        .assertCode(200)
        .assertHit()
        .assertBody("Ey yo")
        .assertUri(serverUri.resolve("/redirect"))
        .previousResponse()
        .assertCode(307)
        .assertHit()
        .assertHeader("Location", "/redirect");

    get(serverUri.resolve("/redirect"))
        .assertCode(200)
        .assertHit()
        .assertBody("Ey yo");

    // Disable auto redirection
    client = clientBuilder.followRedirects(Redirect.NEVER).build();

    get(serverUri)
        .assertCode(307)
        .assertHit();
    get(serverUri.resolve("/redirect"))
        .assertCode(200)
        .assertHit()
        .assertBody("Ey yo");

    clock.advanceSeconds(2); // Make 200 response stale but retain 307 response's freshness

    // Enable auto redirection
    client = clientBuilder.followRedirects(Redirect.ALWAYS).build();

    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "max-age=2")
        .setBody("Hey there"));
    get(serverUri)
        .assertCode(200)
        .assertConditionalMiss()
        .assertBody("Hey there")
        .previousResponse()
        .assertCode(307)
        .assertHit();
  }

  @StoreParameterizedTest
  void cacheRedirectTarget(Store store) throws Exception {
    setUpCache(store);
    client = clientBuilder.followRedirects(Redirect.ALWAYS).build();

    server.enqueue(new MockResponse()
        .setResponseCode(307) // 307 won't be cached as it isn't cacheable by default
        .setHeader("Location", "/redirect"));
    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "max-age=1")
        .setBody("Wakanda forever"));
    seedCache(serverUri);

    server.enqueue(new MockResponse()
        .setResponseCode(307)
        .setHeader("Location", "/redirect"));
    get(serverUri)
        .assertCode(200)
        .assertHit() // 200 response is cached
        .assertBody("Wakanda forever")
        .previousResponse()
        .assertCode(307)
        .assertMiss();
  }

  @ParameterizedTest
  @StoreConfig(store = DISK, fileSystem = SYSTEM)
  @ValueSource(ints = {301, 302, 303, 307, 308})
  void cacheableRedirectWithUncacheableTarget(int code, Store store) throws Exception {
    setUpCache(store);
    client = clientBuilder.followRedirects(Redirect.ALWAYS).build();

    // Make redirect cacheable & target uncacheable
    server.enqueue(new MockResponse()
        .setResponseCode(code)
        .setHeader("Location", "/redirect")
        .setHeader("Cache-Control", "max-age=1"));
    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "no-store")
        .setBody("Wakanda forever"));
    seedCache(serverUri);

    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "no-store")
        .setBody("Wakanda forever"));
    get(serverUri)
        .assertCode(200)
        .assertMiss() // Target response isn't cached
        .assertBody("Wakanda forever")
        .previousResponse()
        .assertCode(code)
        .assertHit(); // Redirecting response is cached

    // Disable auto redirection
    client = clientBuilder.followRedirects(Redirect.NEVER).build();

    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "no-store")
        .setBody("Wakanda forever"));
    get(serverUri)
        .assertCode(code)
        .assertHit();
    get(serverUri.resolve("/redirect"))
        .assertCode(200)
        .assertMiss()
        .assertBody("Wakanda forever");
  }

  @ParameterizedTest
  @StoreConfig(store = DISK, fileSystem = SYSTEM)
  @ValueSource(ints = {301, 302, 303, 307, 308})
  void uncacheableRedirectWithCacheableTarget(int code, Store store) throws Exception {
    setUpCache(store);
    client = clientBuilder.followRedirects(Redirect.ALWAYS).build();

    // Make redirect uncacheable & target cacheable
    var redirectingResponse = new MockResponse()
        .setResponseCode(code)
        .setHeader("Location", "/redirect");
    if (code == 301) {
      // 301 is cacheable by default so explicitly disallow caching
      redirectingResponse.setHeader("Cache-Control", "no-store");
    }
    server.enqueue(redirectingResponse);
    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "max-age=1")
        .setBody("Wakanda forever"));
    seedCache(serverUri);

    server.enqueue(redirectingResponse);
    get(serverUri)
        .assertCode(200)
        .assertHit() // Target response is cached
        .assertBody("Wakanda forever")
        .previousResponse()
        .assertCode(code)
        .assertMiss(); // Redirecting response isn't cached

    // Disable auto redirection
    client = clientBuilder.followRedirects(Redirect.NEVER).build();

    server.enqueue(redirectingResponse);
    get(serverUri)
        .assertCode(code)
        .assertMiss();
    get(serverUri.resolve("/redirect"))
        .assertCode(200)
        .assertHit()
        .assertBody("Wakanda forever");
  }

  @ParameterizedTest
  @StoreConfig(store = DISK, fileSystem = SYSTEM)
  @ValueSource(ints = {301, 302, 303, 307, 308})
  void uncacheableRedirectWithUncacheableTarget(int code, Store store) throws Exception {
    setUpCache(store);
    client = clientBuilder.followRedirects(Redirect.ALWAYS).build();

    // Make both redirect & target uncacheable
    var redirectingResponse = new MockResponse()
        .setResponseCode(code)
        .setHeader("Location", "/redirect");
    if (code == 301) {
      // 301 is cacheable by default so explicitly disallow caching
      redirectingResponse.setHeader("Cache-Control", "no-store");
    }
    server.enqueue(redirectingResponse);
    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "no-store")
        .setBody("Wakanda forever"));
    seedCache(serverUri);

    server.enqueue(redirectingResponse);
    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "no-store")
        .setBody("Wakanda forever"));
    get(serverUri)
        .assertCode(200)
        .assertMiss() // Target response isn't cached
        .assertBody("Wakanda forever")
        .previousResponse()
        .assertCode(code)
        .assertMiss(); // Redirecting response isn't cached

    // Disable auto redirection
    client = clientBuilder.followRedirects(Redirect.NEVER).build();

    server.enqueue(redirectingResponse);
    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "no-store")
        .setBody("Wakanda forever"));
    get(serverUri)
        .assertCode(code)
        .assertMiss();
    get(serverUri.resolve("/redirect"))
        .assertCode(200)
        .assertMiss()
        .assertBody("Wakanda forever");
  }

  @ParameterizedTest
  @StoreConfig(store = DISK, fileSystem = SYSTEM)
  @ValueSource(ints = {301, 302, 303, 307, 308})
  void cacheableRedirectWithCacheableTarget(int code, Store store) throws Exception {
    setUpCache(store);
    client = clientBuilder.followRedirects(Redirect.ALWAYS).build();

    // Make both redirect & target cacheable
    server.enqueue(new MockResponse()
        .setResponseCode(code)
        .setHeader("Location", "/redirect")
        .setHeader("Cache-Control", "max-age=1"));
    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "max-age=1")
        .setBody("Wakanda forever"));
    seedCache(serverUri);

    get(serverUri)
        .assertCode(200)
        .assertHit() // Target response is cached
        .assertBody("Wakanda forever")
        .previousResponse()
        .assertCode(code)
        .assertHit(); // Redirecting response is cached

    // Disable auto redirection
    client = clientBuilder.followRedirects(Redirect.NEVER).build();

    get(serverUri)
        .assertCode(code)
        .assertHit();
    get(serverUri.resolve("/redirect"))
        .assertCode(200)
        .assertHit()
        .assertBody("Wakanda forever");
  }

  /** Ensure the cache doesn't store responses that disagree with their requests' URIs. */
  @StoreParameterizedTest
  void responseWithDifferentUriFromThatOfRequest(Store store) throws Exception {
    setUpCache(store);

    // Don't let the cache intercept redirects
    clientBuilder = Methanol.newBuilder()
        .interceptor(cache.interceptor(threadPool))
        .followRedirects(Redirect.ALWAYS);
    client = clientBuilder.build();

    // The cache only sees the second response
    server.enqueue(new MockResponse()
        .setResponseCode(301)
        .addHeader("Location", "/redirect"));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("Pikachu"));

    // Offer the response to cache
    seedCache(serverUri)
        .assertUri(serverUri.resolve("/redirect"))
        .assertBody("Pikachu");

    // The cache refuses the response as its URI is different from that of the
    // request it intercepted.
    assertNotCached(serverUri.resolve("/redirect"));
  }

  @StoreParameterizedTest
  void staleWhileRevalidate(Store store) throws Exception {
    setUpCache(store);
    var dateInstant = clock.instant();
    var lastModifiedInstant = dateInstant.minusSeconds(1);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1, stale-while-revalidate=2")
        .addHeader("ETag", "1")
        .addHeader("Last-Modified", formatInstant(lastModifiedInstant))
        .addHeader("Date", formatInstant(dateInstant))
        .setBody("Pickachu"));
    seedCache(serverUri);
    get(serverUri).assertHit();
    server.takeRequest(); // Remove initial request

    clock.advanceSeconds(3); // Make response stale by 2 seconds

    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1, stale-while-revalidate=2")
        .addHeader("ETag", "2")
        .addHeader("Last-Modified", formatInstant(clock.instant().minusSeconds(1)))
        .addHeader("Date", formatInstant(clock.instant()))
        .setBody("Ricardo"));

    get(serverUri)
        .assertHit()
        .assertBody("Pickachu")
        .assertHeader("ETag", "1")
        .assertHeader("Warning", "110 - \"Response is Stale\"")
        .assertHeader("Age", "3");

    // A revalidation request is sent in background
    var sentRequest = server.takeRequest();
    assertThat(sentRequest.getHeader("If-None-Match"))
        .isEqualTo("1");
    assertThat(sentRequest.getHeaders().getInstant("If-Modified-Since"))
        .isEqualTo(lastModifiedInstant);

    // Retry till revalidation response completes, causing the cached response to be updated
    awaitCacheHit()
        .assertHit()
        .assertBody("Ricardo")
        .assertHeader("ETag", "2")
        .assertAbsentHeader("Warning") // No warnings
        .assertHeader("Age", "0");
  }

  @StoreParameterizedTest
  void unsatisfiedStaleWhileRevalidate(Store store) throws Exception {
    setUpCache(store);
    var dateInstant = clock.instant();
    var lastModifiedInstant = dateInstant.minusSeconds(1);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1, stale-while-revalidate=2")
        .addHeader("ETag", "1")
        .addHeader("Last-Modified", formatInstant(lastModifiedInstant))
        .addHeader("Date", formatInstant(dateInstant))
        .setBody("Pickachu"));
    seedCache(serverUri);
    get(serverUri).assertHit();
    server.takeRequest(); // Remove initial request

    clock.advanceSeconds(4); // Make response stale by 3 seconds (unsatisfied stale-while-revalidate)

    // Synchronous revalidation is issued when stale-while-revalidate isn't satisfied

    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    get(serverUri)
        .assertConditionalHit()
        .assertBody("Pickachu")
        .assertHeader("ETag", "1")
        .assertAbsentHeader("Warning");

    clock.advanceSeconds(4); // Make response stale by 3 seconds (unsatisfied stale-while-revalidate)

    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1, stale-while-revalidate=2")
        .addHeader("ETag", "2")
        .addHeader("Last-Modified", formatInstant(clock.instant().minusSeconds(1)))
        .addHeader("Date", formatInstant(clock.instant()))
        .setBody("Ricardo"));
    get(serverUri)
        .assertConditionalMiss()
        .assertBody("Ricardo")
        .assertHeader("ETag", "2")
        .assertAbsentHeader("Warning");
  }

  @ParameterizedTest
  @StoreConfig(store = DISK, fileSystem = SYSTEM)
  @ValueSource(ints = {500, 502, 503, 504})
  void staleIfErrorWithServerErrorCodes(int code, Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1, stale-if-error=2")
        .setBody("Ricardo"));
    seedCache(serverUri);
    get(serverUri).assertHit();

    clock.advanceSeconds(2); // Make response stale by 1 second

    server.enqueue(new MockResponse().setResponseCode(code));
    get(serverUri)
        .assertHit()
        .assertCode(200)
        .assertBody("Ricardo")
        .assertHeader("Warning", "110 - \"Response is Stale\"");

    clock.advanceSeconds(1); // Make response stale by 2 seconds

    // stale-if-error is still satisfied
    server.enqueue(new MockResponse().setResponseCode(code));
    get(serverUri)
        .assertHit()
        .assertCode(200)
        .assertBody("Ricardo")
        .assertHeader("Warning", "110 - \"Response is Stale\"");
  }

  @ParameterizedTest
  @StoreConfig(store = DISK, fileSystem = SYSTEM)
  @ValueSource(ints = {500, 502, 503, 504})
  void unsatisfiedStaleIfErrorWithServerErrorCodes(int code, Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1, stale-if-error=1")
        .setBody("Ditto"));
    seedCache(serverUri);
    get(serverUri).assertHit();

    clock.advanceSeconds(3); // Make response stale by 2 seconds

    // stale-if-error isn't satisfied
    server.enqueue(new MockResponse().setResponseCode(code));
    get(serverUri)
        .assertCode(code)
        .assertConditionalMiss()
        .assertBody("") // No body in the error response
        .assertAbsentHeader("Warning");
  }

  private static final class FailingInterceptor implements Interceptor {
    private final Supplier<Throwable> failureFactory;

    FailingInterceptor(Supplier<Throwable> failureFactory) {
      this.failureFactory = failureFactory;
    }

    FailingInterceptor(Class<? extends Throwable> failureType) {
      this.failureFactory = () -> {
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
  @StoreConfig(store = DISK, fileSystem = SYSTEM)
  @ValueSource(classes = {ConnectException.class, UnknownHostException.class})
  void staleIfErrorWithConnectionFailure(
      Class<? extends Throwable> failureType, Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1, stale-if-error=2")
        .setBody("Jigglypuff"));
    seedCache(serverUri);
    get(serverUri).assertHit();

    clock.advanceSeconds(2); // Make response stale by 1 second

    client = clientBuilder.backendInterceptor(new FailingInterceptor(failureType)).build();

    get(serverUri)
        .assertCode(200)
        .assertHit()
        .assertBody("Jigglypuff")
        .assertHeader("Warning", "110 - \"Response is Stale\"");

    clock.advanceSeconds(1); // Make response stale by 2 seconds

    // stale-if-error is still satisfied
    server.enqueue(new MockResponse().setBody("huh?"));
    get(serverUri)
        .assertCode(200)
        .assertHit()
        .assertBody("Jigglypuff")
        .assertHeader("Warning", "110 - \"Response is Stale\"");
  }

  @ParameterizedTest
  @StoreConfig(store = DISK, fileSystem = SYSTEM)
  @ValueSource(classes = {ConnectException.class, UnknownHostException.class})
  void unsatisfiedStaleIfErrorWithConnectionFailure(
      Class<? extends Throwable> failureType, Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1, stale-if-error=1")
        .setBody("Ricardo"));
    seedCache(serverUri);
    get(serverUri).assertHit();

    clock.advanceSeconds(3); // Make response stale by 2 seconds

    client = clientBuilder.backendInterceptor(new FailingInterceptor(failureType)).build();

    // stale-if-error isn't satisfied
    assertThatExceptionOfType(failureType).isThrownBy(() -> get(serverUri));
  }

  @StoreParameterizedTest
  void staleIfErrorWithInapplicableErrorCode(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1, stale-if-error=1")
        .setBody("Eevee"));
    seedCache(serverUri);
    get(serverUri).assertHit();

    clock.advanceSeconds(2); // Make response stale by 1 second

    // Only 5xx error codes are applicable to stale-if-error
    server.enqueue(new MockResponse().setResponseCode(404));
    get(serverUri)
        .assertCode(404)
        .assertConditionalMiss()
        .assertBody(""); // Error response has no body
  }

  @StoreParameterizedTest
  void staleIfErrorWithInapplicableException(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1, stale-if-error=1")
        .setBody("Charmander"));
    seedCache(serverUri);
    get(serverUri).assertHit();

    // Make requests fail with a non applicable exception
    client = clientBuilder
        .backendInterceptor(new FailingInterceptor(TestException::new))
        .build();

    clock.advanceSeconds(2); // Make response stale by 1 second

    // Exception is rethrown as stale-if-error isn't applicable
    assertThatThrownBy(() -> get(serverUri)).isInstanceOf(TestException.class);
  }

  @StoreParameterizedTest
  void staleIfErrorWithUncheckedIOException(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1, stale-if-error=1")
        .setBody("Jynx"));
    seedCache(serverUri);
    get(serverUri).assertHit();

    // Make requests fail with ConnectException disguised as an UncheckedIOException
    client = clientBuilder
        .backendInterceptor(
            new FailingInterceptor(() -> new UncheckedIOException(new ConnectException())))
        .build();

    clock.advanceSeconds(2); // Make response stale by 1 second

    // UncheckedIOException's cause is ConnectException -> stale-if-error is applicable
    var request = GET(serverUri).header("Cache-Control", "stale-if-error=2");
    get(request)
        .assertHit()
        .assertCode(200)
        .assertBody("Jynx")
        .assertHeader("Warning", "110 - \"Response is Stale\"");
  }

  @StoreParameterizedTest
  void staleIfErrorInRequestOverridesThatInResponse(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1, stale-if-error=2")
        .setBody("Psyduck"));
    seedCache(serverUri);
    get(serverUri).assertHit();

    clock.advanceSeconds(4); // Make response stale by 3 seconds

    // Response's stale-if-error isn't satisfied but that of the request is
    server.enqueue(new MockResponse().setResponseCode(500));
    var request1 = GET(serverUri).header("Cache-Control", "stale-if-error=3");
    get(request1)
        .assertCode(200)
        .assertHit()
        .assertBody("Psyduck");

    // Refresh response
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    get(serverUri).assertConditionalHit();

    clock.advanceSeconds(3); // Make response stale by 2 seconds

    // Unsatisfied request's stale-if-error takes precedence
    server.enqueue(new MockResponse().setResponseCode(500));
    var request2 = GET(serverUri).header("Cache-Control", "stale-if-error=1");
    get(request2)
        .assertCode(500)
        .assertConditionalMiss()
        .assertBody("");
  }

  @StoreParameterizedTest
  void warnCodes1xxAreRemovedOnRevalidation(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Warning", "199 - \"OMG IT'S HAPPENING\"")
        .addHeader("Warning", "299 - \"EVERY BODY STAY CALM\"")
        .setBody("Dwight the trickster"));
    seedCache(serverUri);

    clock.advanceSeconds(2); // Make stale

    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    get(serverUri)
        .assertConditionalHit()
        .assertBody("Dwight the trickster")
        .assertHeader("Warning", "299 - \"EVERY BODY STAY CALM\""); // Warn code 199 is removed
  }

  /**
   * Tests that status codes in rfc7231 6.1 without Cache-Control or Expires are only cached if
   * defined as cacheable by default.
   */
  @ParameterizedTest
  @CsvFileSource(resources = "/default_cacheability.csv", numLinesToSkip = 1)
  @StoreConfig(store = DISK, fileSystem = SYSTEM)
  void defaultCacheability(int code, boolean cacheableByDefault, Store store) throws Exception {
    setUpCache(store);
    client = clientBuilder
        .version(Version.HTTP_1_1)       // HTTP_2 doesn't let 101 pass
        .followRedirects(Redirect.NEVER) // Disable redirections in case code is 3xx
        .build();

    // Last-Modified:      20 seconds from date
    // Heuristic lifetime: 2 seconds
    var lastModifiedInstant = clock.instant();
    clock.advanceSeconds(20);
    var dateInstant = clock.instant();

    var body =
        code == 204 || code == 304 ? "" : "Cache me pls!"; // Body with 204 or 304 causes problems
    server.enqueue(new MockResponse()
        .setResponseCode(code)
        .addHeader("Last-Modified", formatInstant(lastModifiedInstant))
        .addHeader("Date", formatInstant(dateInstant))
        .setBody(body));
    seedCache(serverUri);

    clock.advanceSeconds(1); // Heuristic freshness retained

    ResponseVerifier<String> response;
    if (cacheableByDefault) {
      response = get(serverUri).assertHit();
    } else {
      server.enqueue(new MockResponse()
          .setResponseCode(code)
          .addHeader("Last-Modified", formatInstant(lastModifiedInstant))
          .addHeader("Date", formatInstant(dateInstant))
          .setBody(body));
      response = get(serverUri).assertMiss();
    }
    response.assertCode(code)
        .assertBody(body);
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

    server.enqueue(new MockResponse()
        .addHeader("Last-Modified", formatInstant(lastModifiedInstant))
        .addHeader("Date", formatInstant(dateInstant))
        .setBody("Cache me pls!"));
    seedCache(serverUri);

    clock.advanceSeconds(1); // Heuristic freshness retained (age = 2 secs, lifetime = 2 secs)

    get(serverUri)
        .assertHit()
        .assertBody("Cache me pls!")
        .assertHeader("Age", "2")
        .assertAbsentHeader("Warning");

    clock.advanceSeconds(1); // Make response stale (age = 3 secs, lifetime = 2 secs)

    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    get(serverUri)
        .assertConditionalHit()
        .assertBody("Cache me pls!")
        .assertAbsentHeader("Age") // The response has no Age as it has just been revalidated
        .assertAbsentHeader("Warning");
  }

  @StoreParameterizedTest
  void warningOnHeuristicFreshnessWithAgeGreaterThanOneDay(Store store) throws Exception {
    setUpCache(store);
    // Last-Modified:      20 days from date
    // Heuristic lifetime: 2 days
    var lastModifiedInstant = clock.instant();
    clock.advance(ofDays(20));
    var dateInstant = clock.instant();

    server.enqueue(new MockResponse()
        .addHeader("Last-Modified", formatInstant(lastModifiedInstant))
        .addHeader("Date", formatInstant(dateInstant))
        .setBody("Cache me pls!"));
    seedCache(serverUri);

    // Heuristic freshness retained (age = 1 day + 1 second, lifetime = 2 days)
    clock.advance(ofDays(1).plusSeconds(1));

    get(serverUri)
        .assertHit()
        .assertBody("Cache me pls!")
        .assertHeader("Age", ofDays(1).plusSeconds(1).toSeconds())
        .assertHeader("Warning", "113 - \"Heuristic Expiration\"");
  }

  /** See https://tools.ietf.org/html/rfc7234#section-4.2.3 */
  @StoreParameterizedTest
  void computingAge(Store store) throws Exception {
    setUpCache(store);

    // Simulate response taking 3 seconds to arrive
    client = clientBuilder.backendInterceptor(
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
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=60")
        .addHeader("Age", "10"));
    // now = x + 5
    seedCache(serverUri) // Put in cache & advance clock
        .assertRequestSentAt(clock.instant().minusSeconds(3))
        .assertResponseReceivedAt(clock.instant());

    // now = x + 10
    // resident_time = now - responseTime = 5
    clock.advanceSeconds(5);

    // response_delay = 3
    // corrected_age_value = age_value + response_delay = 13
    // corrected_initial_age = max(apparent_age, corrected_age_value) = 13
    // resident_time = now - response_time = 5
    // current_age = corrected_initial_age + resident_time = 18
    get(serverUri)
        .assertHit()
        .assertHeader("Age", "18");
  }

  @ParameterizedTest
  @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
  @StoreConfig(store = DISK, fileSystem = SYSTEM)
  void unsafeMethodsInvalidateCache(String method, StoreContext storeContext) throws Exception {
    assertUnsafeMethodInvalidatesCache(storeContext, method, 200, true);
    assertUnsafeMethodInvalidatesCache(storeContext, method, 302, true);
  }

  @ParameterizedTest
  @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
  @StoreConfig(store = DISK, fileSystem = SYSTEM)
  void unsafeMethodsDoNotInvalidateCacheWithErrorResponse(
      String method, StoreContext storeContext) throws Exception {
    assertUnsafeMethodInvalidatesCache(storeContext, method, 104, false);
    assertUnsafeMethodInvalidatesCache(storeContext, method, 404, false);
    assertUnsafeMethodInvalidatesCache(storeContext, method, 504, false);
  }

  private void assertUnsafeMethodInvalidatesCache(
      StoreContext storeContext, String method, int code, boolean invalidationExpected)
      throws Exception {
    // Perform cleanup for previous call
    if (cache != null) {
      storeContext.drainQueuedTasks();
      cache.dispose();
    }
    setUpCache(storeContext.newStore());

    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("Pickachu"));
    seedCache(serverUri);

    get(serverUri)
        .assertHit()
        .assertBody("Pickachu");

    server.enqueue(new MockResponse()
        .setResponseCode(code)
        .setBody("Charmander"));
    var unsafeRequest = MutableRequest.create(serverUri)
        .method(method, BodyPublishers.noBody());
    get(unsafeRequest).assertMiss();

    if (invalidationExpected) {
      assertNotCached(serverUri);
    } else {
      get(serverUri)
          .assertCode(200)
          .assertHit()
          .assertBody("Pickachu");
    }
  }

  /**
   * Test that an invalidated response causes the URIs referenced via Location & Content-Location
   * to also get invalidated (https://tools.ietf.org/html/rfc7234#section-4.4).
   */
  // TODO find a way to test referenced URIs aren't invalidated if they have different hosts
  @ParameterizedTest
  @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
  @StoreConfig(store = DISK, fileSystem = SYSTEM)
  void unsafeMethodsInvalidateReferencedUris(String method, Store store) throws Exception {
    setUpCache(store);
    client = clientBuilder.followRedirects(Redirect.NEVER).build();

    server.enqueue(new MockResponse()
        .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
        .addHeader("Cache-Control", "max-age=1")
        .setBody("Pickachu"));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("Ditto"));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("Eevee"));
    seedCache(serverUri);
    seedCache(serverUri.resolve("ditto")).assertBody("Ditto");
    seedCache(serverUri.resolve("eevee")).assertBody("Eevee");

    get(serverUri)
        .assertHit()
        .assertBody("Pickachu");
    get(serverUri.resolve("ditto"))
        .assertHit()
        .assertBody("Ditto");
    get(serverUri.resolve("eevee"))
        .assertHit()
        .assertBody("Eevee");

    server.enqueue(new MockResponse()
        .addHeader("Location", "ditto")
        .addHeader("Content-Location", "eevee")
        .setBody("Eevee"));
    var unsafeRequest = MutableRequest.create(serverUri)
        .method(method, BodyPublishers.noBody());
    get(unsafeRequest) // Invalidates what's cached
        .assertMiss()
        .assertBody("Eevee");
    assertNotCached(serverUri.resolve("ditto"));
    assertNotCached(serverUri.resolve("eevee"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
  @StoreConfig(store = DISK, fileSystem = SYSTEM)
  void unsafeMethodsAreNotCached(String method, Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=2")
        .setBody("Pickachu"));

    var unsafeRequest = MutableRequest.create(serverUri)
        .method(method, BodyPublishers.noBody());
    seedCache(unsafeRequest).assertBody("Pickachu"); // Offer to cache
    assertNotCached(serverUri);
  }

  @StoreParameterizedTest
  void headOfCachedGet(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=2")
        .setBody("Mewtwo"));
    seedCache(serverUri);

    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=2"));
    var head = MutableRequest.create(serverUri)
        .method("HEAD", BodyPublishers.noBody());
    get(head).assertMiss();

    get(serverUri)
        .assertHit()
        .assertBody("Mewtwo");
  }

  @UseHttps
  @StoreParameterizedTest
  void requestsWithPushPromiseHandlersBypassCache(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "max-age=1")
        .setBody("Steppenwolf"));
    seedCache(serverUri);
    get(serverUri)
        .assertHit()
        .assertBody("Steppenwolf");

    clock.advanceSeconds(2); // Make cached response stale

    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "max-age=1")
        .setBody("Darkseid"));

    // The request isn't served by the cache as it doesn't know what might be pushed by the server.
    // The main response however still contributes to updating the cache.
    getWithPushHandler(serverUri)
        .assertCode(200)
        .assertMiss()
        .assertBody("Darkseid");

    // Previously cached response is replaced
    get(serverUri)
        .assertCode(200)
        .assertHit()
        .assertBody("Darkseid");
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
    server.enqueue(new MockResponse()
        .setBody("For Darkseid"));
    seedCache(serverUri);
    get(serverUri)
        .assertHit()
        .assertBody("For Darkseid");

    server.enqueue(new MockResponse()
        .setBody("For Darkseid"));
    var request = GET(serverUri);
    PreconditionKind.get(preconditionField).add(request, preconditionField, clock);
    get(request)
        .assertMiss()
        .assertBody("For Darkseid");

    get(request.removeHeader(preconditionField))
        .assertHit()
        .assertBody("For Darkseid");
  }

  @StoreParameterizedTest
  void manuallyInvalidateEntries(Store store) throws Exception {
    setUpCache(store);
    var uri1 = serverUri.resolve("/a");
    var uri2 = serverUri.resolve("/b");
    server.setDispatcher(new Dispatcher() {
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

    seedCache(uri1);
    get(uri1)
        .assertHit()
        .assertBody("a");

    seedCache(uri2);
    get(uri2)
        .assertHit()
        .assertBody("b");

    assertThat(cache.remove(uri1)).isTrue();
    assertNotCached(uri1);

    assertThat(cache.remove(MutableRequest.GET(uri2))).isTrue();
    assertNotCached(uri2);

    seedCache(uri1);
    get(uri1)
        .assertHit()
        .assertBody("a");

    seedCache(uri2);
    get(uri2)
        .assertHit()
        .assertBody("b");

    cache.clear();
    assertNotCached(uri1);
    assertNotCached(uri2);
  }

  @StoreParameterizedTest
  void manuallyInvalidateEntryMatchingASpecificVariant(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Vary", "Accept-Encoding")
        .addHeader("Content-Encoding", "gzip")
        .setBody(new okio.Buffer().write(gzip("Mew"))));
    seedCache(GET(serverUri).header("Accept-Encoding", "gzip"));

    // Removal only succeeds for the request matching the correct response variant

    assertThat(cache.remove(GET(serverUri).header("Accept-Encoding", "deflate"))).isFalse();
    get(GET(serverUri).header("Accept-Encoding", "gzip"))
        .assertCode(200)
        .assertHit()
        .assertBody("Mew");

    assertThat(cache.remove(GET(serverUri).header("Accept-Encoding", "gzip"))).isTrue();
    assertNotCached(GET(serverUri).header("Accept-Encoding", "gzip"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"private", "public"})
  @StoreConfig(store = DISK, fileSystem = SYSTEM)
  void responseWithCacheControlPublicOrPrivateIsCacheableByDefault(String directive, Store store)
      throws Exception {
    setUpCache(store);
    // Last-Modified:      30 seconds from date
    // Heuristic lifetime: 3 seconds
    var lastModifiedInstant = clock.instant();
    clock.advanceSeconds(30);
    var dateInstant = clock.instant();
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", directive)
        .addHeader("Last-Modified", formatInstant(lastModifiedInstant))
        .addHeader("Date", formatInstant(dateInstant))
        .setBody("Mew"));
    seedCache(serverUri); // Put in cache
    server.takeRequest(); // Drop network request

    clock.advanceSeconds(2); // Retain freshness (lifetime = 3 seconds, age = 2 seconds)

    get(serverUri)
        .assertHit()
        .assertBody("Mew");

    // Make response stale by 1 second (lifetime = 3 seconds, age = 4 seconds)
    clock.advanceSeconds(2);

    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    get(serverUri)
        .assertConditionalHit()
        .assertBody("Mew");

    var sentRequest = server.takeRequest();
    assertThat(sentRequest.getHeaders().getInstant("If-Modified-Since"))
        .isEqualTo(lastModifiedInstant);
  }

  @UseHttps // Test SSLSession persistence
  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void cachePersistence(StoreContext storeContext) throws Exception {
    setUpCache(storeContext.newStore());
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("Eevee"));
    seedCache(serverUri);

    cache.close();
    clock.advanceSeconds(1); // Retain freshness between sessions

    setUpCache(storeContext.newStore());
    get(serverUri)
        .assertHit()
        .assertBody("Eevee")
        .assertCachedWithSsl();

    cache.close();
    clock.advanceSeconds(1); // Make response stale by 1 second between sessions

    setUpCache(storeContext.newStore());
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    get(serverUri)
        .assertConditionalHit()
        .assertBody("Eevee")
        .assertCachedWithSsl();
  }

  @StoreParameterizedTest
  void cacheSize(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("bababooey"));
    seedCache(serverUri);
    assertThat(cache.size()).isEqualTo(cache.storeForTesting().size());
  }

  @StoreParameterizedTest
  void networkFailureDuringTransmission(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("Jigglypuff")
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
    assertThatIOException().isThrownBy(() -> seedCache(serverUri));

    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("Jigglypuff"));
    seedCache(serverUri).assertBody("Jigglypuff");

    clock.advanceSeconds(2); // Make response stale by 1 second

    // Attempted revalidation throws & cache update is discarded
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("Jigglypuff")
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
    assertThatIOException().isThrownBy(() -> get(serverUri));

    // Stale cache response is still there
    var request = GET(serverUri).header("Cache-Control", "max-stale=1");
    get(request)
        .assertHit()
        .assertBody("Jigglypuff")
        .assertHeader("Age", "2")
        .assertHeader("Warning", "110 - \"Response is Stale\"");
  }

  private static final class FailingStore extends ForwardingStore {
    volatile boolean allowReads = false;
    volatile boolean allowWrites = false;

    FailingStore(Store delegate) {
      super(delegate);
    }

    @Override
    public @Nullable Editor edit(String key) throws IOException {
      return wrapEditor(super.edit(key));
    }

    @Override
    public @Nullable Viewer view(String key) throws IOException {
      return wrapViewer(super.view(key));
    }

    @Nullable Editor wrapEditor(@Nullable Editor e) {
      return e != null ? new FailingEditor(e) : null;
    }

    @Nullable Viewer wrapViewer(@Nullable Viewer v) {
      return v != null ? new FailingViewer(v) : null;
    }

    private final class FailingEditor extends ForwardingEditor {
      private volatile boolean committed;

      FailingEditor(Editor delegate) {
        super(delegate);
      }

      @Override
      public CompletableFuture<Integer> writeAsync(long position, ByteBuffer src) {
        return super.writeAsync(position, src)
            .thenCompose(
                r ->
                    allowWrites
                        ? CompletableFuture.completedFuture(r)
                        : CompletableFuture.failedFuture(new TestException()));
      }

      @Override
      public void commitOnClose() {
        committed = true;
        super.commitOnClose();
      }

      @Override
      public void close() throws IOException {
        super.close();
        if (committed && !allowWrites) {
          throw new IOException("edit is committed but writes weren't allowed");
        }
      }
    }

    private final class FailingViewer extends ForwardingViewer {
      FailingViewer(Viewer delegate) {
        super(delegate);
      }

      @Override
      public CompletableFuture<Integer> readAsync(long position, ByteBuffer dst) {
        return super.readAsync(position, dst)
            .thenCompose(
                r ->
                    allowReads
                        ? CompletableFuture.completedFuture(r)
                        : CompletableFuture.failedFuture(new TestException()));

      }

      @Override
      public @Nullable Editor edit() throws IOException {
        return wrapEditor(super.edit());
      }
    }
  }

  @StoreParameterizedTest
  void prematurelyCloseResponseBody(Store store) throws Exception {
    setUpCache(store);

    // Ensure we have a body that spans multiple onNext signals
    var body = "Pickachu\n".repeat(12 * 1024);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody(body));

    // Prematurely close the response body. This causes cache writing to continue in background.
    try (var reader = client.send(GET(serverUri), MoreBodyHandlers.ofReader()).body()) {
      var chars = new char["Pickachu".length()];
      int read = reader.read(chars);
      assertThat(read).isEqualTo(chars.length);
      assertThat(new String(chars)).isEqualTo("Pickachu");
    }

    // Wait till opened editors are closed so all writing is completed
    editAwaiter.await();

    get(serverUri)
        .assertCode(200)
        .assertHit()
        .assertBody(body);
  }

  @StoreParameterizedTest
  void errorsWhileWritingDiscardsCaching(Store store) throws Exception {
    var failingStore = new FailingStore(store);
    failingStore.allowReads = true;
    setUpCache(failingStore);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("Pickachu"));

    // Write failure is ignored & the response completes normally nevertheless.
    seedCache(serverUri).assertBody("Pickachu");
    assertNotCached(serverUri);

    // Allow the response to be cached
    failingStore.allowWrites = true;
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("Pickachu"));
    seedCache(serverUri);
    get(serverUri)
        .assertHit()
        .assertBody("Pickachu");

    clock.advanceSeconds(2); // Make response stale by 1 second

    // Attempted revalidation throws & cache update is discarded
    failingStore.allowWrites = false;
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("Charmander"));
    get(serverUri)
        .assertConditionalMiss()
        .assertBody("Charmander");

    // Stale cache response is still there
    var request = GET(serverUri).header("Cache-Control", "max-stale=1");
    get(request)
        .assertHit()
        .assertBody("Pickachu")
        .assertHeader("Age", "2")
        .assertHeader("Warning", "110 - \"Response is Stale\"");
  }

  @StoreParameterizedTest
  void errorsWhileReadingArePropagated(Store store) throws Exception {
    var failingStore = new FailingStore(store);
    failingStore.allowWrites = true;
    setUpCache(failingStore);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("Pickachu"));
    seedCache(serverUri);

    // Read failure is propagated
    assertThatThrownBy(() -> get(serverUri)).isInstanceOf(TestException.class);
  }

  @StoreParameterizedTest
  void uriIterator(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("a"));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("b"));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("c"));
    seedCache(serverUri.resolve("/a"));
    seedCache(serverUri.resolve("/b"));
    seedCache(serverUri.resolve("/c"));

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
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("a"));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("b"));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("c"));
    seedCache(serverUri.resolve("/a"));
    seedCache(serverUri.resolve("/b"));
    seedCache(serverUri.resolve("/c"));

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

    get(serverUri.resolve("/b"))
        .assertHit()
        .assertBody("b");
  }

  @StoreParameterizedTest
  void recordStats(Store store) throws Exception {
    setUpCache(store);
    server.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest recordedRequest) {
        var path = recordedRequest.getRequestUrl().pathSegments().get(0);
        switch (path) {
          case "hit":
            return new MockResponse().addHeader("Cache-Control", "max-age=60");
          case "miss":
            return new MockResponse().addHeader("Cache-Control", "no-store");
          default:
            return fail("unexpected path: " + path);
        }
      }
    });

    var hitUri = serverUri.resolve("/hit");
    var missUri = serverUri.resolve("/miss");

    // requestCount = 1, missCount = 1, networkUseCount = 1
    get(hitUri).assertMiss();

    // requestCount = 2, hitCount = 1
    get(hitUri).assertHit();

    // requestCount = 3, missCount = 2, networkUseCount = 2
    get(missUri).assertMiss();

    // requestCount = 13, missCount = 12, networkUseCount = 12
    for (int i = 0; i < 10; i++) {
      get(missUri).assertMiss();
    }

    assertThat(cache.remove(hitUri)).isTrue();

    // requestCount = 14, missCount = 13, networkUseCount = 13
    get(hitUri).assertMiss();

    // requestCount = 24, hitCount = 11
    for (int i = 0; i < 10; i++) {
      get(hitUri).assertHit();
    }

    // requestCount = 25, missCount = 14 (no network)
    get(GET(missUri).header("Cache-Control", "only-if-cached"))
        .assertUnsatisfiable();

    var stats = cache.stats();
    assertThat(stats.requestCount()).isEqualTo(25);
    assertThat(stats.hitCount()).isEqualTo(11);
    assertThat(stats.missCount()).isEqualTo(14);
    assertThat(stats.networkUseCount()).isEqualTo(13);
    assertThat(stats.hitRate()).isEqualTo(11 / 25.0);
    assertThat(stats.missRate()).isEqualTo(14 / 25.0);

    // Check that per URI stats aren't recorded by default
    assertThat(cache.stats(hitUri)).isEqualTo(Stats.empty());
    assertThat(cache.stats(missUri)).isEqualTo(Stats.empty());
  }

  @StoreParameterizedTest
  void perUriStats(Store store) throws Exception {
    setUpCache(store, StatsRecorder.createConcurrentPerUriRecorder());
    server.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest recordedRequest) {
        return new MockResponse().addHeader("Cache-Control", "max-age=2");
      }
    });

    var aUri = serverUri.resolve("/a");
    var bUri = serverUri.resolve("/b");

    // a.requestCount = 1, a.missCount = 1, a.networkUseCount = 1
    get(aUri).assertMiss();

    // a.requestCount = 2, a.hitCount = 1
    get(aUri).assertHit();

    // a.requestCount = 3, a.missCount = 2, a.networkUseCount = 2
    get(GET(aUri).header("Cache-Control", "no-cache"))
        .assertConditionalMiss();

    // a.requestCount = 4, a.hitCount = 2
    get(aUri).assertHit();

    assertThat(cache.remove(aUri)).isTrue();

    // a.requestCount = 5, a.missCount = 3, a.networkUseCount = 2 (network isn't accessed)
    assertNotCached(aUri);

    // b.requestCount = 1, b.missCount = 1, b.networkUseCount = 1
    get(bUri).assertMiss();

    // b.requestCount = 6, b.missCount = 6, b.networkUseCount = 6
    for (int i = 0; i < 5; i++) {
      get(GET(bUri).header("Cache-Control", "no-cache")).assertConditionalMiss();
    }

    // b.requestCount = 7, b.hitCount = 1
    get(bUri).assertHit();

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
    setUpCache(failingStore, StatsRecorder.createConcurrentPerUriRecorder());
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest recordedRequest) {
        return new MockResponse()
            .addHeader("Cache-Control", "max-age=1")
            .setBody("Pickachu");
      }
    });

    failingStore.allowWrites = true;

    // writeSuccessCount = 1, a.writeSuccessCount = 1
    seedCache(serverUri.resolve("/a"));

    assertThat(cache.remove(serverUri.resolve("/a"))).isTrue();

    // writeSuccessCount = 2, a.writeSuccessCount = 2
    seedCache(serverUri.resolve("/a"));

    // writeSuccessCount = 3, b.writeSuccessCount = 1
    seedCache(serverUri.resolve("/b"));

    failingStore.allowWrites = false;

    assertThat(cache.remove(serverUri.resolve("/b"))).isTrue();

    // writeFailureCount = 1, b.writeFailureCount = 1
    seedCache(serverUri.resolve("/b"));

    // writeFailureCount = 2, c.writeFailureCount = 1
    seedCache(serverUri.resolve("/c"));

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
    seedCache(serverUri);
    get(serverUri);
    assertThat(cache.stats()).isEqualTo(Stats.empty());
    assertThat(cache.stats(serverUri)).isEqualTo(Stats.empty());
  }

  @StoreParameterizedTest
  void compressedCacheResponse(Store store) throws Exception {
    setUpCache(store);

    var gzippedBytes = gzip("Will Smith");
    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "max-age=1")
        .setHeader("Content-Encoding", "gzip")
        .setBody(new okio.Buffer().write(gzippedBytes)));
    seedCache(serverUri);
    get(serverUri)
        .assertCode(200)
        .assertHit()
        .assertBody("Will Smith")
        .assertAbsentHeader("Content-Encoding")
        .assertAbsentHeader("Content-Length");
  }

  private ResponseVerifier<String> get(URI uri) throws IOException, InterruptedException {
    return get(GET(uri));
  }

  private ResponseVerifier<String> getUnchecked(HttpRequest request) {
    try {
      return get(request);
    } catch (IOException | InterruptedException e) {
      return Assertions.fail(e);
    }
  }

  private ResponseVerifier<String> get(HttpRequest request)
      throws IOException, InterruptedException {
    var response = client.send(request, BodyHandlers.ofString());
    editAwaiter.await();
    return verifying(response);
  }

  private ResponseVerifier<String> getWithPushHandler(URI uri) {
    var response = client.sendAsync(
        GET(uri),
        BodyHandlers.ofString(),
        PushPromiseHandler.of(__ -> BodyHandlers.ofString(), new ConcurrentHashMap<>())).join();
    editAwaiter.await();
    return verifying(response);
  }

  private ResponseVerifier<String> seedCache(URI uri)
      throws IOException, InterruptedException {
    return get(uri).assertMiss();
  }

  private ResponseVerifier<String> seedCache(HttpRequest request)
      throws IOException, InterruptedException {
    return get(request).assertMiss();
  }

  /**
   * Ensures requests to serverUri result in a cache hit, retrying if necessary in case a stale
   * response is being updated in background.
   */
  private ResponseVerifier<String> awaitCacheHit() {
    var request = GET(serverUri).header("Cache-Control", "max-stale=0, only-if-cached");
    return await()
        .atMost(ofMinutes(2))
        .until(() -> get(request), response -> response.hasCacheStatus(HIT));
  }

  private void assertNotCached(URI uri) throws Exception {
    assertNotCached(GET(uri));
  }

  private void assertNotCached(MutableRequest request) throws Exception {
    var cacheControl = CacheControl.newBuilder()
        .onlyIfCached()
        .anyMaxStale()
        .build();
    get(request.cacheControl(cacheControl)).assertUnsatisfiable();
  }

  private static LocalDateTime toUtcDateTime(Instant instant) {
    return LocalDateTime.ofInstant(instant, UTC);
  }

  private static String formatInstant(Instant instant) {
    return formatHttpDate(toUtcDateTime(instant));
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
    @Nullable
    public Viewer view(String key) throws IOException {
      return delegate.view(key);
    }

    @Override
    @Nullable
    public Editor edit(String key) throws IOException {
      return delegate.edit(key);
    }

    @Override
    public Iterator<Viewer> iterator() throws IOException {
      return delegate.iterator();
    }

    @Override
    public boolean remove(String key) throws IOException {
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
    final Editor delegate;

    ForwardingEditor(Editor delegate) {
      this.delegate = delegate;
    }

    @Override
    public String key() {
      return delegate.key();
    }

    @Override
    public void metadata(ByteBuffer metadata) {
      delegate.metadata(metadata);
    }

    @Override
    public CompletableFuture<Integer> writeAsync(long position, ByteBuffer src) {
      return delegate.writeAsync(position, src);
    }

    @Override
    public void commitOnClose() {
      delegate.commitOnClose();
    }

    @Override
    public void close() throws IOException {
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
    public CompletableFuture<Integer> readAsync(long position, ByteBuffer dst) {
      return delegate.readAsync(position, dst);
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
    @Nullable
    public Editor edit() throws IOException {
      return delegate.edit();
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
   * completion wait for the whole body to be written. So if writes take time (happens with
   * DiskStore) the response entry is committed a while after the response is completed. This
   * however agitates tests as they expect things to happen sequentially. This is solved by waiting
   * for all open editors to close after a client.send(...) is issued.
   */
  private static final class EditAwaiter {
    private final Phaser phaser = new Phaser(1); // Register self

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

    /**
     * An Editor that notifies (arrives at) a Phaser when closed, allowing to await its closure
     * among others'.
     */
    private static final class NotifyingEditor extends ForwardingEditor {
      private final Phaser phaser;

      NotifyingEditor(Editor delegate, Phaser phaser) {
        super(delegate);
        this.phaser = phaser;
        phaser.register(); // Register self
      }

      @Override
      public void close() throws IOException {
        try {
          delegate.close();
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
    public @Nullable Viewer view(String key) throws IOException {
      var v = delegate.view(key);
      return v != null ? new EditAwaiterViewer(v) : null;
    }

    @Override
    public @Nullable Editor edit(String key) throws IOException {
      var e = delegate.edit(key);
      return e != null ? editAwaiter.register(e) : null;
    }

    private final class EditAwaiterViewer extends ForwardingViewer {
      EditAwaiterViewer(Viewer delegate) {
        super(delegate);
      }

      @Override
      public @Nullable Editor edit() throws IOException {
        var e = delegate.edit();
        return e != null ? editAwaiter.register(e) : null;
      }
    }
  }
}
