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
import static com.github.mizosoft.methanol.testing.ResponseVerification.verifying;
import static com.github.mizosoft.methanol.testing.StoreConfig.FileSystemType.SYSTEM;
import static com.github.mizosoft.methanol.testing.StoreConfig.StoreType.DISK;
import static com.github.mizosoft.methanol.testutils.TestUtils.deflate;
import static com.github.mizosoft.methanol.testutils.TestUtils.gzip;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static java.time.Duration.ofDays;
import static java.time.Duration.ofHours;
import static java.time.Duration.ofSeconds;
import static java.time.ZoneOffset.UTC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.mizosoft.methanol.Methanol.Interceptor;
import com.github.mizosoft.methanol.internal.cache.CacheInterceptor;
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
import com.github.mizosoft.methanol.testing.ResponseVerification;
import com.github.mizosoft.methanol.testing.StoreConfig;
import com.github.mizosoft.methanol.testing.StoreContext;
import com.github.mizosoft.methanol.testing.StoreExtension;
import com.github.mizosoft.methanol.testing.StoreExtension.StoreParameterizedTest;
import com.github.mizosoft.methanol.testutils.Logging;
import com.github.mizosoft.methanol.testutils.MockClock;
import com.github.mizosoft.methanol.testutils.TestException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URI;
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
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
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

  private static final int MAX_RETRY_COUNT = 60;

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
    editAwaiter = new EditAwaiter();
    cache = HttpCache.newBuilder()
        .clockForTesting(clock)
        .storeForTesting(new EditAwaiterStore(store, editAwaiter))
        .executor(threadPool)
        .build();
    client = clientBuilder.cache(cache).build();
  }

  @AfterEach
  void tearDown() throws IOException {
    if (cache != null) {
      cache.close();
    }
  }

  @Test
  void buildWithMemoryStore() {
    var cache = HttpCache.newBuilder()
        .cacheOnMemory(12L)
        .build();
    var store = cache.storeForTesting();
    assertTrue(store instanceof MemoryStore);
    assertEquals(12L, store.maxSize());
    assertEquals(Optional.empty(), store.executor());
    assertEquals(Optional.empty(), cache.directory());
    assertEquals(12L, cache.maxSize());
  }

  @Test
  void buildWithDiskStore() {
    var cache = HttpCache.newBuilder()
        .cacheOnDisk(Path.of("cache_dir"), 12L)
        .executor(r -> { throw new RejectedExecutionException("NO!"); })
        .build();
    var store = cache.storeForTesting();
    assertTrue(store instanceof DiskStore);
    assertEquals(12L, store.maxSize());
    assertEquals(Optional.of(Path.of("cache_dir")), cache.directory());
    assertEquals(cache.executor(), store.executor());
    assertEquals(12L, cache.maxSize());
  }

  @StoreParameterizedTest
  @StoreConfig
  void cacheGetWithMaxAge(Store store) throws Exception {
    setUpCache(store);
    assertGetIsCached(ofSeconds(1), "Cache-Control", "max-age=2");
  }

  @StoreParameterizedTest
  @StoreConfig
  void cacheGetWithExpires(Store store) throws Exception {
    setUpCache(store);
    var now = toUtcDateTime(clock.instant());
    assertGetIsCached(
        ofHours(12),                      // Advance clock half a day
        "Expires",
        formatHttpDate(now.plusDays(1))); // Expire a day from "now"
  }

  @StoreParameterizedTest
  @StoreConfig
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
  @StoreConfig
  @UseHttps
  void cacheSecureGetWithMaxAge(Store store) throws Exception {
    setUpCache(store);
    assertGetIsCached(ofSeconds(1), "Cache-Control", "max-age=2")
        .assertCachedWithSSL();
  }

  private ResponseVerification<String> assertGetIsCached(
      Duration clockAdvance, String... headers)
      throws Exception {
    server.enqueue(new MockResponse()
        .setHeaders(Headers.of(headers))
        .setBody("Is you is or is you ain't my baby?"));

    get(serverUri)
        .assertMiss()
        .assertBody("Is you is or is you ain't my baby?");

    clock.advance(clockAdvance);

    return get(serverUri)
        .assertHit()
        .assertBody("Is you is or is you ain't my baby?");
  }

  @StoreParameterizedTest
  @StoreConfig
  void cacheGetWithExpiresConditionalHit(Store store) throws Exception {
    setUpCache(store);
    // Expire one day from "now"
    var oneDayFromNow = clock.instant().plus(ofDays(1));
    server.enqueue(new MockResponse()
        .addHeader("Expires", formatHttpDate(toUtcDateTime(oneDayFromNow)))
        .setBody("Is you is or is you ain't my baby?"));
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));

    get(serverUri)
        .assertMiss()
        .assertBody("Is you is or is you ain't my baby?");

    clock.advance(ofDays(2)); // Make response stale

    get(serverUri)
        .assertConditionalHit()
        .assertBody("Is you is or is you ain't my baby?");
  }

  @StoreParameterizedTest
  @StoreConfig
  @UseHttps
  void secureCacheGetWithExpiresConditionalHit(Store store) throws Exception {
    setUpCache(store);
    // Expire one day from "now"
    var oneDayFromNow = clock.instant().plus(ofDays(1));
    server.enqueue(new MockResponse()
        .addHeader("Expires", formatHttpDate(toUtcDateTime(oneDayFromNow)))
        .setBody("Is you is or is you ain't my baby?"));
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));

    get(serverUri)
        .assertMiss()
        .assertBody("Is you is or is you ain't my baby?");

    clock.advance(ofDays(2)); // Make response stale

    get(serverUri)
        .assertConditionalHit()
        .assertBody("Is you is or is you ain't my baby?")
        .assertCachedWithSSL();
  }

  @StoreParameterizedTest
  @StoreConfig
  void responseIsFreshenedOnConditionalHit(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        // Warning 113 will be stored (e.g. may come from a proxy's cache)
        // but will be removed on freshening
        .addHeader("Warning", "113 - \"Heuristic Expiration\"")
        .addHeader("X-Version", "v1")
        .addHeader("Content-Type", "text/plain")
        .addHeader("Cache-Control", "max-age=1")
        .setBody("Youse is still my baby, baby"));
    server.enqueue(new MockResponse()
        .setResponseCode(HTTP_NOT_MODIFIED)
        .addHeader("X-Version", "v2"));
    seedCache(serverUri);

    clock.advanceSeconds(2); // Make response stale

    var instantRevalidationReceived = clock.instant();
    get(serverUri)
        .assertConditionalHit()
        .assertBody("Youse is still my baby, baby");

    // Check that response metadata is updated, which is done in
    // background, so keep trying a number of times.
    retryTillOfflineHit()
        .assertHit()
        .assertBody("Youse is still my baby, baby")
        .assertHeader("X-Version", "v2")
        .assertRequestSentAt(instantRevalidationReceived)
        .assertResponseReceivedAt(instantRevalidationReceived);
  }

  @StoreParameterizedTest
  @StoreConfig
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
    var instantRevalidationReceived = clock.instant();
    get(serverUri)
        .assertConditionalHit()
        .assertBody("Pickachu")
        .assertHeader("X-Version", "v2")
        .assertHeader("Content-Length", "Pickachu".length())
        .networkResponse()
        .assertHeader("Content-Length", "0");

    retryTillOfflineHit()
        .assertHit()
        .assertBody("Pickachu")
        .assertHeader("X-Version", "v2")
        .assertHeader("Content-Length", "Pickachu".length())
        .assertRequestSentAt(instantRevalidationReceived)
        .assertResponseReceivedAt(instantRevalidationReceived);
  }

  @StoreParameterizedTest
  @StoreConfig
  void prohibitNetworkOnRequiredValidation(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("123"));
    seedCache(serverUri);

    clock.advanceSeconds(2); // Make response stale

    get(GET(serverUri).header("Cache-Control", "only-if-cached"))
        .assertLocallyGenerated()
        .assertBody(""); // Doesn't have a body
  }

  /* As encouraged by rfc 7232 2.4, both validators are added in case an
     HTTP/1.0 recipient is present along the way that doesn't know about ETags. */

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
  @StoreConfig
  void revalidationFromStale(StoreContext storeContext) throws Throwable {
    testForEachValidator(storeContext, config -> {
      var request = GET(serverUri);
      assertRevalidation(request, config, true);
    });
  }

  @StoreParameterizedTest
  @StoreConfig
  void failedRevalidationFromStale(StoreContext storeContext) throws Throwable {
    testForEachValidator(storeContext, config -> {
      var request = GET(serverUri);
      assertFailedRevalidation(request, config, true);
    });
  }

  @StoreParameterizedTest
  @StoreConfig
  void revalidationForcedByNoCache(StoreContext storeContext) throws Throwable {
    testForEachValidator(storeContext, config -> {
      var request = GET(serverUri).header("Cache-Control", "no-cache");
      assertRevalidation(request, config, false);
    });
  }

  @StoreParameterizedTest
  @StoreConfig
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
    clock.advanceSeconds(1); // Make Last-Modified 1 seconds prior to "now"

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
    assertEquals(effectiveLastModified, sentRequest.getHeaders().getInstant("If-Modified-Since"));
    if (config.etag) {
      assertEquals("1", sentRequest.getHeader("If-None-Match"));
    }
  }

  private void assertFailedRevalidation(
      HttpRequest triggeringRequest,
      ValidatorConfig config,
      boolean makeStale) throws IOException, InterruptedException {
    var validators1 = config.getValidators(1, clock.instant());
    clock.advanceSeconds(1);
    // Use different etag and Last-Modified
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

    clock.advanceSeconds(1); // Updated response is still fresh

    get(serverUri)
        .assertHit()
        .assertBody("DOUBLE STONKS!")
        .assertHeader("X-Version", "v2")
        .assertHasHeaders(validators2.toMultimap());

    var sentRequest = server.takeRequest();
    // Date received is used if Last-Modified is absent
    var effectiveLastModified =
        config.lastModified ? validators1.getInstant("Last-Modified") : instantInitiallyReceived;
    assertEquals(effectiveLastModified, sentRequest.getHeaders().getInstant("If-Modified-Since"));
    if (config.etag) {
      assertEquals("1", sentRequest.getHeader("If-None-Match"));
    }
  }

  @StoreParameterizedTest
  @StoreConfig
  void preconditionFieldsAreNotVisibleOnServedResponse(Store store) throws Exception {
    setUpCache(store);
    var lastModifiedString = formatHttpDate(toUtcDateTime(clock.instant().minusSeconds(1)));
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
  @StoreConfig
  void lastModifiedDefaultsToDateWhenRevalidating(Store store) throws Exception {
    setUpCache(store);
    var dateInstant = clock.instant();
    clock.advanceSeconds(1);

    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Date", formatHttpDate(toUtcDateTime(dateInstant)))
        .setBody("FLUX"));
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    seedCache(serverUri);
    server.takeRequest(); // Remove initial request

    clock.advanceSeconds(2); // Make stale

    get(serverUri)
        .assertConditionalHit()
        .assertBody("FLUX");

    var sentRequest = server.takeRequest();
    assertEquals(dateInstant, sentRequest.getHeaders().getInstant("If-Modified-Since"));
  }

  @StoreParameterizedTest
  @StoreConfig
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
  @StoreConfig
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
  @StoreConfig
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
  @StoreConfig
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
  @StoreConfig
  void constrainFreshnessWithMinFresh(Store store) throws Exception {
    setUpCache(store);
    // Last-Modified: 2 seconds from "now"
    var lastModifiedInstant = clock.instant();
    clock.advanceSeconds(2);

    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=3")
        .addHeader("Last-Modified", formatHttpDate(toUtcDateTime(lastModifiedInstant)))
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
        .addHeader("Last-Modified", formatHttpDate(toUtcDateTime(lastModifiedInstant)))
        .setBody("tesla"));
    var request2 = GET(serverUri).header("Cache-Control", "min-fresh=3");
    get(request2) // min-fresh unsatisfied
        .assertConditionalMiss()
        .assertBody("tesla");

    var sentRequest = server.takeRequest();
    assertEquals(lastModifiedInstant, sentRequest.getHeaders().getInstant("If-Modified-Since"));
  }

  @StoreParameterizedTest
  @StoreConfig
  void acceptingStalenessWithMaxStale(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("stale on a scale"));
    seedCache(serverUri);

    clock.advanceSeconds(3); // Make stale by 2 seconds

    BiConsumer<CacheControl, UnaryOperator<ResponseVerification<String>>> assertStaleness =
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
    assertStaleness.accept(CacheControl.parse("max-stale"), ResponseVerification::assertHit);

    // Allow 3 seconds of staleness -> HIT
    assertStaleness.accept(CacheControl.parse("max-stale=3"), ResponseVerification::assertHit);

    // Allow 2 seconds of staleness -> HIT
    assertStaleness.accept(CacheControl.parse("max-stale=2"), ResponseVerification::assertHit);

    // Allow 1 second of staleness -> CONDITIONAL_HIT as staleness is 2
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    assertStaleness.accept(
        CacheControl.parse("max-stale=1"), ResponseVerification::assertConditionalHit);
  }

  @StoreParameterizedTest
  @StoreConfig
  void imposeRevalidationWhenStaleByMustRevalidate(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1, must-revalidate")
        .setBody("popeye"));
    seedCache(serverUri);

    clock.advanceSeconds(2); // Make stale by 1 secs

    // A revalidation is made despite max-stale=2
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    var requestMaxStale2Secs = GET(serverUri).header("Cache-Control", "max-stale=2");
    get(requestMaxStale2Secs).assertConditionalHit();
  }

  @StoreParameterizedTest
  @StoreConfig
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
  @StoreConfig
  void preventCachingByNoStore(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "no-store")
        .setBody("alpha"));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "no-store")
        .setBody("alpha"));
    seedCache(serverUri.resolve("/a")); // Offer to cache
    get(serverUri.resolve("/a"))
        .assertMiss() // Not cached
        .assertBody("alpha");

    // The request can also prevent caching even if the response is cacheable
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("beta"));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("beta"));
    var request = GET(serverUri.resolve("/b")).header("Cache-Control", "no-store");
    seedCache(request); // Offer to cache
    get(request)
        .assertMiss() // Not cached
        .assertBody("beta");
  }

  @StoreParameterizedTest
  @StoreConfig
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
  @StoreConfig
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

  @StoreParameterizedTest
  @StoreConfig
  void responsesVaryingOnImplicitHeadersAreNotStored(Store store) throws Exception {
    setUpCache(store);
    for (var field : CacheInterceptor.implicitlyAddedFieldsForTesting()) {
      server.enqueue(new MockResponse()
          .addHeader("Cache-Control", "max-age=1")
          .addHeader("Vary", "Accept-Encoding, " + field)
          .setBody("aaa"));
      seedCache(serverUri); // Offer to cache

      var request = GET(serverUri).header("Cache-Control", "only-if-cached");
      get(request).assertLocallyGenerated(); // Not cached!
    }
  }

  @StoreParameterizedTest
  @StoreConfig
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
  @StoreConfig
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
    get(withTextHtml).assertLocallyGenerated();

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
    get(withApplicationJson).assertLocallyGenerated();

    // Absent varying fields won't match a request containing them
    seedCache(serverUri);
    get(serverUri)
        .assertHit()
        .assertHeader("Content-Language", "en-US")
        .assertBody("Lit!");
  }

  @StoreParameterizedTest
  @StoreConfig
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

    // This doesn't match as there's 2 values vs alpha variant's 3
    var requestBeta2 = GET(serverUri)
        .header("My-Header", "val1")
        .header("My-Header", "val2");
    seedCache(requestBeta2);
    get(requestBeta2)
        .assertHit()
        .assertBody("beta");

    // TODO this should probably match but it currently doesn't
