package com.github.mizosoft.methanol;

import static com.github.mizosoft.methanol.ExecutorProvider.ExecutorType.FIXED_POOL;
import static com.github.mizosoft.methanol.MutableRequest.GET;
import static com.github.mizosoft.methanol.internal.cache.DateUtils.formatHttpDate;
import static com.github.mizosoft.methanol.internal.extensions.CacheAwareResponse.CacheStatus.CONDITIONAL_HIT;
import static com.github.mizosoft.methanol.internal.extensions.CacheAwareResponse.CacheStatus.HIT;
import static com.github.mizosoft.methanol.internal.extensions.CacheAwareResponse.CacheStatus.LOCALLY_GENERATED;
import static com.github.mizosoft.methanol.internal.extensions.CacheAwareResponse.CacheStatus.MISS;
import static com.github.mizosoft.methanol.testing.extensions.StoreProvider.StoreConfig.FileSystemType.SYSTEM;
import static com.github.mizosoft.methanol.testing.extensions.StoreProvider.StoreConfig.StoreType.DISK;
import static com.github.mizosoft.methanol.testutils.TestUtils.deflate;
import static com.github.mizosoft.methanol.testutils.TestUtils.gzip;
import static com.github.mizosoft.methanol.testutils.TestUtils.headers;
import static java.net.HttpURLConnection.HTTP_GATEWAY_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.time.Duration.ofDays;
import static java.time.Duration.ofHours;
import static java.time.Duration.ofSeconds;
import static java.time.ZoneOffset.UTC;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.mizosoft.methanol.ExecutorProvider.ExecutorConfig;
import com.github.mizosoft.methanol.Methanol.Interceptor;
import com.github.mizosoft.methanol.MockWebServerProvider.UseHttps;
import com.github.mizosoft.methanol.internal.cache.DiskStore;
import com.github.mizosoft.methanol.internal.cache.MemoryStore;
import com.github.mizosoft.methanol.internal.cache.Store;
import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.extensions.CacheAwareResponse;
import com.github.mizosoft.methanol.internal.extensions.TrackedResponse;
import com.github.mizosoft.methanol.testing.extensions.StoreProvider;
import com.github.mizosoft.methanol.testing.extensions.StoreProvider.StoreConfig;
import com.github.mizosoft.methanol.testing.extensions.StoreProvider.StoreContext;
import com.github.mizosoft.methanol.testing.extensions.StoreProvider.StoreParameterizedTest;
import com.github.mizosoft.methanol.testutils.MockClock;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.Spliterators;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Phaser;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import okhttp3.Headers;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.ThrowingConsumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(value = 5, unit = TimeUnit.MINUTES)
@ExtendWith({MockWebServerProvider.class, StoreProvider.class, ExecutorProvider.class})
class HttpCacheTest {
  private Executor threadPool;
  private Methanol.Builder clientBuilder;
  private Methanol client;
  private MockWebServer server;
  private MockClock clock;
  private EditAwaiter editAwaiter;
  private HttpCache cache;

  @BeforeEach
  @ExecutorConfig(FIXED_POOL)
  void setUp(Executor threadPool, Methanol.Builder builder, MockWebServer server) {
    this.threadPool = threadPool;
    this.clientBuilder = builder.executor(threadPool);
    this.server = server;
  }

  private void setUpCache(StoreContext storeContext) throws IOException {
    clock = new MockClock();
    editAwaiter = new EditAwaiter();
    cache = HttpCache.newBuilder()
        .clockForTesting(clock)
        .storeForTesting(new EditAwaiterStore(storeContext.newStore(), editAwaiter))
        .executor(threadPool)
        .build();
    client = clientBuilder.cache(cache).build();
  }

  @AfterEach
  void tearDown() throws IOException {
    if (cache != null) {
      cache.dispose();
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
  }

  @StoreParameterizedTest
  @StoreConfig
  void cacheGetWithMaxAge(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    assertGetIsCached(ofSeconds(1), "Cache-Control", "max-age=2");
  }

  @StoreParameterizedTest
  @StoreConfig
  void cacheGetWithExpires(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    var now = toUtcDateTime(clock.instant());
    assertGetIsCached(
        ofHours(12),                      // Advance clock half a day
        "Expires",
        formatHttpDate(now.plusDays(1))); // Expire a day from "now"
  }

  @StoreParameterizedTest
  @StoreConfig
  void cacheGetWithExpiresAndDate(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
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
  void cacheSecureGetWithMaxAge(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    var cacheResponse = assertGetIsCached(ofSeconds(1), "Cache-Control", "max-age=2");
    assertCachedWithSSLSession(cacheResponse);
  }

  private CacheAwareResponse<String> assertGetIsCached(
      Duration clockAdvance, String... headers)
      throws Exception {
    server.enqueue(new MockResponse()
        .setHeaders(Headers.of(headers))
        .setBody("Is you is or is you ain't my baby?"));

    var response = assertMiss(getString(uri(server)));
    assertEquals("Is you is or is you ain't my baby?", response.body());

    clock.advance(clockAdvance);

    var cacheResponse = assertHit(getString(uri(server)));
    assertEquals("Is you is or is you ain't my baby?", cacheResponse.body());
    return cacheResponse;
  }

  @StoreParameterizedTest
  @StoreConfig
  void cacheGetWithExpiresConditionalHit(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    // Expire one day from "now"
    var oneDayFromNow = clock.instant().plus(ofDays(1));
    server.enqueue(new MockResponse()
        .addHeader("Expires", formatHttpDate(toUtcDateTime(oneDayFromNow)))
        .setBody("Is you is or is you ain't my baby?"));
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));

    var response = assertMiss(getString(uri(server)));
    assertEquals("Is you is or is you ain't my baby?", response.body());

    clock.advance(ofDays(2)); // Make response stale

    var cacheResponse = assertConditionalHit(getString(uri(server)));
    assertEquals("Is you is or is you ain't my baby?", cacheResponse.body());
  }

