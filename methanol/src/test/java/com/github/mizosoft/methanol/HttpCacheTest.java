package com.github.mizosoft.methanol;

import static com.github.mizosoft.methanol.MutableRequest.GET;
import static com.github.mizosoft.methanol.TestExecutorProvider.ExecutorType.FIXED_POOL;
import static com.github.mizosoft.methanol.internal.cache.DateUtils.formatHttpDate;
import static com.github.mizosoft.methanol.internal.extensions.CacheAwareResponse.CacheStatus.CONDITIONAL_HIT;
import static com.github.mizosoft.methanol.internal.extensions.CacheAwareResponse.CacheStatus.HIT;
import static com.github.mizosoft.methanol.internal.extensions.CacheAwareResponse.CacheStatus.LOCALLY_GENERATED;
import static com.github.mizosoft.methanol.internal.extensions.CacheAwareResponse.CacheStatus.MISS;
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
import static java.util.function.Predicate.not;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.mizosoft.methanol.Methanol.Interceptor;
import com.github.mizosoft.methanol.MockWebServerProvider.UseHttps;
import com.github.mizosoft.methanol.internal.extensions.CacheAwareResponse;
import com.github.mizosoft.methanol.internal.extensions.TrackedResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.security.cert.Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAmount;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import okhttp3.Headers;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@ExtendWith(MockWebServerProvider.class)
class HttpCacheTest {
  @RegisterExtension final TestExecutorProvider executorProvider = new TestExecutorProvider();

  private AdvancableClock clock;
  private HttpCache cache;
  private Methanol.Builder clientBuilder;
  private Methanol client;
  private MockWebServer server;

  @BeforeEach
  void setUp(Methanol.Builder builder, MockWebServer server) {
    clock = new AdvancableClock();
    cache = HttpCache.newBuilder()
        .cacheOnMemory(Long.MAX_VALUE)
        .executor(executorProvider.newExecutor(FIXED_POOL))
        .clockForTesting(clock)
        .build();
    this.clientBuilder = builder.cache(cache);
    this.client = clientBuilder.build();
    this.server = server;
  }

  @AfterEach
  void tearDown() throws IOException {
    if (cache != null) {
      cache.close(); // TODO destroy when implemented
    }
  }

  // TODO also test with DiskStore when implemented

  @Test
  void cacheGetWithMaxAge() throws Exception {
    assertGetIsCached(ofSeconds(1), "Cache-Control", "max-age=2");
  }

  @Test
  void cacheGetWithExpires() throws Exception {
    var now = toUtcDateTime(clock.instant());
    assertGetIsCached(
        ofHours(12),                      // Advance clock half a day
        "Expires",
        formatHttpDate(now.plusDays(1))); // Expire a day from "now"
  }

  @Test
  void cacheGetWithExpiresAndDate() throws Exception {
    var date = toUtcDateTime(clock.instant());
    assertGetIsCached(
        ofDays(1),                         // Advance clock a day (retain freshness)
        "Date",
        formatHttpDate(date),
        "Expires",
        formatHttpDate(date.plusDays(1))); // Expire a day from date
  }