//    var requestCharlie = GET(serverUri)
//        .header("My-Header", "val1, val3");
//    assertEquals("beta", assertHit(getString(request4)).body());

    // Request with no values doesn't match
    seedCache(serverUri);
    get(serverUri)
        .assertHit()
        .assertBody("charlie");
  }

  @StoreParameterizedTest
  @StoreConfig
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
  @StoreConfig
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
  @StoreConfig
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
        .assertHit(); // Redirect response is cached

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
    var redirectResponse = new MockResponse()
        .setResponseCode(code)
        .setHeader("Location", "/redirect");
    if (code == 301) {
      // 301 is cacheable by default so explicitly disallow caching
      redirectResponse.setHeader("Cache-Control", "no-store");
    }
    server.enqueue(redirectResponse);
    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "max-age=1")
        .setBody("Wakanda forever"));
    seedCache(serverUri);

    server.enqueue(redirectResponse);
    get(serverUri)
        .assertCode(200)
        .assertHit() // Target response is cached
        .assertBody("Wakanda forever")
        .previousResponse()
        .assertCode(code)
        .assertMiss(); // Redirect response isn't cached

    // Disable auto redirection
    client = clientBuilder.followRedirects(Redirect.NEVER).build();

    server.enqueue(redirectResponse);
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
    var redirectResponse = new MockResponse()
        .setResponseCode(code)
        .setHeader("Location", "/redirect");
    if (code == 301) {
      // 301 is cacheable by default so explicitly disallow caching
      redirectResponse.setHeader("Cache-Control", "no-store");
    }
    server.enqueue(redirectResponse);
    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "no-store")
        .setBody("Wakanda forever"));
    seedCache(serverUri);

    server.enqueue(redirectResponse);
    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "no-store")
        .setBody("Wakanda forever"));
    get(serverUri)
        .assertCode(200)
        .assertMiss() // Target response isn't cached
        .assertBody("Wakanda forever")
        .previousResponse()
        .assertCode(code)
        .assertMiss(); // Redirect response isn't cached

    // Disable auto redirection
    client = clientBuilder.followRedirects(Redirect.NEVER).build();

    server.enqueue(redirectResponse);
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
        .assertHit(); // Redirect response is cached

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

  @StoreParameterizedTest
  @StoreConfig
  void staleWhileRevalidate(Store store) throws Exception {
    setUpCache(store);
    var dateInstant = clock.instant();
    var lastModifiedInstant = dateInstant.minusSeconds(1);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1, stale-while-revalidate=2")
        .addHeader("ETag", "1")
        .addHeader("Last-Modified", formatHttpDate(toUtcDateTime(lastModifiedInstant)))
        .addHeader("Date", formatHttpDate(toUtcDateTime(dateInstant)))
        .setBody("Pickachu"));
    seedCache(serverUri);
    get(serverUri).assertHit();
    server.takeRequest(); // Remove initial request

    clock.advanceSeconds(3); // Make response stale by 2 seconds

    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1, stale-while-revalidate=2")
        .addHeader("ETag", "2")
        .addHeader("Last-Modified", formatHttpDate(toUtcDateTime(clock.instant().minusSeconds(1))))
        .addHeader("Date", formatHttpDate(toUtcDateTime(clock.instant())))
        .setBody("Ricardo"));

    get(serverUri)
        .assertHit()
        .assertBody("Pickachu")
        .assertHeader("ETag", "1")
        .assertHeader("Warning", "110 - \"Response is Stale\"")
        .assertHeader("Age", "3");

    // A revalidation request is sent in background
    var sentRequest = server.takeRequest();
    assertEquals("1", sentRequest.getHeader("If-None-Match"));
    assertEquals(lastModifiedInstant, sentRequest.getHeaders().getInstant("If-Modified-Since"));

    // Retry till revalidation response completes, causing the cached response to be updated
    retryTillOfflineHit()
        .assertHit()
        .assertBody("Ricardo")
        .assertHeader("ETag", "2")
        .assertAbsentHeader("Warning") // No warnings
        .assertHeader("Age", "0");
  }

  @StoreParameterizedTest
  @StoreConfig
  void unsatisfiedStaleWhileRevalidate(Store store) throws Exception {
    setUpCache(store);
    var dateInstant = clock.instant();
    var lastModifiedInstant = dateInstant.minusSeconds(1);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1, stale-while-revalidate=2")
        .addHeader("ETag", "1")
        .addHeader("Last-Modified", formatHttpDate(toUtcDateTime(lastModifiedInstant)))
        .addHeader("Date", formatHttpDate(toUtcDateTime(dateInstant)))
        .setBody("Pickachu"));
    seedCache(serverUri); // Put in cache
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
        .addHeader("Last-Modified", formatHttpDate(toUtcDateTime(clock.instant().minusSeconds(1))))
        .addHeader("Date", formatHttpDate(toUtcDateTime(clock.instant())))
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
        .assertConditionalMiss()
        .assertCode(code)
        .assertBody("") // No body in the error response
        .assertAbsentHeader("Warning");
  }

  private static final class FailingInterceptor implements Interceptor {
    private final Supplier<Throwable> failure;

    FailingInterceptor(Supplier<Throwable> failure) {
      this.failure = failure;
    }

    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain) {
      throw throwUnchecked(failure.get());
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
        HttpRequest request, Chain<T> chain) {
      return CompletableFuture.failedFuture(failure.get());
    }

    @SuppressWarnings("unchecked")
    private static <X extends Throwable> X throwUnchecked(Throwable t) throws X {
      throw (X) t;
    }
  }

  @StoreParameterizedTest
  @StoreConfig
  void staleIfErrorWithConnectionFailure(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1, stale-if-error=2")
        .setBody("Jigglypuff"));
    seedCache(serverUri);
    get(serverUri).assertHit();

    clock.advanceSeconds(2); // Make response stale by 1 second

    // Make requests fail with a ConnectException
    client = clientBuilder
        .backendInterceptor(new FailingInterceptor(ConnectException::new))
        .build();

    get(serverUri)
        .assertHit()
        .assertCode(200)
        .assertBody("Jigglypuff")
        .assertHeader("Warning", "110 - \"Response is Stale\"");

    clock.advanceSeconds(1); // Make response stale by 2 seconds

    // stale-if-error is still satisfied
    server.enqueue(new MockResponse().setBody("huh?"));
    get(serverUri)
        .assertHit()
        .assertCode(200)
        .assertBody("Jigglypuff")
        .assertHeader("Warning", "110 - \"Response is Stale\"");
  }

  @StoreParameterizedTest
  @StoreConfig
  void unsatisfiedStaleIfErrorWithConnectionFailure(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1, stale-if-error=1")
        .setBody("Ricardo"));
    seedCache(serverUri);
    get(serverUri).assertHit();

    clock.advanceSeconds(3); // Make response stale by 2 seconds

    // Make requests fail with a ConnectException
    client = clientBuilder
        .backendInterceptor(new FailingInterceptor(ConnectException::new))
        .build();

    // stale-if-error isn't satisfied
    assertThrows(IOException.class, () -> get(serverUri));
  }

  @StoreParameterizedTest
  @StoreConfig
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
        .assertConditionalMiss()
        .assertCode(404)
        .assertBody(""); // Error response has no body
  }

  @StoreParameterizedTest
  @StoreConfig
  void staleIfErrorWithInapplicableException(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1, stale-if-error=1")
        .setBody("Charmander"));
    seedCache(serverUri);
    get(serverUri).assertHit();

    // Make requests fail with a non-IOException
    client = clientBuilder
        .backendInterceptor(new FailingInterceptor(TestException::new))
        .build();

    clock.advanceSeconds(2); // Make response stale by 1 second

    // Exception is rethrown as stale-if-error isn't satisfied
    assertThrows(TestException.class, () -> get(serverUri));
  }

  @StoreParameterizedTest
  @StoreConfig
  void staleIfErrorWithUncheckedIOException(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1, stale-if-error=1")
        .setBody("Jynx"));
    seedCache(serverUri);
    get(serverUri).assertHit();


    // Make requests fail with UncheckedIOException
    client = clientBuilder
        .backendInterceptor(
            new FailingInterceptor(() -> new UncheckedIOException(new ConnectException())))
        .build();

    clock.advanceSeconds(2); // Make response stale by 1 second

    // UncheckedIOException is treated as IOException -> stale-if-error is satisfied
    var request = GET(serverUri).header("Cache-Control", "stale-if-error=2");
    get(request)
        .assertHit()
        .assertCode(200)
        .assertBody("Jynx")
        .assertHeader("Warning", "110 - \"Response is Stale\"");
  }

  @StoreParameterizedTest
  @StoreConfig
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
        .assertHit()
        .assertCode(200)
        .assertBody("Psyduck");

    // Refresh response
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    get(serverUri).assertConditionalHit();

    clock.advanceSeconds(3); // Make response stale by 2 seconds

    // Response's stale-if-error is satisfied but that of request isn't
    server.enqueue(new MockResponse().setResponseCode(500));
    var request2 = GET(serverUri).header("Cache-Control", "stale-if-error=1");
    get(request2)
        .assertConditionalMiss()
        .assertCode(500)
        .assertBody("");
  }

  @StoreParameterizedTest
  @StoreConfig
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
    var lastModified = toUtcDateTime(clock.instant());
    clock.advanceSeconds(20);
    var date = toUtcDateTime(clock.instant());

    var body =
        code == 204 || code == 304 ? "" : "Cache me pls!"; // Body with 204 or 304 causes problems
    server.enqueue(new MockResponse()
        .setResponseCode(code)
        .addHeader("Last-Modified", formatHttpDate(lastModified))
        .addHeader("Date", formatHttpDate(date))
        .setBody(body));
    seedCache(serverUri);

    clock.advanceSeconds(1); // Heuristic freshness retained

    ResponseVerification<String> response;
    if (cacheableByDefault) {
      response = get(serverUri).assertHit();
    } else {
      server.enqueue(new MockResponse()
          .setResponseCode(code)
          .addHeader("Last-Modified", formatHttpDate(lastModified))
          .addHeader("Date", formatHttpDate(date))
          .setBody(body));
      response = get(serverUri).assertMiss();
    }
    response.assertCode(code)
        .assertBody(body);
  }

  @StoreParameterizedTest
  @StoreConfig
  void heuristicExpiration(Store store) throws Exception {
    setUpCache(store);
    // Last-Modified:      20 seconds from date
    // Heuristic lifetime: 2 seconds
    // Age:                1 second
    var lastModified = toUtcDateTime(clock.instant());
    clock.advanceSeconds(20);
    var date = toUtcDateTime(clock.instant());
    clock.advanceSeconds(1);

    server.enqueue(new MockResponse()
        .addHeader("Last-Modified", formatHttpDate(lastModified))
        .addHeader("Date", formatHttpDate(date))
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
  @StoreConfig
  void warningOnHeuristicFreshnessWithAgeGreaterThanOneDay(Store store) throws Exception {
    setUpCache(store);
    // Last-Modified:      20 days from date
    // Heuristic lifetime: 2 days
    var lastModified = toUtcDateTime(clock.instant());
    clock.advance(ofDays(20));
    var date = toUtcDateTime(clock.instant());

    server.enqueue(new MockResponse()
        .addHeader("Last-Modified", formatHttpDate(lastModified))
        .addHeader("Date", formatHttpDate(date))
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

  @StoreParameterizedTest
  @StoreConfig
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
          .assertHit()
          .assertCode(200)
          .assertBody("Pickachu");
    }
  }

  /**
   * Test that an invalidated response causes the URIs referenced via Location & Content-Location
   * to also get invalidated.
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
    var unsafeRequest = MutableRequest.create(serverUri).method(method, BodyPublishers.noBody());
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

    var unsafeRequest = MutableRequest.create(serverUri).method(method, BodyPublishers.noBody());
    seedCache(unsafeRequest).assertBody("Pickachu"); // Offer to cache

    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=2")
        .setBody("Ditto"));
    get(serverUri)
        .assertMiss()
        .assertBody("Ditto"); // Unsafe request's response isn't cached
  }

  @StoreParameterizedTest
  @StoreConfig
  void headOfCachedGet(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=2")
        .setBody("Mewtwo"));
    seedCache(serverUri);

    server.enqueue(new MockResponse().addHeader("Cache-Control", "max-age=2"));
    var head = MutableRequest.create(serverUri).method("HEAD", BodyPublishers.noBody());
    get(head).assertMiss();

    get(serverUri)
        .assertHit()
        .assertBody("Mewtwo");
  }

  @UseHttps
  @StoreParameterizedTest
  @StoreConfig
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
    // The request isn't served by the cache as it doesn't know what might be pushed.
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
        request.header(field, formatHttpDate(toUtcDateTime(clock.instant().minusSeconds(3))));
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
      var set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
      set.addAll(Set.of(fields));
      this.fields = Collections.unmodifiableSet(set);
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
  @StoreConfig
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
  @StoreConfig
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

    assertTrue(cache.remove(uri1));
    get(uri1).assertMiss(); // Reinserts the response

    assertTrue(cache.remove(MutableRequest.GET(uri2)));
    get(uri2).assertMiss(); // Reinserts the response

    get(uri1)
        .assertHit()
        .assertBody("a");
    get(uri2)
        .assertHit()
        .assertBody("b");

    cache.clear();
    get(uri1).assertMiss();
    get(uri2).assertMiss();
  }

  @StoreParameterizedTest
  @StoreConfig
  void manuallyInvalidateEntryMatchingASpecificVariant(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Vary", "Accept-Encoding")
        .addHeader("Content-Encoding", "gzip")
        .setBody(new okio.Buffer().write(gzip("Mew"))));
    seedCache(GET(serverUri).header("Accept-Encoding", "gzip"));

    // Removal only succeeds for the request matching the correct response variant

    assertFalse(cache.remove(GET(serverUri).header("Accept-Encoding", "deflate")));
    get(GET(serverUri).header("Accept-Encoding", "gzip"))
        .assertCode(200)
        .assertHit()
        .assertBody("Mew");

    assertTrue(cache.remove(GET(serverUri).header("Accept-Encoding", "gzip")));
    assertNotCached(GET(serverUri).header("Accept-Encoding", "gzip"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"private", "public"})
  @StoreConfig(store = DISK, fileSystem = SYSTEM)
  void cacheControlPublicOrPrivateIsCacheableByDefault(String directive, Store store)
      throws Exception {
    setUpCache(store);
    // Last-Modified:      30 seconds from date
    // Heuristic lifetime: 3 seconds
    var lastModifiedInstant = clock.instant();
    clock.advanceSeconds(30);
    var dateInstant = clock.instant();
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", directive)
        .addHeader("Last-Modified", formatHttpDate(toUtcDateTime(lastModifiedInstant)))
        .addHeader("Date", formatHttpDate(toUtcDateTime(dateInstant)))
        .setBody("Mew"));
    seedCache(serverUri); // Put in cache
    server.takeRequest(); // Drop network request

    clock.advanceSeconds(2); // Retain freshness (lifetime = 1 seconds)

    get(serverUri)
        .assertHit()
        .assertBody("Mew");

    clock.advanceSeconds(2); // Make stale

    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    get(serverUri)
        .assertConditionalHit()
        .assertBody("Mew");

    var sentRequest = server.takeRequest();
    assertEquals(lastModifiedInstant, sentRequest.getHeaders().getInstant("If-Modified-Since"));
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

    setUpCache(storeContext.newStore()); // Create a new cache
    get(serverUri)
        .assertHit()
        .assertBody("Eevee")
        .assertCachedWithSSL();

    cache.close();
    clock.advanceSeconds(1); // Make response stale by 1 second between sessions

    setUpCache(storeContext.newStore()); // Create a new cache
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    get(serverUri)
        .assertConditionalHit()
        .assertBody("Eevee")
        .assertCachedWithSSL();
  }

  @StoreParameterizedTest
  @StoreConfig
  void cacheSize(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("bababooey"));
    seedCache(serverUri);

    assertEquals(cache.storeForTesting().size(), cache.size());
  }

  @StoreParameterizedTest
  @StoreConfig
  void networkFailureDuringTransmission(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("Jigglypuff get that stuff")
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
    assertThrows(IOException.class, () -> get(serverUri));

    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("Jigglypuff get that stuff"));
    get(serverUri)
        .assertMiss()
        .assertBody("Jigglypuff get that stuff");

    clock.advanceSeconds(2); // Make response stale by 1 second

    // Attempted revalidation throws & cache update is discarded
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("Jigglypuff get that stuff")
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
    assertThrows(IOException.class, () -> get(serverUri));

    // Stale cache response is still there
    var request = GET(serverUri).header("Cache-Control", "max-stale=1");
    get(request)
        .assertHit()
        .assertBody("Jigglypuff get that stuff")
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
  @StoreConfig
  void prematurelyCloseResponseBody(Store store) throws Exception {
    setUpCache(store);

    // Ensure we have a body that spans multiple onNext signals
    var body = "Picka Picka Pickachu\n".repeat(10 * 1024);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody(body));

    // Prematurely close the response body. This causes cache writing to continue in background.
    try (var reader = client.send(GET(serverUri), MoreBodyHandlers.ofReader()).body()) {
      var chars = new char["Picka Picka Pickachu".length()];
      int read = reader.read(chars);
      assertEquals(chars.length, read);
      assertEquals("Picka Picka Pickachu", new String(chars));
    }

    // Wait till opened editors are closed so all writing is completed
    editAwaiter.await();

    get(serverUri)
        .assertCode(200)
        .assertHit()
        .assertBody(body);
  }

  @StoreParameterizedTest
  @StoreConfig
  void errorsWhileWritingDiscardsCaching(Store store) throws Exception {
    var failingStore = new FailingStore(store);
    failingStore.allowReads = true;
    setUpCache(failingStore);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("Pickachu"));

    // Write failure is ignored & the response completes normally nevertheless.
    seedCache(serverUri).assertBody("Pickachu");
    get(GET(serverUri).header("Cache-Control", "only-if-cached"))
        .assertLocallyGenerated(); // No cached response

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
  @StoreConfig
  void errorsWhileReadingArePropagated(Store store) throws Exception {
    var failingStore = new FailingStore(store);
    failingStore.allowWrites = true;
    setUpCache(failingStore);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("Pickachu"));
    seedCache(serverUri);

    // Read failure is propagated
    assertThrows(TestException.class, () -> get(serverUri));
  }

  @StoreParameterizedTest
  @StoreConfig
  void iteratorUris(Store store) throws Exception {
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

    var uris = Stream.of("/a", "/b", "/c")
        .map(serverUri::resolve)
        .collect(Collectors.toSet());
    var iter = cache.uris();
    for (int i = 0; i < 3; i++) {
      assertTrue(iter.hasNext());

      var uri = iter.next();
      assertTrue(uris.remove(uri), uri::toString);
    }
    assertFalse(iter.hasNext());
    assertThrows(NoSuchElementException.class, iter::next);
  }

  @StoreParameterizedTest
  @StoreConfig
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
    assertThrows(IllegalStateException.class, iter::remove);
    while (iter.hasNext()) {
      assertThrows(IllegalStateException.class, iter::remove);

      var uri = iter.next();
      if (uri.equals(serverUri.resolve("/a")) || uri.equals(serverUri.resolve("/c"))) {
        iter.remove();
      }

      // Check hasNext prohibits removing the wrong entry as it causes the iterator to advance
      iter.hasNext();
      assertThrows(IllegalStateException.class, iter::remove);
    }

    get(GET(serverUri.resolve("/a")).header("Cache-Control", "only-if-cached"))
        .assertLocallyGenerated();
    get(GET(serverUri.resolve("/c")).header("Cache-Control", "only-if-cached"))
        .assertLocallyGenerated();

    get(serverUri.resolve("/b"))
        .assertHit()
        .assertBody("b");
  }

  @StoreParameterizedTest
  @StoreConfig
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
    get(hitUri).assertMiss();  // requestCount = 1, missCount = 1, networkUseCount = 1
    get(hitUri).assertHit();   // requestCount = 2, hitCount = 1
    get(missUri).assertMiss(); // requestCount = 3, missCount = 2, networkUseCount = 2
    for (int i = 0; i < 10; i++) {  // requestCount = 13, missCount = 12, networkUseCount = 12
      get(missUri).assertMiss();
    }

    assertTrue(cache.remove(hitUri));

    get(hitUri).assertMiss();  // requestCount = 14, missCount = 13, networkUseCount = 13
    for (int i = 0; i < 10; i++) {  // requestCount = 24, hitCount = 11
      get(hitUri).assertHit();
    }

    // requestCount = 25, missCount = 14 (no network)
    get(GET(missUri).header("Cache-Control", "only-if-cached"))
        .assertLocallyGenerated();

    var stats = cache.stats();
    assertEquals(25, stats.requestCount());
    assertEquals(11, stats.hitCount());
    assertEquals(14, stats.missCount());
    assertEquals(13, stats.networkUseCount());
    assertEquals(11 / 25.0, stats.hitRate());
    assertEquals(14 / 25.0, stats.missRate());
  }

  @StoreParameterizedTest
  @StoreConfig
  void perUriStats(Store store) throws Exception {
    setUpCache(store);
    var uri1 = serverUri.resolve("/a");
    var uri2 = serverUri.resolve("/b");
    server.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest recordedRequest) {
        return new MockResponse().addHeader("Cache-Control", "max-age=2");
      }
    });

    get(uri1).assertMiss(); // a.requestCount = 1, a.missCount = 1, a.networkUseCount = 1
    get(uri1).assertHit();  // a.requestCount = 2, a.hitCount = 1
    // a.requestCount = 3, a.missCount = 2, a.networkUseCount = 2
    get(GET(uri1).header("Cache-Control", "no-cache"))
        .assertConditionalMiss();
    get(uri1).assertHit(); // a.requestCount = 4, a.hitCount = 2
    assertTrue(cache.remove(uri1));
    // a.requestCount = 5, a.missCount = 3 (no network)
    get(GET(uri1).header("Cache-Control", "only-if-cached")).assertLocallyGenerated();

    get(uri2).assertMiss(); // b.requestCount = 1, b.missCount = 1, b.networkUseCount = 1
    for (int i = 0; i < 5; i++) { // b.requestCount = 6, b.missCount = 6, b.networkUseCount = 6
      get(GET(uri2).header("Cache-Control", "no-cache")).assertConditionalMiss();
    }
    get(uri2).assertHit(); // b.requestCount = 7, b.hitCount = 1

    var stats1 = cache.stats(uri1);
    assertEquals(5, stats1.requestCount());
    assertEquals(2, stats1.hitCount());
    assertEquals(3, stats1.missCount());
    assertEquals(2, stats1.networkUseCount());

    var stats2 = cache.stats(uri2);
    assertEquals(7, stats2.requestCount());
    assertEquals(1, stats2.hitCount());
    assertEquals(6, stats2.missCount());
    assertEquals(6, stats2.networkUseCount());

    var emptyStats = cache.stats(serverUri.resolve("/c"));
    assertEquals(0, emptyStats.requestCount());
    assertEquals(0, emptyStats.hitCount());
    assertEquals(0, emptyStats.missCount());
    assertEquals(0, emptyStats.networkUseCount());
  }

  @StoreParameterizedTest
  @StoreConfig
  void writeStats(Store store) throws Exception {
    var failingStore = new FailingStore(store);
    failingStore.allowReads = true;
    setUpCache(failingStore);
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest recordedRequest) {
        return new MockResponse()
            .addHeader("Cache-Control", "max-age=1")
            .setBody("Pickachu");
      }
    });

    failingStore.allowWrites = true;
    seedCache(serverUri.resolve("/a"));
    assertTrue(cache.remove(serverUri.resolve("/a"))); // Reinsert
    seedCache(serverUri.resolve("/a"));
    seedCache(serverUri.resolve("/b"));

    failingStore.allowWrites = false;
    assertTrue(cache.remove(serverUri.resolve("/b"))); // Reinsert
    seedCache(serverUri.resolve("/b"));
    seedCache(serverUri.resolve("/c"));

    assertEventuallyEquals(3, () -> cache.stats().writeSuccessCount());
    assertEventuallyEquals(2, () -> cache.stats().writeFailureCount());

    assertEventuallyEquals(2, () -> cache.stats(serverUri.resolve("/a")).writeSuccessCount());
    assertEventuallyEquals(0, () -> cache.stats(serverUri.resolve("/a")).writeFailureCount());

    assertEventuallyEquals(1, () ->  cache.stats(serverUri.resolve("/b")).writeSuccessCount());
    assertEventuallyEquals(1, () ->  cache.stats(serverUri.resolve("/b")).writeFailureCount());

    assertEventuallyEquals(0, () -> cache.stats(serverUri.resolve("/c")).writeSuccessCount());
    assertEventuallyEquals(1, () -> cache.stats(serverUri.resolve("/c")).writeFailureCount());
  }

  private ResponseVerification<String> get(URI uri) throws IOException, InterruptedException {
    return get(GET(uri));
  }

  private ResponseVerification<String> getUnchecked(HttpRequest request) {
    try {
      return get(request);
    } catch (IOException | InterruptedException e) {
      return fail(e);
    }
  }

  private ResponseVerification<String> get(HttpRequest request)
      throws IOException, InterruptedException {
    var response = client.send(request, BodyHandlers.ofString());
    editAwaiter.await();
    return verifying(response);
  }

  private ResponseVerification<String> getWithPushHandler(URI uri) {
    var response = client.sendAsync(
        GET(uri),
        BodyHandlers.ofString(),
        PushPromiseHandler.of(__ -> BodyHandlers.ofString(), new ConcurrentHashMap<>())).join();
    editAwaiter.await();
    return verifying(response);
  }

  private ResponseVerification<String> seedCache(URI uri)
      throws IOException, InterruptedException {
    return get(uri).assertMiss();
  }

  private ResponseVerification<String> seedCache(HttpRequest request)
      throws IOException, InterruptedException {
    return get(request).assertMiss();
  }

  /**
   * Ensures requests to serverUri result in a cache hit, retrying if necessary in case a stale
   * response is being updated in background.
   */
  private ResponseVerification<String> retryTillOfflineHit()
      throws IOException, InterruptedException {
    int tries = 0;
    ResponseVerification<String> response;
    do {
      response = get(GET(serverUri).header("Cache-Control", "max-stale=0, only-if-cached"));
    } while (response.getCacheAware().cacheStatus() != HIT && ++tries <= MAX_RETRY_COUNT);
    return response.assertCacheStatus(HIT);
  }

  private void assertNotCached(URI uri) throws Exception {
    assertNotCached(GET(uri));
  }

  private void assertNotCached(MutableRequest request) throws Exception {
    var cacheControl = CacheControl.newBuilder()
        .onlyIfCached()
        .anyMaxStale()
        .build();
    get(request.cacheControl(cacheControl))
        .assertLocallyGenerated();
  }

  private static LocalDateTime toUtcDateTime(Instant instant) {
    return LocalDateTime.ofInstant(instant, UTC);
  }

  private static void assertEventuallyEquals(long expected, LongSupplier actual) {
    var tolerance = Duration.ofSeconds(30);
    while (tolerance.toMillis() > 0) {
      try {
        assertEquals(expected, actual.getAsLong());
        return;
      } catch (AssertionError e) {
        try {
          long millisToSleep = Math.min(500, tolerance.toMillis());
          tolerance = tolerance.minusMillis(millisToSleep);

          //noinspection BusyWait
          Thread.sleep(millisToSleep);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(interrupted);
        }
      }
    }

    // Assert one last time to fail with the appropriate exception.
    // There's also a chance that this assert succeeds.
    assertEquals(expected, actual.getAsLong());
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