  @StoreParameterizedTest
  @StoreConfig
  void responseIsFreshenedOnConditionalHit(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
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
    assertMiss(getString(uri(server))); // Put response

    clock.advanceSeconds(2); // Make response stale

    var instantRevalidationReceived = clock.instant();
    var response = assertConditionalHit(getString(uri(server)));
    assertEquals("Youse is still my baby, baby", response.body());

    // Check that response metadata is updated, which is done in
    // background, so keep trying a number of times
    int maxTries = 10, tries = 0;
    CacheAwareResponse<String> cacheResponse;
    do {
      server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
      cacheResponse = getString(uri(server));
    } while (cacheResponse.cacheStatus() != HIT && tries++ < maxTries);

    assertEquals(
        HIT, cacheResponse.cacheStatus(),
        () -> "metadata not updated after " + maxTries + " tries");

    assertHit(cacheResponse);
    assertEquals("Youse is still my baby, baby", cacheResponse.body());
    assertEquals(Optional.of("v2"), cacheResponse.headers().firstValue("X-Version"));
    // Timestamps updated to that of the conditional GET
    assertEquals(instantRevalidationReceived, cacheResponse.timeRequestSent());
    assertEquals(instantRevalidationReceived, cacheResponse.timeResponseReceived());
  }

  @StoreParameterizedTest
  @StoreConfig
  void successfulRevalidationWithZeroContentLength(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    server.enqueue(new MockResponse()
        .addHeader("X-Version", "v1")
        .addHeader("Cache-Control", "max-age=1")
        .setBody("Pickachu"));
    server.enqueue(new MockResponse()
        .setResponseCode(HTTP_NOT_MODIFIED)
        .addHeader("X-Version", "v2")
        .addHeader("Content-Length", "0")); // This is wrong, but some servers do it

    assertMiss(getString(uri(server))); // Put response

    clock.advanceSeconds(2); // Make response stale

    var instantRevalidationReceived = clock.instant();
    var response = assertConditionalHit(getString(uri(server)));
    assertEquals("Pickachu", response.body());
    assertEquals(Optional.of("v2"), response.headers().firstValue("X-Version"));
    // The 304 response has 0 Content-Length, but that isn't use to replace the
    // stored response's Content-Length.
    assertEquals(
        OptionalLong.of(0L),
        response.networkResponse().orElseThrow().headers().firstValueAsLong("Content-Length"));
    assertEquals(
        OptionalLong.of("Pickachu".length()),
        response.headers().firstValueAsLong("Content-Length"));

    // Wait till response metadata is updated
    int maxTries = 10, tries = 0;
    CacheAwareResponse<String> cacheResponse;
    do {
      server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
      cacheResponse = getString(uri(server));
    } while (cacheResponse.cacheStatus() != HIT && tries++ < maxTries);

    assertEquals(
        HIT, cacheResponse.cacheStatus(),
        () -> "metadata not updated after " + maxTries + " tries");

    assertHit(cacheResponse);
    assertEquals("Pickachu", cacheResponse.body());
    assertEquals(Optional.of("v2"), cacheResponse.headers().firstValue("X-Version"));
    assertEquals(
        OptionalLong.of("Pickachu".length()),
        cacheResponse.headers().firstValueAsLong("Content-Length"));
    // Timestamps updated to that of the conditional GET
    assertEquals(instantRevalidationReceived, cacheResponse.timeRequestSent());
    assertEquals(instantRevalidationReceived, cacheResponse.timeResponseReceived());
  }

  @StoreParameterizedTest
  @StoreConfig
  void prohibitNetworkOnRequiredValidation(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("123"));
    assertMiss(getString(uri(server))); // Put response

    clock.advanceSeconds(2); // Make response stale