  @Test
  @UseHttps
  void cacheSecureGetWithMaxAge() throws Exception {
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

  @Test
  void cacheGetWithExpiresConditionalHit() throws Exception {
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

  @Test
  void responseIsFreshenedOnConditionalHit() throws Exception {
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

  @Test
  void prohibitNetworkOnRequiredValidation() throws Exception {
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

  @ParameterizedTest
  @EnumSource
  void revalidationFromStale(ValidatorConfig config) throws Exception {
    var request = GET(uri(server));
    assertRevalidation(request, config, true);
  }

  @ParameterizedTest
  @EnumSource
  void failedRevalidationFromStale(ValidatorConfig config) throws Exception {
    var request = GET(uri(server));
    assertFailedRevalidation(request, config, true);
  }

  @ParameterizedTest
  @EnumSource
  void revalidationForcedByNoCache(ValidatorConfig config) throws Exception {
    var request = GET(uri(server)).header("Cache-Control", "no-cache");
    assertRevalidation(request, config, false);
  }

  @ParameterizedTest
  @EnumSource
  void failedRevalidationForcedByNoCache(ValidatorConfig config) throws Exception {
    var request = GET(uri(server)).header("Cache-Control", "no-cache");
    assertFailedRevalidation(request, config, false);
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
    assertMiss(getString(uri(server))); // Put response
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
    assertMiss(getString(uri(server))); // Put response
    server.takeRequest(); // Remove initial request

    clock.advanceSeconds(makeStale ? 3 : 1); // Make stale or retain freshness

    var response = assertConditionalMiss(getString(triggeringRequest));
    assertEquals("DOUBLE STONKS!", response.body()); // Body is updated
    assertEquals(Optional.of("v2"), response.headers().firstValue("X-Version"));
    validators2.toMultimap()
        .forEach((name, values) -> assertEquals(values, response.headers().allValues(name)));

    // This is the invalidated cache response
    var cacheResponse = response.cacheResponse().orElseThrow();
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

  @Test
  void lastModifiedDefaultsToDateWhenRevalidating() throws Exception {
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

  @Test
  void relaxMaxAgeWithRequest() throws Exception {
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

  @Test
  void constrainMaxAgeWithRequest() throws Exception {
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

  @Test
  void constrainFreshnessWithMinFresh() throws Exception {
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

  @Test
  void acceptingStalenessWithMaxStale() throws Exception {
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

  @Test
  void imposeRevalidationWhenStaleByMustRevalidate() throws Exception {
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

  @Test
  void cacheTwoPathsSameUri() throws Exception {
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

  @Test
  void preventCachingByNoStore() throws Exception {
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

  @Test
  void preventCachingByWildcardVary() throws Exception {
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

  @Test
  void varyingResponse() throws Exception {
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

  @Test
  void responsesVaryingOnImplicitHeadersAreNotStored() throws Exception {
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

  @Test
  void warningCodes1xxAreRemovedOnRevalidation() throws Exception {
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
  @ParameterizedTest(name = ParameterizedTest.ARGUMENTS_WITH_NAMES_PLACEHOLDER)
  @CsvFileSource(resources = "/default_cacheability.csv", numLinesToSkip = 1)
  void defaultCacheability(int code, boolean cacheableByDefault) throws Exception {
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

  @Test
  void heuristicExpiration() throws Exception {
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

  @Test
  void warningOnHeuristicFreshnessWithAgeGreaterThanOneDay() throws Exception {
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
    System.out.println(cacheResponse.headers());
    assertEquals(
        List.of("113 - \"Heuristic Expiration\""), cacheResponse.headers().allValues("Warning"));
    assertEquals(Optional.of("86401"), cacheResponse.headers().firstValue("Age"));
  }

  @Test
  void computingAge() throws Exception {
    // date_value = x
    clock.advanceSeconds(2);

    var clockInterceptor = new ClockAdvancingInterceptor(clock);
    clockInterceptor.advanceOnSend(ofSeconds(3));
    client = clientBuilder.postDecorationInterceptor(clockInterceptor).build();

    // request_time = x + 2
    // response_time = request_time + 3 = x + 5
    // apparent_age = response_time - date_value = 5
    // age_value = 10
    server.enqueue(new MockResponse()
        .addHeader("Cache-Control", "max-age=60")
        .addHeader("Age", "10"));
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
  void unsafeMethodsInvalidateCache(String method) throws Exception {
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
  void unsafeMethodsOnlyInvalidateCacheIfSuccessful(String method) throws Exception {
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
  void unsafeMethodsAreNotCached(String method) throws Exception {
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

  @Test
  void headOfCachedGet() throws Exception {
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

  @Test
  void varyWithAcceptEncoding() throws Exception {
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

  @Test
  void manuallyInvalidateEntries() throws Exception {
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
  void cacheControlPublicOrPrivateIsCacheableByDefault(String directive) throws Exception {
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

  @Test
  void recordStats() throws Exception {
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

  @Test
  void perUriStats() throws Exception {
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
      return cacheAware(client.send(withTimeout(request), BodyHandlers.ofString()));
    } catch (IOException | InterruptedException e) {
      return fail(e);
    }
  }

  private CacheAwareResponse<String> getString(HttpRequest request)
      throws IOException, InterruptedException {
    return cacheAware(client.send(withTimeout(request), BodyHandlers.ofString()));
  }

  // Set timeout to not block indefinitely when response is mistakenly not enqueued to MockWebServer
  private static MutableRequest withTimeout(HttpRequest request) {
    return MutableRequest.copyOf(request).timeout(Duration.ofSeconds(5));
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
                      .filter(not(value -> value.startsWith("1")))
                      .collect(Collectors.toUnmodifiableList());
            }
          }

          // Network values override cached
          assertEquals(networkValues != null ? networkValues : cacheValues, values);
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

  private static final class AdvancableClock extends Clock {
    private Instant instant;

    AdvancableClock() {
      this.instant = Instant.ofEpochMilli(0);
    }

    void advance(TemporalAmount amount) {
      instant = instant.plus(amount);
    }

    void advanceSeconds(int seconds) {
      instant = instant.plus(ofSeconds(seconds));
    }

    @Override
    public Instant instant() {
      return instant;
    }

    @Override
    public ZoneId getZone() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Clock withZone(ZoneId zone) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class ClockAdvancingInterceptor implements Interceptor {
    private final AdvancableClock clock;
    private Duration advanceOnSend;

    ClockAdvancingInterceptor(AdvancableClock clock) {
      this.clock = clock;
      this.advanceOnSend = Duration.ZERO;
    }

    void advanceOnSend(Duration d) {
      advanceOnSend = d;
    }

    @Override
    public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
        throws IOException, InterruptedException {
      clock.advance(advanceOnSend);
      return chain.forward(request);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
        HttpRequest request, Chain<T> chain) {
      clock.advance(advanceOnSend);
      return chain.forwardAsync(request);
    }
  }
}