    var request = GET(uri(server)).header("Cache-Control", "only-if-cached");
    var response = assertLocallyGenerated(getString(request));
    assertEquals("", response.body()); // Doesn't have a body
  }

  /* As encouraged by rfc 7232 2.4, both validators are added in case an
     HTTP/1.0 recipient is present along the way that doesn't know about ETags. */

  @SuppressWarnings("unused") // EnumSource indirection
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
      var request = GET(uri(server));
      assertRevalidation(request, config, true);
    });
  }

  @StoreParameterizedTest
  @StoreConfig
  void failedRevalidationFromStale(StoreContext storeContext) throws Throwable {
    testForEachValidator(storeContext, config -> {
      var request = GET(uri(server));
      assertFailedRevalidation(request, config, true);
    });
  }

  @StoreParameterizedTest
  @StoreConfig
  void revalidationForcedByNoCache(StoreContext storeContext) throws Throwable {
    testForEachValidator(storeContext, config -> {
      var request = GET(uri(server)).header("Cache-Control", "no-cache");
      assertRevalidation(request, config, false);
    });
  }

  @StoreParameterizedTest
  @StoreConfig
  void failedRevalidationForcedByNoCache(StoreContext storeContext) throws Throwable {
    testForEachValidator(storeContext, config -> {
      var request = GET(uri(server)).header("Cache-Control", "no-cache");
      assertFailedRevalidation(request, config, false);
    });
  }

  private void testForEachValidator(
      StoreContext storeContext, ThrowingConsumer<ValidatorConfig> tester) throws Throwable {
    for (var config : ValidatorConfig.values()) {
      try {
        setUpCache(storeContext);
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
    var response = assertMiss(getString(uri(server))); // Put response
    assertEquals("STONKS!", response.body());
    assertEquals(Optional.of("v1"), response.headers().firstValue("X-Version"));
    server.takeRequest(); // Remove initial request

    clock.advanceSeconds(makeStale ? 3 : 1); // Make stale or retain freshness

    var cacheResponse = assertConditionalHit(getString(triggeringRequest));
    assertEquals("STONKS!", cacheResponse.body());
    assertEquals(Optional.of("v2"), cacheResponse.headers().firstValue("X-Version"));

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
        .setHeader("Cache-Control", "max-age=2")
        .addHeader("X-Version", "v2")
        .setBody("DOUBLE STONKS!"));

    var instantInitiallyReceived = clock.instant(); // First response is received at this tick
    var response = assertMiss(getString(uri(server))); // Put response
    assertEquals("STONKS!", response.body());
    assertEquals(Optional.of("v1"), response.headers().firstValue("X-Version"));
    server.takeRequest(); // Remove initial request

    clock.advanceSeconds(makeStale ? 3 : 1); // Make stale or retain freshness

    var networkResponse = assertConditionalMiss(getString(triggeringRequest));
    assertEquals("DOUBLE STONKS!", networkResponse.body()); // Body is updated
    assertEquals(Optional.of("v2"), networkResponse.headers().firstValue("X-Version"));
    validators2.toMultimap()
        .forEach((name, values) -> assertEquals(values, networkResponse.headers().allValues(name)));

    // This is the invalidated cache response
    var cacheResponse = networkResponse.cacheResponse().orElseThrow();
    assertEquals(Optional.of("v1"), cacheResponse.headers().firstValue("X-Version"));

    clock.advanceSeconds(1); // Updated response is still fresh

    var cacheResponse2 = assertHit(getString(uri(server)));
    assertEquals("DOUBLE STONKS!", cacheResponse2.body());
    assertEquals(Optional.of("v2"), cacheResponse2.headers().firstValue("X-Version"));
    validators2.toMultimap()
        .forEach((name, values) -> assertEquals(values, cacheResponse2.headers().allValues(name)));

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
  void lastModifiedDefaultsToDateWhenRevalidating(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    var dateInstant = clock.instant();
    clock.advanceSeconds(1);

    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Date", formatHttpDate(toUtcDateTime(dateInstant)))
        .setBody("FLUX"));
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    assertMiss(getString(uri(server))); // Put response
    server.takeRequest(); // Remove initial request

    clock.advanceSeconds(2); // Make stale

    var response = assertConditionalHit(getString(uri(server)));
    assertEquals("FLUX", response.body());

    var sentRequest = server.takeRequest();
    assertEquals(dateInstant, sentRequest.getHeaders().getInstant("If-Modified-Since"));
  }

  @StoreParameterizedTest
  @StoreConfig
  void relaxMaxAgeWithRequest(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("tesla"));
    assertMiss(getString(uri(server))); // Put response

    clock.advanceSeconds(2); // Make response stale

    // Relaxed max-age retains freshness
    var request = GET(uri(server)).header("Cache-Control", "max-age=2");
    var cacheResponse = assertHit(getString(request));
    assertEquals("tesla", cacheResponse.body());
  }

  @StoreParameterizedTest
  @StoreConfig
  void constrainMaxAgeWithRequest(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=2")
        .setBody("tesla"));
    assertMiss(getString(uri(server))); // Put response

    clock.advanceSeconds(2); // Retain freshness

    assertHit(getString(uri(server)));

    // Constrain max-age so that the response is stale
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    var request = GET(uri(server)).header("Cache-Control", "max-age=1");
    var cacheResponse = assertConditionalHit(getString(request));
    assertEquals("tesla", cacheResponse.body());
  }

  @StoreParameterizedTest
  @StoreConfig
  void constrainFreshnessWithMinFresh(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    // Last-Modified: 2 seconds from "now"
    var lastModifiedInstant = clock.instant();
    clock.advanceSeconds(2);

    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=3")
        .addHeader("Last-Modified", formatHttpDate(toUtcDateTime(lastModifiedInstant)))
        .setBody("spaceX"));
    assertMiss(getString(uri(server))); // Put response
    server.takeRequest(); // Drop request

    clock.advanceSeconds(1); // Retain freshness (lifetime = 2 secs)

    assertHit(getString(uri(server)));

    var request1 = GET(uri(server)).header("Cache-Control", "min-fresh=2");
    assertEquals("spaceX", assertHit(getString(request1)).body()); // min-fresh satisfied

    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=3")
        .addHeader("Last-Modified", formatHttpDate(toUtcDateTime(lastModifiedInstant)))
        .setBody("tesla"));
    var request2 = GET(uri(server)).header("Cache-Control", "min-fresh=3");
    assertEquals("tesla", assertConditionalMiss(getString(request2)).body());

    var sentRequest = server.takeRequest();
    assertEquals(lastModifiedInstant, sentRequest.getHeaders().getInstant("If-Modified-Since"));
  }

  @StoreParameterizedTest
  @StoreConfig
  void acceptingStalenessWithMaxStale(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("stale on a scale"));
    assertMiss(getString(uri(server))); // Put response

    clock.advanceSeconds(3); // Make stale by 2 seconds

    BiConsumer<CacheControl, UnaryOperator<CacheAwareResponse<String>>> assertStaleness =
        (cacheControl, cacheStatusAssert) -> {
          var request = GET(uri(server)).cacheControl(cacheControl);
          var response = cacheStatusAssert.apply(getStringUnchecked(request));
          assertEquals("stale on a scale", response.body());
          // Must put a warning only if not revalidated
          if (response.cacheStatus() == HIT) {
            assertEquals(
                Optional.of("110 - \"Response is Stale\""),
                response.headers().firstValue("Warning"));
          } else {
            assertEquals(Optional.empty(), response.headers().firstValue("Warning"));
          }
        };

    // Allow any staleness -> HIT
    assertStaleness.accept(CacheControl.parse("max-stale"), HttpCacheTest::assertHit);

    // Allow 3 seconds of staleness -> HIT
    assertStaleness.accept(CacheControl.parse("max-stale=3"), HttpCacheTest::assertHit);

    // Allow 2 seconds of staleness -> HIT
    assertStaleness.accept(CacheControl.parse("max-stale=2"), HttpCacheTest::assertHit);

    // Allow 1 second of staleness -> CONDITIONAL_HIT as staleness is 2
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    assertStaleness.accept(CacheControl.parse("max-stale=1"), HttpCacheTest::assertConditionalHit);
  }

  @StoreParameterizedTest
  @StoreConfig
  void imposeRevalidationWhenStaleByMustRevalidate(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1, must-revalidate")
        .setBody("popeye"));
    assertMiss(getString(uri(server))); // Put response

    clock.advanceSeconds(2); // Make stale by 1 secs

    // A revalidation is made despite max-stale=2
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    var requestMaxStale2Secs = GET(uri(server)).header("Cache-Control", "max-stale=2");
    assertConditionalHit(getString(requestMaxStale2Secs));
  }

  @StoreParameterizedTest
  @StoreConfig
  void cacheTwoPathsSameUri(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("alpha"));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("beta"));
    assertMiss(getString(uri((server), "/a")));
    assertMiss(getString(uri((server), "/b")));

    assertEquals("alpha", assertHit(getString(uri(server, "/a"))).body());
    assertEquals("beta", assertHit(getString(uri(server, "/b"))).body());
  }

  @StoreParameterizedTest
  @StoreConfig
  void preventCachingByNoStore(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "no-store")
        .setBody("alpha"));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "no-store")
        .setBody("alpha"));
    assertMiss(getString(uri((server), "/a"))); // Offer to cache
    assertEquals("alpha", assertMiss(getString(uri(server, "/a"))).body());

    // The request can also prevent caching even if the response is cacheable
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("beta"));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .setBody("beta"));
    var request = GET(uri(server, "/b")).header("Cache-Control", "no-store");
    assertMiss(getString(request)); // Offer to cache
    assertEquals("beta", assertMiss(getString(request)).body());
  }

  @StoreParameterizedTest
  @StoreConfig
  void preventCachingByWildcardVary(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Vary", "*")
        .setBody("Cache me if you can!"));
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Vary", "*")
        .setBody("Cache me if you can!"));
    assertMiss(getString(uri(server))); // Offer to cache
    assertEquals("Cache me if you can!", assertMiss(getString(uri(server))).body());
  }

  @StoreParameterizedTest
  @StoreConfig
  void varyingResponse(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Vary", "X-My-Header")
        .setBody("alpha"));
    var requestAlpha = GET(uri(server)).header("X-My-Header", "a");
    assertMiss(getString(requestAlpha)); // Put response
    var cacheResponseAlpha =  assertHit(getString(requestAlpha));
    assertEquals("alpha", cacheResponseAlpha.body());
    assertEquals(
        headers("X-My-Header", "a"),
        cacheResponseAlpha.cacheResponse().orElseThrow().request().headers());

    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Vary", "X-My-Header")
        .setBody("beta"));
    var requestBeta = GET(uri(server)).header("X-My-Header", "b");
    // TODO that'll need to change if we ever support storing multiple variants
    assertMiss(getString(requestBeta)); // Replace first variant
    var cacheResponseBeta = assertHit(getString(requestBeta));
    assertEquals("beta", cacheResponseBeta.body());
    assertEquals(
        headers("X-My-Header", "b"),
        cacheResponseBeta.cacheResponse().orElseThrow().request().headers());

    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Vary", "X-My-Header")
        .setBody("ϕ"));
    var requestPhi = GET(uri(server)); // Varying header is absent -> another variant!
    assertMiss(getString(requestPhi)); // Replace second variant
    var cacheResponsePhi = assertHit(getString(requestPhi));
    assertEquals("ϕ", cacheResponsePhi.body());
    assertEquals(
        headers(/* empty */),
        cacheResponsePhi.cacheResponse().orElseThrow().request().headers());
  }

  @StoreParameterizedTest
  @StoreConfig
  void responsesVaryingOnImplicitHeadersAreNotStored(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    for (var field : HttpCache.implicitlyAddedFieldsForTesting()) {
      server.enqueue(new MockResponse()
          .addHeader("Cache-Control", "max-age=1")
          .addHeader("Vary", "Accept-Encoding, " + field)
          .setBody("aaa"));
      assertMiss(getString(uri(server))); // Offer to cache

      var request = GET(uri(server)).header("Cache-Control", "only-if-cached");
      assertLocallyGenerated(getString(request)); // Not cached!
    }
  }

  @StoreParameterizedTest
  @StoreConfig
  void warningCodes1xxAreRemovedOnRevalidation(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=1")
        .addHeader("Warning", "199 - \"OMG IT'S HAPPENING\"")
        .addHeader("Warning", "299 - \"EVERY BODY STAY CALM\"")
        .setBody("Dwight the trickster"));
    var response = assertMiss(getString(uri(server))); // Put in cache
    assertEquals(
        Set.of("199 - \"OMG IT'S HAPPENING\"", "299 - \"EVERY BODY STAY CALM\""),
        Set.copyOf(response.headers().allValues("Warning")));

    clock.advanceSeconds(2); // Make stale

    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    var cacheResponse = assertConditionalHit(getString(uri(server)));
    assertEquals("Dwight the trickster", cacheResponse.body());
    assertEquals(
        Set.of("299 - \"EVERY BODY STAY CALM\""),
        Set.copyOf(cacheResponse.headers().allValues("Warning")));
  }

  /**
   * Tests that status codes in rfc7231 6.1 without Cache-Control or Expires are only cached if
   * defined as cacheable by default.
   */
  @ParameterizedTest
  @CsvFileSource(resources = "/default_cacheability.csv", numLinesToSkip = 1)
  @StoreConfig(store = DISK, fileSystem = SYSTEM)
  void defaultCacheability(int code, boolean cacheableByDefault, StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
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
    assertMiss(getString(uri(server))); // Offer to cache

    clock.advanceSeconds(1); // Heuristic freshness retained

    CacheAwareResponse<String> response;
    if (cacheableByDefault) {
      response = assertHit(getString(uri(server)));
    } else {
      server.enqueue(new MockResponse()
          .setResponseCode(code)
          .addHeader("Last-Modified", formatHttpDate(lastModified))
          .addHeader("Date", formatHttpDate(date))
          .setBody(body));
      response = assertMiss(getString(uri(server)));
    }
    assertEquals(code, response.statusCode());
    assertEquals(body, response.body());
  }

  @StoreParameterizedTest
  @StoreConfig
  void heuristicExpiration(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    // Last-Modified:      20 seconds from date
    // Heuristic lifetime: 2 seconds
    // Age:                1 second
    var lastModified = toUtcDateTime(clock.instant());
    clock.advanceSeconds(20);
    var date = toUtcDateTime(clock.instant());
    clock.advanceSeconds(1);

    server.enqueue(new MockResponse()
        .setHeader("Last-Modified", formatHttpDate(lastModified))
        .addHeader("Date", formatHttpDate(date))
        .setBody("Cache me pls!"));
    assertMiss(getString(uri(server))); // Put response

    clock.advanceSeconds(1); // Heuristic freshness retained (age = 2 secs, lifetime = 2 secs)

    var cacheResponse = assertHit(getString(uri(server)));
    assertEquals("Cache me pls!", cacheResponse.body());
    assertEquals(Optional.empty(), cacheResponse.headers().firstValue("Warning"));
    assertEquals(Optional.of("2"), cacheResponse.headers().firstValue("Age"));

    clock.advanceSeconds(1); // Make response stale (age = 3 secs, lifetime = 2 secs)

    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    var validationResponse = assertConditionalHit(getString(uri(server)));
    assertEquals("Cache me pls!", validationResponse.body());
    assertEquals(Optional.empty(), validationResponse.headers().firstValue("Warning"));
    assertEquals(Optional.empty(), validationResponse.headers().firstValue("Age"));
  }

  @StoreParameterizedTest
  @StoreConfig
  void warningOnHeuristicFreshnessWithAgeGreaterThanOneDay(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    // Last-Modified:      11 days from date
    // Heuristic lifetime: 1.1 days
    var lastModified = toUtcDateTime(clock.instant());
    clock.advance(ofDays(20));
    var date = toUtcDateTime(clock.instant());

    server.enqueue(new MockResponse()
        .setHeader("Last-Modified", formatHttpDate(lastModified))
        .addHeader("Date", formatHttpDate(date))
        .setBody("Cache me pls!"));
    assertMiss(getString(uri(server))); // Put response

    // Heuristic freshness retained (age = 1 day + 1 second, lifetime = 1.1 days)
    clock.advance(ofDays(1).plusSeconds(1));

    var cacheResponse = assertHit(getString(uri(server)));
    assertEquals("Cache me pls!", cacheResponse.body());
    assertEquals(
        List.of("113 - \"Heuristic Expiration\""), cacheResponse.headers().allValues("Warning"));
    assertEquals(Optional.of("86401"), cacheResponse.headers().firstValue("Age"));
  }

  @StoreParameterizedTest
  @StoreConfig
  void computingAge(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    client = clientBuilder.postDecorationInterceptor(new Interceptor() {
      @Override public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
          throws IOException, InterruptedException {
        clock.advanceSeconds(3);
        return chain.forward(request);
      }

      @Override public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
          HttpRequest request, Chain<T> chain) { throw new AssertionError(); }
    }).build();

    // date_value = x
    // now = x + 2
    // request_time = x + 2
    // response_time = request_time + 3 = x + 5
    // apparent_age = response_time - date_value = 5
    // age_value = 10
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=60")
        .addHeader("Age", "10"));
    clock.advanceSeconds(2);
    // now = x + 5
    var response = assertMiss(getString(uri(server))); // Put in cache & advance clock
    assertEquals(clock.instant().minusSeconds(3), response.timeRequestSent());
    assertEquals(clock.instant(), response.timeResponseReceived());

    // now = x + 10
    // resident_time = now - responseTime = 5
    clock.advanceSeconds(5);

    // response_delay = 3
    // corrected_age_value = age_value + response_delay = 13
    // corrected_initial_age = max(apparent_age, corrected_age_value) = 13
    // resident_time = now - response_time = 5
    // current_age = corrected_initial_age + resident_time = 18
    var cacheResponse = assertHit(getString(uri(server)));
    assertEquals(Optional.of("18"), cacheResponse.headers().firstValue("Age"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
  @StoreConfig(store = DISK, fileSystem = SYSTEM)
  void unsafeMethodsInvalidateCache(String method, StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "max-age=2")
        .setBody("Pikachu"));
    assertMiss(getString(uri(server))); // Put in cache

    assertEquals("Pikachu", assertHit(getString(uri(server))).body());

    server.enqueue(new MockResponse().setBody("Eevee"));
    var request = MutableRequest.create(uri(server)).method(method, BodyPublishers.noBody());
    assertEquals("Eevee", assertMiss(getString(request)).body()); // Invalidates what's cached

    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "max-age=2")
        .setBody("Charmander"));
    assertEquals("Charmander", assertMiss(getString(uri(server))).body());
  }

  @ParameterizedTest
  @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
  @StoreConfig(store = DISK, fileSystem = SYSTEM)
  void unsafeMethodsOnlyInvalidateCacheIfSuccessful(String method, StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "max-age=2")
        .setBody("Pikachu"));
    assertMiss(getString(uri(server))); // Put in cache

    clock.advanceSeconds(1);

    assertEquals("Pikachu", assertHit(getString(uri(server))).body());

    server.enqueue(new MockResponse().setResponseCode(HTTP_INTERNAL_ERROR));
    var unsafeRequest = MutableRequest.create(uri(server)).method(method, BodyPublishers.noBody());
    assertMiss(getString(unsafeRequest)); // Shouldn't invalidate what's cached

    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "max-age=2")
        .setBody("Charmander"));
    assertEquals("Pikachu", assertHit(getString(uri(server))).body()); // Hit!
  }

  @ParameterizedTest
  @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
  @StoreConfig(store = DISK, fileSystem = SYSTEM)
  void unsafeMethodsAreNotCached(String method, StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "max-age=2")
        .setBody("Ditto"));

    var unsafeRequest = MutableRequest.create(uri(server)).method(method, BodyPublishers.noBody());
    assertMiss(getString(unsafeRequest)); // Offer to cache

    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "max-age=2")
        .setBody("Ditto"));
    assertEquals("Ditto", assertMiss(getString(uri(server))).body()); // Not cached!
  }

  @StoreParameterizedTest
  @StoreConfig
  void headOfCachedGet(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "max-age=2")
        .setBody("Mewtwo"));
    assertMiss(getString(uri(server))); // Put in cache

    // TODO that'll fail if HEADS are handled
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=2"));
    var head = MutableRequest.create(uri(server)).method("HEAD", BodyPublishers.noBody());
    assertMiss(getString(head));

    assertEquals("Mewtwo", assertHit(getString(uri(server))).body());
  }

  @StoreParameterizedTest
  @StoreConfig
  void varyWithAcceptEncoding(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "max-age=1")
        .setHeader("Vary", "Accept-Encoding")
        .setHeader("Content-Encoding", "gzip")
        .setBody(new okio.Buffer().write(gzip("Jigglypuff"))));
    server.enqueue(new MockResponse()
        .setHeader("Cache-Control", "max-age=1")
        .setHeader("Vary", "Accept-Encoding")
        .setHeader("Content-Encoding", "deflate")
        .setBody(new okio.Buffer().write(deflate("Jigglypuff"))));

    var gzipRequest = GET(uri(server)).header("Accept-Encoding", "gzip");
    assertEquals("Jigglypuff", assertMiss(getString(gzipRequest)).body()); // Put in cache
    assertEquals("Jigglypuff", assertHit(getString(gzipRequest)).body());

    var deflateRequest = GET(uri(server)).header("Accept-Encoding", "deflate");
    assertEquals("Jigglypuff", assertMiss(getString(deflateRequest)).body()); // Replace gzip variant
    assertEquals("Jigglypuff", assertHit(getString(deflateRequest)).body());
  }

  @StoreParameterizedTest
  @StoreConfig
  void manuallyInvalidateEntries(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    var uri1 = uri(server, "/a");
    var uri2 = uri(server, "/b");
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(@NotNull RecordedRequest recordedRequest) {
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

    assertMiss(getString(uri1)); // Put in cache
    assertEquals("a", assertHit(getString(uri1)).body());

    assertMiss(getString(uri2)); // Put in cache
    assertEquals("b", assertHit(getString(uri2)).body());

    cache.remove(uri1);
    assertMiss(getString(uri1)); // Re-insert

    cache.remove(MutableRequest.GET(uri2));
    assertMiss(getString(uri2)); // Re-insert

    assertEquals("a", assertHit(getString(uri1)).body());
    assertEquals("b", assertHit(getString(uri2)).body());

    cache.clear();
    assertEquals("a", assertMiss(getString(uri1)).body());
    assertEquals("b", assertMiss(getString(uri2)).body());
  }

  @ParameterizedTest
  @ValueSource(strings = {"private", "public"})
  @StoreConfig(store = DISK, fileSystem = SYSTEM)
  void cacheControlPublicOrPrivateIsCacheableByDefault(String directive, StoreContext storeContext)
      throws Exception {
    setUpCache(storeContext);
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
    getString(uri(server)); // Put in cache
    server.takeRequest(); // Drop network request

    clock.advanceSeconds(2); // Retain freshness (lifetime = 1 seconds)

    assertEquals("Mew", assertHit(getString(uri(server))).body());

    clock.advanceSeconds(2); // Make stale

    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    assertEquals("Mew", assertConditionalHit(getString(uri(server))).body());

    var sentRequest = server.takeRequest();
    assertEquals(lastModifiedInstant, sentRequest.getHeaders().getInstant("If-Modified-Since"));
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void cachePersistence(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
  }

  @StoreParameterizedTest
  @StoreConfig
  void recordStats(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    server.setDispatcher(new Dispatcher() {
      @NotNull @Override public MockResponse dispatch(@NotNull RecordedRequest recordedRequest) {
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

    var hitUri = uri(server, "/hit");
    var missUri = uri(server, "/miss");
    assertMiss(getString(hitUri));  // requestCount = 1, missCount = 1, networkUseCount = 1
    assertHit(getString(hitUri));   // requestCount = 2, hitCount = 1
    assertMiss(getString(missUri)); // requestCount = 3, missCount = 2, networkUseCount = 2
    for (int i = 0; i < 10; i++) {  // requestCount = 13, missCount = 12, networkUseCount = 12
      assertMiss(getString(missUri));
    }

    cache.remove(hitUri);

    assertMiss(getString(hitUri));  // requestCount = 14, missCount = 13, networkUseCount = 13
    for (int i = 0; i < 10; i++) {  // requestCount = 24, hitCount = 11
      assertHit(getString(hitUri));
    }

    // requestCount = 25, missCount = 14 (no network)
    assertLocallyGenerated(getString(GET(missUri).header("Cache-Control", "only-if-cached")));

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
  void perUriStats(StoreContext storeContext) throws Exception {
    setUpCache(storeContext);
    var uri1 = uri(server, "/a");
    var uri2 = uri(server, "/b");
    server.setDispatcher(new Dispatcher() {
      @NotNull @Override public MockResponse dispatch(@NotNull RecordedRequest recordedRequest) {
        return new MockResponse().addHeader("Cache-Control", "max-age=2");
      }
    });

    assertMiss(getString(uri1)); // a.requestCount = 1, a.missCount = 1, a.networkUseCount = 1
    assertHit(getString(uri1));  // a.requestCount = 2, a.hitCount = 1
    // a.requestCount = 3, a.missCount = 2, a.networkUseCount = 2
    assertConditionalMiss(getString(GET(uri1).header("Cache-Control", "no-cache")));
    assertHit(getString(uri1)); // a.requestCount = 4, a.hitCount = 2
    cache.remove(uri1);
    // a.requestCount = 5, a.missCount = 3 (no network)
    assertLocallyGenerated(getString(GET(uri1).header("Cache-Control", "only-if-cached")));

    assertMiss(getString(uri2)); // b.requestCount = 1, b.missCount = 1, b.networkUseCount = 1
    for (int i = 0; i < 5; i++) { // b.requestCount = 6, b.missCount = 6, b.networkUseCount = 6
      assertConditionalMiss(getString(GET(uri2).header("Cache-Control", "no-cache")));
    }
    assertHit(getString(uri2)); // b.requestCount = 7, b.hitCount = 1

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

    var emptyStats = cache.stats(uri(server, "/c"));
    assertEquals(0, emptyStats.requestCount());
    assertEquals(0, emptyStats.hitCount());
    assertEquals(0, emptyStats.missCount());
    assertEquals(0, emptyStats.networkUseCount());
  }

  private CacheAwareResponse<String> getString(URI uri) throws IOException, InterruptedException {
    return getString(GET(uri));
  }

  private CacheAwareResponse<String> getStringUnchecked(HttpRequest request) {
    try {
      return getString(request);
    } catch (IOException | InterruptedException e) {
      return fail(e);
    }
  }

  private CacheAwareResponse<String> getString(HttpRequest request)
      throws IOException, InterruptedException {
    var response = cacheAware(client.send(withTimeout(request), BodyHandlers.ofString()));
    editAwaiter.await();
    return response;
  }

  // Set timeout to not block indefinitely when response is mistakenly not enqueued to MockWebServer
  private static MutableRequest withTimeout(HttpRequest request) {
    return MutableRequest.copyOf(request).timeout(Duration.ofSeconds(20));
  }

  private static LocalDateTime toUtcDateTime(Instant instant) {
    return LocalDateTime.ofInstant(instant, UTC);
  }

  private static <T> CacheAwareResponse<T> assertHit(CacheAwareResponse<T> response) {
    assertEquals(HIT, response.cacheStatus());
    assertFalse(response.networkResponse().isPresent(), response.networkResponse().toString());
    assertTrue(response.cacheResponse().isPresent(), response.cacheResponse().toString());
    assertSimilarResponses(response.cacheResponse().get(), response);
    return response;
  }

  private static <T> CacheAwareResponse<T> assertConditionalHit(CacheAwareResponse<T> response) {
    assertEquals(CONDITIONAL_HIT, response.cacheStatus());
    assertTrue(response.cacheResponse().isPresent(), response.cacheResponse().toString());
    assertTrue(response.networkResponse().isPresent(), response.networkResponse().toString());

    var cacheResponse = response.cacheResponse().get();
    assertEquals(cacheResponse.uri(), response.uri());
    assertEquals(cacheResponse.statusCode(), response.statusCode());

    var networkResponse = response.networkResponse().get();
    assertEquals(cacheResponse.uri(), response.uri());
    assertEquals(HTTP_NOT_MODIFIED, networkResponse.statusCode());

    // Make sure headers are merged correctly as specified
    // in https://httpwg.org/specs/rfc7234.html#freshening.responses
    var cacheHeaders = new HashMap<>(cacheResponse.headers().map());
    var networkHeaders = new HashMap<>(networkResponse.headers().map());
    response.headers().map().forEach(
        (name, values) -> {
          var cacheValues = cacheHeaders.get(name);
          var networkValues = networkHeaders.get(name);
          // Assert Warning headers with 1xx code are removed
          if ("Warning".equalsIgnoreCase(name)) {
            values.forEach(value -> assertFalse(value.startsWith("1")));
            if (cacheValues != null) {
              cacheValues =
                  cacheValues.stream()
                      .filter(value -> !value.startsWith("1"))
                      .collect(Collectors.toUnmodifiableList());
            }
          }

          // Network values override cached unless it's Content-Length
          if (!"Content-Length".equalsIgnoreCase(name)) {
            assertEquals(networkValues != null ? networkValues : cacheValues, values, name);
          }
        });

    return response;
  }

  private static <T> CacheAwareResponse<T> assertMiss(CacheAwareResponse<T> response) {
    assertEquals(MISS, response.cacheStatus());
    assertFalse(response.cacheResponse().isPresent(), response.cacheResponse().toString());
    assertTrue(response.networkResponse().isPresent(), response.networkResponse().toString());
    assertEqualResponses(response.networkResponse().get(), response);
    return response;
  }

  private static <T> CacheAwareResponse<T> assertConditionalMiss(CacheAwareResponse<T> response) {
    assertEquals(MISS, response.cacheStatus());
    assertTrue(response.cacheResponse().isPresent(), response.cacheResponse().toString());
    assertTrue(response.networkResponse().isPresent(), response.networkResponse().toString());
    assertEqualResponses(response.networkResponse().get(), response);
    return response;
  }

  private static <T> CacheAwareResponse<T> assertLocallyGenerated(
      CacheAwareResponse<T> response) {
    assertEquals(LOCALLY_GENERATED, response.cacheStatus());
    assertEquals(HTTP_GATEWAY_TIMEOUT, response.statusCode());
    assertFalse(response.cacheResponse().isPresent(), response.cacheResponse().toString());
    assertFalse(response.networkResponse().isPresent(), response.networkResponse().toString());
    return response;
  }

  private static void assertCachedWithSSLSession(CacheAwareResponse<?> response) {
    var cacheResponse = response.cacheResponse().orElseThrow();
    assertTrue(cacheResponse.sslSession().isPresent());
    assertTrue(response.sslSession().isPresent());

    var session = response.sslSession().get();
    var cachedSession = cacheResponse.sslSession().get();
    assertEquals(cachedSession.getCipherSuite(), session.getCipherSuite());
    assertEquals(cachedSession.getProtocol(), session.getProtocol());
    assertArrayEquals(getPeerCerts(cachedSession), getPeerCerts(session));
    assertArrayEquals(cachedSession.getLocalCertificates(), session.getLocalCertificates());
  }

  private static Certificate[] getPeerCerts(SSLSession session) {
    try {
      return session.getPeerCertificates();
    } catch (SSLPeerUnverifiedException e) {
      return null;
    }
  }

  private static void assertSimilarResponses(HttpResponse<?> expected, HttpResponse<?> actual) {
    assertEquals(expected.uri(), actual.uri());
    assertEquals(expected.statusCode(), actual.statusCode());
    assertEquals(expected.headers(), actual.headers());
    assertEquals(expected.sslSession().isPresent(), actual.sslSession().isPresent());
  }

  private static void assertEqualResponses(TrackedResponse<?> expected, TrackedResponse<?> actual) {
    assertEquals(expected.uri(), actual.uri());
    assertEquals(expected.statusCode(), actual.statusCode());
    assertEquals(expected.headers(), actual.headers());
    assertEquals(expected.version(), actual.version());
    assertEquals(expected.timeRequestSent(), actual.timeRequestSent());
    assertEquals(expected.timeResponseReceived(), actual.timeResponseReceived());
    assertEquals(expected.sslSession().isPresent(), actual.sslSession().isPresent());
  }

  private static URI uri(MockWebServer server) {
    return uri(server, "/");
  }

  private static URI uri(MockWebServer server, String path) {
    return server.url(path).uri();
  }

  private static <T> CacheAwareResponse<T> cacheAware(HttpResponse<T> response) {
    return (CacheAwareResponse<T>) response;
  }

  /**
   * Awaits ongoing edits to be completed. By design, {@link
   * com.github.mizosoft.methanol.internal.cache.CacheWritingBodySubscriber} doesn't make downstream
   * completion wait for the whole body to be written. So if writes take time (happens with
   * DiskStore) the response entry is committed a while after the response is completed. This
   * however agitates tests as they expect things to happen sequentially. This is solved by waiting
   * for all open editors to close after a client.send(...) is issued.
   */
  private static final class EditAwaiter {
    private final Phaser phaser;

    EditAwaiter() {
      this.phaser = new Phaser(1); // Register self
    }

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
     * An Editor that notifies (arrives at) a Phaser when closed, allowing to await it's closure
     * among others'.
     */
    private static final class NotifyingEditor implements Editor {
      private final Editor delegate;
      private final Phaser phaser;

      NotifyingEditor(Editor delegate, Phaser phaser) {
        this.delegate = delegate;
        this.phaser = phaser;
        phaser.register();
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
        try {
          delegate.close();
        } finally {
          phaser.arriveAndDeregister();
        }
      }
    }
  }

  private static final class EditAwaiterStore implements Store {
    private final EditAwaiter editAwaiter;
    private final Store delegate;

    EditAwaiterStore(Store delegate, EditAwaiter editAwaiter) {
      this.editAwaiter = editAwaiter;
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
    public @Nullable Viewer view(String key) throws IOException {
      var v = delegate.view(key);
      return v != null ? new EditAwaiterViewer(v) : null;
    }

    @Override
    public CompletableFuture<@Nullable Viewer> viewAsync(String key) {
      return delegate.viewAsync(key);
    }

    @Override
    public @Nullable Editor edit(String key) throws IOException {
      var e = delegate.edit(key);
      return e != null ? editAwaiter.register(e) : null;
    }

    @Override
    public CompletableFuture<@Nullable Editor> editAsync(String key) {
      return delegate.editAsync(key);
    }

    @Override
    public Iterator<Viewer> viewAll() throws IOException {
      return StreamSupport.stream(Spliterators.spliteratorUnknownSize(delegate.viewAll(), 0), false)
          .<Viewer>map(EditAwaiterViewer::new)
          .iterator();
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
    public long size() {
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

    private final class EditAwaiterViewer implements Viewer {
      private final Viewer delegate;

      EditAwaiterViewer(Viewer delegate) {
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
      public @Nullable Editor edit() throws IOException {
        var e = delegate.edit();
        return e != null ? editAwaiter.register(e) : null;
      }

      @Override
      public void close() {
        delegate.close();
      }
    }
  }
}
