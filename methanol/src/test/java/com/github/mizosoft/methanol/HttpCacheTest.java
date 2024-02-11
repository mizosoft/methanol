/*
 * Copyright (c) 2023 Moataz Abdelnasser
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
import static com.github.mizosoft.methanol.internal.cache.HttpDates.formatHttpDate;
import static com.github.mizosoft.methanol.testing.TestUtils.deflate;
import static com.github.mizosoft.methanol.testing.TestUtils.gzip;
import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.function.Predicate.isEqual;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.mizosoft.methanol.CacheAwareResponse.CacheStatus;
import com.github.mizosoft.methanol.HttpCache.Listener;
import com.github.mizosoft.methanol.HttpCache.Stats;
import com.github.mizosoft.methanol.HttpCache.StatsRecorder;
import com.github.mizosoft.methanol.HttpCacheTest.RecordingListener.EventCategory;
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
import com.github.mizosoft.methanol.internal.cache.InternalStorageExtension;
import com.github.mizosoft.methanol.internal.cache.MemoryStore;
import com.github.mizosoft.methanol.internal.cache.Store;
import com.github.mizosoft.methanol.testing.Logging;
import com.github.mizosoft.methanol.testing.MockWebServerExtension.UseHttps;
import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.store.StoreConfig.FileSystemType;
import com.github.mizosoft.methanol.testing.store.StoreConfig.StoreType;
import com.github.mizosoft.methanol.testing.store.StoreContext;
import com.github.mizosoft.methanol.testing.store.StoreExtension;
import com.github.mizosoft.methanol.testing.store.StoreExtension.StoreParameterizedTest;
import com.github.mizosoft.methanol.testing.store.StoreSpec;
import com.github.mizosoft.methanol.testing.verifiers.ResponseVerifier;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
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
import java.util.Collections;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.QueueDispatcher;
import mockwebserver3.RecordedRequest;
import mockwebserver3.SocketPolicy;
import okhttp3.Headers;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.api.ObjectAssert;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.ThrowingConsumer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(10)
@ExtendWith(StoreExtension.class)
class HttpCacheTest extends AbstractHttpCacheTest {
  /**
   * Status codes that are cacheable by default (i.e. heuristically cacheable). From rfc7231 section
   * 6.1:
   *
   * <pre>{@code
   * Responses with status codes that are defined as cacheable by default (e.g., 200, 203, 204, 206,
   *   300, 301, 404, 405, 410, 414, and 501 in this specification) can be reused by a cache with
   *   heuristic expiration unless otherwise indicated by the method definition or explicit cache
   *   controls [RFC7234]; all other status codes are not cacheable by default.
   * }</pre>
   */
  private static final Set<Integer> CACHEABLE_BY_DEFAULT_CODES =
      Set.of(200, 203, 204, 206, 300, 301, 404, 405, 410, 414, 501);

  static {
    Logging.disable(HttpCache.class, DiskStore.class, CacheWritingPublisher.class);
  }

  private HttpCache cache;

  private void setUpCache(Store store) {
    setUpCache(store, null, null);
  }

  private void setUpCache(Store store, @Nullable StatsRecorder statsRecorder) {
    setUpCache(store, statsRecorder, null);
  }

  private void setUpCache(
      Store store, @Nullable StatsRecorder statsRecorder, @Nullable Listener listener) {
    var cacheBuilder =
        HttpCache.newBuilder()
            .clock(clock)
            .cacheOn(InternalStorageExtension.singleton(new EditAwaitableStore(store, editAwaiter)))
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
    try (var cache = HttpCache.newBuilder().cacheOnMemory(12).build()) {
      var store = cache.store();
      assertThat(store).isInstanceOf(MemoryStore.class);
      assertThat(store.maxSize()).isEqualTo(12);
      assertThat(store.executor()).isEmpty();
      assertThat(cache.directory()).isEmpty();
      assertThat(cache.size()).isZero();
    }
  }

  @Test
  void buildWithDiskStore(@TempDir Path dir) throws IOException {
    try (var cache = HttpCache.newBuilder().cacheOnDisk(dir, 12).executor(Runnable::run).build()) {
      var store = cache.store();
      assertThat(store).isInstanceOf(DiskStore.class);
      assertThat(store.maxSize()).isEqualTo(12);
      assertThat(cache.directory()).hasValue(dir);
      assertThat(cache.executor()).isEqualTo(store.executor());
    }
  }

  @StoreParameterizedTest
  void cacheGetWithMaxAge(Store store) throws Exception {
    setUpCache(store);

    server.enqueue(new MockResponse().setBody("Pikachu").setHeader("Cache-Control", "max-age=2"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Pikachu");

    clock.advance(Duration.ofSeconds(1));
    verifyThat(send(serverUri)).isCacheHit().hasBody("Pikachu");

    clock.advance(Duration.ofMillis(999));
    verifyThat(send(serverUri)).isCacheHit().hasBody("Pikachu");

    clock.advance(Duration.ofMillis(1));
    server.enqueue(new MockResponse().setBody("Eevee").setHeader("Cache-Control", "max-age=2"));
    verifyThat(send(serverUri)).isConditionalMiss().hasBody("Eevee");

    clock.advance(Duration.ofSeconds(3));
    server.enqueue(new MockResponse().setBody("Eevee").setHeader("Cache-Control", "max-age=2"));
    verifyThat(send(serverUri)).isConditionalMiss().hasBody("Eevee");
  }

  @StoreParameterizedTest
  void cacheGetWithExpires(Store store) throws Exception {
    setUpCache(store);

    var timeResponseReceived = toUtcDateTime(clock.instant());
    server.enqueue(
        new MockResponse()
            .setHeader("Expires", formatHttpDate(timeResponseReceived.plusDays(1)))
            .setBody("Pikachu"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Pikachu");

    clock.advance(Duration.ofHours(12));
    verifyThat(send(serverUri)).isCacheHit().hasBody("Pikachu");

    clock.advance(Duration.ofHours(12).minusMillis(1));
    verifyThat(send(serverUri)).isCacheHit().hasBody("Pikachu");

    clock.advance(Duration.ofMillis(1));
    server.enqueue(
        new MockResponse()
            .setHeader("Expires", formatHttpDate(timeResponseReceived.plusDays(1)))
            .setBody("Eevee"));
    verifyThat(send(serverUri)).isConditionalMiss().hasBody("Eevee");

    clock.advance(Duration.ofDays(1).plusMillis(1));
    server.enqueue(
        new MockResponse()
            .setHeader("Expires", formatHttpDate(timeResponseReceived.plusDays(1)))
            .setBody("Pikachu"));
    verifyThat(send(serverUri)).isConditionalMiss().hasBody("Pikachu");
  }

  @StoreParameterizedTest
  void cacheGetWithExpiresAndDate(Store store) throws Exception {
    setUpCache(store);

    var timeResponseGenerated = toUtcDateTime(clock.instant());
    clock.advance(Duration.ofHours(12));
    server.enqueue(
        new MockResponse()
            .setHeader("Date", formatHttpDate(timeResponseGenerated))
            .setHeader("Expires", formatHttpDate(timeResponseGenerated.plusDays(1)))
            .setBody("Pikachu"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Pikachu");

    clock.advance(Duration.ofHours(10));
    verifyThat(send(serverUri)).isCacheHit().hasBody("Pikachu");

    clock.advance(Duration.ofHours(2).minusMillis(1));
    verifyThat(send(serverUri)).isCacheHit().hasBody("Pikachu");

    clock.advance(Duration.ofMillis(1));
    server.enqueue(
        new MockResponse()
            .setHeader("Date", formatHttpDate(timeResponseGenerated))
            .setHeader("Expires", formatHttpDate(timeResponseGenerated.plusDays(1)))
            .setBody("Eevee"));
    verifyThat(send(serverUri)).isConditionalMiss().hasBody("Eevee");

    clock.advance(Duration.ofDays(1).plusMillis(1));
    server.enqueue(
        new MockResponse()
            .setHeader("Date", formatHttpDate(timeResponseGenerated))
            .setHeader("Expires", formatHttpDate(timeResponseGenerated.plusDays(1)))
            .setBody("Pikachu"));
    verifyThat(send(serverUri)).isConditionalMiss().hasBody("Pikachu");
  }

  @StoreParameterizedTest
  @UseHttps
  void cacheSecureGetWithMaxAge(Store store) throws Exception {
    setUpCache(store);

    server.enqueue(new MockResponse().setBody("Pikachu").setHeader("Cache-Control", "max-age=2"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Pikachu");

    clock.advance(Duration.ofSeconds(1));
    verifyThat(send(serverUri)).isCacheHit().hasBody("Pikachu");

    clock.advance(Duration.ofMillis(999));
    verifyThat(send(serverUri)).isCacheHit().hasBody("Pikachu");

    clock.advance(Duration.ofMillis(1));
    server.enqueue(new MockResponse().setBody("Eevee").setHeader("Cache-Control", "max-age=2"));
    verifyThat(send(serverUri)).isConditionalMiss().hasBody("Eevee");

    clock.advance(Duration.ofSeconds(3));
    server.enqueue(new MockResponse().setBody("Eevee").setHeader("Cache-Control", "max-age=2"));
    verifyThat(send(serverUri)).isConditionalMiss().hasBody("Eevee");
  }

  @StoreParameterizedTest
  void conditionalHitForGetWithExpires(Store store) throws Exception {
    setUpCache(store);

    // Expire one day from "now"
    var oneDayFromNow = clock.instant().plus(Duration.ofDays(1));
    server.enqueue(
        new MockResponse()
            .setHeader("Expires", instantToHttpDateString(oneDayFromNow))
            .setBody("Pikachu"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Pikachu");

    // Make response stale
    clock.advance(Duration.ofDays(2));
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(send(serverUri)).isConditionalHit().hasBody("Pikachu");
  }

  @StoreParameterizedTest
  @UseHttps
  void conditionalHitForSecureGetWithExpires(Store store) throws Exception {
    setUpCache(store);

    // Expire one day from "now"
    var oneDayFromNow = clock.instant().plus(Duration.ofDays(1));
    server.enqueue(
        new MockResponse()
            .setHeader("Expires", instantToHttpDateString(oneDayFromNow))
            .setBody("Pikachu"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Pikachu");

    // Make response stale
    clock.advance(Duration.ofDays(2));
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(send(serverUri)).isConditionalHit().hasBody("Pikachu").isCachedWithSsl();
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
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Jigglypuff");

    // Make response stale
    clock.advanceSeconds(2);
    server.enqueue(
        new MockResponse().setResponseCode(HTTP_NOT_MODIFIED).setHeader("X-Version", "v2"));
    verifyThat(send(serverUri)).isConditionalHit().hasBody("Jigglypuff");

    var instantRevalidationSentAndReceived = clock.instant();
    verifyThat(awaitCacheHit())
        .isCacheHit()
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
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Pikachu");

    // Make response stale
    clock.advanceSeconds(2);
    server.enqueue(
        new MockResponse()
            .setResponseCode(HTTP_NOT_MODIFIED)
            .setHeader("X-Version", "v2")
            .setHeader("Content-Length", "0")); // This is wrong, but some servers do it
    verifyThat(send(serverUri))
        .isConditionalHit()
        .hasBody("Pikachu")
        .containsHeader("X-Version", "v2")
        .containsHeader(
            "Content-Length", "Pikachu".length()) // Correct Content-Length isn't replaced
        .networkResponse()
        .containsHeader("Content-Length", "0");

    verifyThat(awaitCacheHit())
        .isCacheHit()
        .hasBody("Pikachu")
        .containsHeader("X-Version", "v2")
        .containsHeader("Content-Length", "Pikachu".length());
  }

  @StoreParameterizedTest
  void prohibitNetworkOnRequiredValidation(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("123"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("123");

    // Make response stale
    clock.advanceSeconds(2);
    verifyThat(send(GET(serverUri).header("Cache-Control", "only-if-cached")))
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
  @StoreSpec(tested = StoreType.DISK, fileSystem = FileSystemType.SYSTEM)
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
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Pikachu");

    // Make response stale.
    clock.advanceSeconds(2);
    server.enqueue(
        new MockResponse()
            .setResponseCode(HTTP_NOT_MODIFIED)
            .setHeader(headerName, networkHeaderValue));
    verifyThat(send(serverUri))
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

    server.enqueue(
        new MockResponse()
            .setHeaders(validators)
            .setHeader("Cache-Control", "max-age=2")
            .setHeader("X-Version", "v1")
            .setBody("STONKS!"));
    verifyThat(send(serverUri)) //
        .isCacheMiss()
        .hasBody("STONKS!")
        .containsHeader("X-Version", "v1");
    server.takeRequest(); // Remove initial request

    // Make stale or retain freshness
    clock.advanceSeconds(makeStale ? 3 : 1);

    server.enqueue(
        new MockResponse().setResponseCode(HTTP_NOT_MODIFIED).setHeader("X-Version", "v2"));
    verifyThat(send(triggeringRequest))
        .isConditionalHit()
        .hasBody("STONKS!")
        .containsHeader("X-Version", "v2");

    var sentRequest = server.takeRequest();

    // If-Modified-Since is only sent if Last-Modified is present.
    if (config.lastModified) {
      assertThat(sentRequest.getHeaders().getInstant("If-Modified-Since"))
          .isEqualTo(validators.getInstant("Last-Modified"));
    }

    // If-Non-Match is used only if ETag is present.
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

    server.enqueue(
        new MockResponse()
            .setHeaders(validators1)
            .setHeader("Cache-Control", "max-age=2")
            .setHeader("X-Version", "v1")
            .setBody("STONKS!"));
    verifyThat(send(serverUri)) //
        .isCacheMiss()
        .hasBody("STONKS!")
        .containsHeader("X-Version", "v1");
    server.takeRequest(); // Remove initial request

    // Make stale or retain freshness
    clock.advanceSeconds(makeStale ? 3 : 1);

    server.enqueue(
        new MockResponse()
            .setHeaders(validators2)
            .setHeader("Cache-Control", "max-age=2")
            .setHeader("X-Version", "v2")
            .setBody("DOUBLE STONKS!"));
    verifyThat(send(triggeringRequest))
        .isConditionalMiss()
        .hasBody("DOUBLE STONKS!")
        .containsHeader("X-Version", "v2")
        .containsHeaders(validators2.toMultimap())
        .cacheResponse() // This is the invalidated cache response
        .containsHeader("X-Version", "v1")
        .containsHeaders(validators1.toMultimap());

    // Retain updated response's freshness
    clock.advanceSeconds(1);

    verifyThat(send(serverUri))
        .isCacheHit()
        .hasBody("DOUBLE STONKS!")
        .containsHeader("X-Version", "v2")
        .containsHeaders(validators2.toMultimap());

    var sentRequest = server.takeRequest();
    if (config.lastModified) {
      assertThat(sentRequest.getHeaders().getInstant("If-Modified-Since"))
          .isEqualTo(validators1.getInstant("Last-Modified"));
    }
    if (config.etag) {
      assertThat(sentRequest.getHeader("If-None-Match")).isEqualTo("1");
    }
  }

  @StoreParameterizedTest
  void preconditionFieldsAreNotVisibleOnServedResponse(Store store) throws Exception {
    setUpCache(store);

    var lastModifiedString = instantToHttpDateString(clock.instant().minusSeconds(1));
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("ETag", "1")
            .setHeader("Last-Modified", lastModifiedString));
    verifyThat(send(serverUri)).isCacheMiss();

    // Make response stale
    clock.advanceSeconds(2);

    // Precondition fields aren't visible on the served response's request.
    // The preconditions are however visible from the network response's request.
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(send(serverUri))
        .isConditionalHit()
        .doesNotContainRequestHeader("If-None-Match")
        .doesNotContainRequestHeader("If-Modified-Since")
        .networkResponse()
        .containsRequestHeader("If-None-Match", "1")
        .containsRequestHeader("If-Modified-Since", lastModifiedString);
  }

  @StoreParameterizedTest
  void pastExpires(Store store) throws Exception {
    setUpCache(store);

    var date = toUtcDateTime(clock.instant());
    server.enqueue(
        new MockResponse()
            .setHeader("Date", formatHttpDate(date))
            .setHeader("Expires", formatHttpDate(date.minusSeconds(10)))
            .setBody("Pikachu"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Pikachu");

    // Negative freshness lifetime caused by past Expires triggers revalidation
    server.enqueue(
        new MockResponse()
            .setHeader("Date", formatHttpDate(date))
            .setHeader("Expires", formatHttpDate(date.minusSeconds(10)))
            .setBody("Psyduck"));
    verifyThat(send(serverUri)).isConditionalMiss().hasBody("Psyduck");
  }

  @StoreParameterizedTest
  void heuristicFreshnessWithFutureLastModified(Store store) throws Exception {
    setUpCache(store);

    var date = toUtcDateTime(clock.instant());
    var lastModified = date.plusSeconds(10);

    // Don't include explicit freshness to trigger heuristics, which relies on Last-Modified
    server.enqueue(
        new MockResponse()
            .setHeader("Date", formatHttpDate(date))
            .setHeader("Last-Modified", formatHttpDate(lastModified))
            .setBody("Pikachu"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Pikachu");

    // Negative heuristic lifetime caused by future Last-Modified triggers revalidation
    server.enqueue(
        new MockResponse()
            .setHeader("Date", formatHttpDate(date))
            .setHeader("Last-Modified", formatHttpDate(lastModified))
            .setBody("Psyduck"));
    verifyThat(send(serverUri))
        .isConditionalMiss()
        .hasBody("Psyduck")
        .networkResponse()
        .containsRequestHeader("If-Modified-Since", formatHttpDate(lastModified));
  }

  @StoreParameterizedTest
  void relaxMaxAgeWithRequest(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("tesla"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("tesla");

    // Make response stale
    clock.advanceSeconds(2);

    // Relaxed max-age retains freshness
    var request = GET(serverUri).header("Cache-Control", "max-age=3");
    verifyThat(send(request)).isCacheHit().hasBody("tesla");
  }

  @StoreParameterizedTest
  void constrainMaxAgeWithRequest(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=2").setBody("tesla"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("tesla");

    // Retain freshness
    clock.advanceSeconds(1);
    verifyThat(send(serverUri)).isCacheHit().hasBody("tesla");

    // Constrain max-age so that the response becomes stale
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    var request = GET(serverUri).header("Cache-Control", "max-age=1");
    verifyThat(send(request)).isConditionalHit().hasBody("tesla");
  }

  @StoreParameterizedTest
  void constrainFreshnessWithMinFresh(Store store) throws Exception {
    setUpCache(store);

    var lastModifiedInstant = clock.instant();
    clock.advanceSeconds(2);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=3")
            .setHeader("Last-Modified", instantToHttpDateString(lastModifiedInstant))
            .setBody("spaceX"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("spaceX");
    server.takeRequest(); // Drop request

    // Set freshness to 2 seconds
    clock.advanceSeconds(1);
    verifyThat(send(serverUri)).isCacheHit().hasBody("spaceX");

    var requestMinFresh2 = GET(serverUri).header("Cache-Control", "min-fresh=2");
    verifyThat(send(requestMinFresh2))
        .isCacheHit() // min-fresh is satisfied
        .hasBody("spaceX");

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=3")
            .setHeader("Last-Modified", instantToHttpDateString(lastModifiedInstant))
            .setBody("tesla"));

    var requestMinFresh3 = GET(serverUri).header("Cache-Control", "min-fresh=3");
    verifyThat(send(requestMinFresh3))
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
    verifyThat(send(serverUri)).isCacheMiss().hasBody("stale on a scale");

    // Make response stale by 2 seconds
    clock.advanceSeconds(3);

    BiConsumer<CacheControl, UnaryOperator<ResponseVerifier<String>>> assertStaleness =
        (cacheControl, cacheStatusAssert) -> {
          var request = GET(serverUri).cacheControl(cacheControl);
          var response =
              cacheStatusAssert
                  .apply(verifyThat(sendUnchecked(request)))
                  .hasBody("stale on a scale");
          // Must put a warning only if not revalidated
          if (response.getCacheAwareResponse().cacheStatus() == CacheStatus.HIT) {
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
  void mustRevalidateWhenStaleWithMaxStale(Store store) throws Exception {
    setUpCache(store);

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, must-revalidate")
            .setBody("Popeye"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Popeye");

    // Make response stale.
    clock.advanceSeconds(2);

    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    var request1 =
        GET(serverUri)
            .cacheControl(CacheControl.newBuilder().maxStale(Duration.ofSeconds(5)).build());
    verifyThat(send(request1)).isConditionalHit().hasBody("Popeye");

    // Make response stale.
    clock.advanceSeconds(2);

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, must-revalidate")
            .setBody("Olive Oyl"));
    var request2 =
        GET(serverUri)
            .cacheControl(CacheControl.newBuilder().maxStale(Duration.ofSeconds(5)).build());
    verifyThat(send(request2)).isConditionalMiss().hasBody("Olive Oyl");
  }

  @StoreParameterizedTest
  void mustRevalidateWhenStaleWithStaleWhileRevalidateAndStaleIfErrorOnResponse(Store store)
      throws Exception {
    setUpCache(store);

    // Although Cache-Control here doesn't make much sense, it ensures server's must-revalidate
    // never permits stale responses, even if allowed by the server itself.
    server.enqueue(
        new MockResponse()
            .setHeader(
                "Cache-Control",
                "max-age=1, must-revalidate, stale-while-revalidate=5, stale-if-error=5")
            .setBody("Popeye"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Popeye");

    // Make response stale.
    clock.advanceSeconds(2);

    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(send(serverUri)).isConditionalHit().hasBody("Popeye");

    // Make response stale.
    clock.advanceSeconds(2);

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, must-revalidate")
            .setBody("Olive Oyl"));
    verifyThat(send(serverUri)).isConditionalMiss().hasBody("Olive Oyl");
  }

  @StoreParameterizedTest
  void mustRevalidateWhenStaleWithStaleIfErrorOnRequest(Store store) throws Exception {
    setUpCache(store);

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, must-revalidate")
            .setBody("Popeye"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Popeye");

    // Make response stale.
    clock.advanceSeconds(2);

    client = clientBuilder.backendInterceptor(new FaultyInterceptor(TestException::new)).build();

    var request = GET(serverUri).header("Cache-Control", "stale-if-error=5");
    assertThatThrownBy(() -> send(request)).isInstanceOf(TestException.class);
  }

  @StoreParameterizedTest
  void mustRevalidateWhenFresh(Store store) throws Exception {
    setUpCache(store);

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, must-revalidate")
            .setBody("Picasso"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Picasso");

    // must-revalidate only applies to stale responses.
    verifyThat(send(serverUri)).isCacheHit().hasBody("Picasso");
  }

  @StoreParameterizedTest
  void cacheTwoPathsSameUri(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("alpha"));
    verifyThat(send(serverUri.resolve("/a"))).isCacheMiss().hasBody("alpha");

    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("beta"));
    verifyThat(send(serverUri.resolve("/b"))).isCacheMiss().hasBody("beta");

    verifyThat(send(serverUri.resolve("/a"))).isCacheHit().hasBody("alpha");
    verifyThat(send(serverUri.resolve("/b"))).isCacheHit().hasBody("beta");
  }

  @StoreParameterizedTest
  void preventCachingByNoStoreInResponse(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse().setHeader("Cache-Control", "no-store").setBody("alpha"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("alpha");
    assertNotStored(serverUri);

    // The response isn't stored.
    assertThat(store.view(HttpCache.toStoreKey(serverUri))).isEmpty();
  }

  @StoreParameterizedTest
  void preventCachingByNoStoreInRequest(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("alpha"));

    var request = GET(serverUri).header("Cache-Control", "no-store");
    verifyThat(send(request)).isCacheMiss().hasBody("alpha");
    assertNotStored(serverUri);

    // The response isn't stored.
    assertThat(store.view(HttpCache.toStoreKey(serverUri))).isEmpty();
  }

  @StoreParameterizedTest
  void preventCachingByWildcardVary(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("Vary", "*")
            .setBody("Cache me if you can!"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Cache me if you can!");
    assertNotStored(serverUri);

    // The response isn't stored.
    assertThat(store.view(HttpCache.toStoreKey(serverUri))).isEmpty();
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
    verifyThat(send(requestAlpha)).isCacheMiss().hasBody("alpha");
    verifyThat(send(requestAlpha))
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
    verifyThat(send(requestBeta)).isCacheMiss().hasBody("beta");
    verifyThat(send(requestBeta))
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
    verifyThat(send(requestPhi)).isCacheMiss().hasBody("ϕ");
    verifyThat(send(requestPhi))
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
  @StoreSpec(tested = StoreType.DISK, fileSystem = FileSystemType.SYSTEM)
  void responsesVaryingOnImplicitHeadersAreNotStored(String implicitField, Store store)
      throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("Vary", "Accept-Encoding, " + implicitField)
            .setBody("aaa"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("aaa");
    assertNotStored(serverUri);
    assertThat(store.view(HttpCache.toStoreKey(serverUri))).isEmpty();
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
    verifyThat(send(gzipRequest)).isCacheMiss().hasBody("Jigglypuff");
    verifyThat(send(gzipRequest)).isCacheHit().hasBody("Jigglypuff");

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("Vary", "Accept-Encoding")
            .setHeader("Content-Encoding", "deflate")
            .setBody(new okio.Buffer().write(deflate("Jigglypuff"))));

    var deflateRequest = GET(serverUri).header("Accept-Encoding", "deflate");
    verifyThat(send(deflateRequest))
        .isCacheMiss() // Gzip variant is replaced
        .hasBody("Jigglypuff");
    verifyThat(send(deflateRequest)).isCacheHit().hasBody("Jigglypuff");
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
    verifyThat(send(jeNeParlePasAnglais)).isCacheMiss().hasBody("magnifique");
    verifyThat(send(jeNeParlePasAnglais))
        .isCacheHit()
        .containsHeader("Content-Language", "fr-FR")
        .hasBody("magnifique")
        .cacheResponse()
        .containsRequestHeadersExactly(
            "Accept-Language", "fr-FR",
            "Accept-Encoding", "identity");
    verifyThat(send(jeNeParlePasAnglais.header("My-Header", "a")))
        .isCacheHit()
        .hasBody("magnifique");

    // Current variant has no Accept header, so this won't match
    var withTextHtml =
        jeNeParlePasAnglais.header("Accept", "text/html").header("Cache-Control", "only-if-cached");
    verifyThat(send(withTextHtml)).isCacheUnsatisfaction();

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
    verifyThat(send(noHabloIngles))
        .isCacheMiss() // French variant is replaced
        .hasBody("magnífico");
    verifyThat(send(noHabloIngles))
        .isCacheHit()
        .containsHeader("Content-Language", "es-ES")
        .hasBody("magnífico")
        .cacheResponse()
        .containsRequestHeadersExactly(
            "Accept-Language", "es-ES",
            "Accept-Encoding", "identity",
            "Accept", "text/html");
    verifyThat(send(noHabloIngles.header("My-Header", "a"))).isCacheHit().hasBody("magnífico");

    // Request with different Accept won't match
    var withApplicationJson =
        noHabloIngles
            .header("Accept", "application/json")
            .header("Cache-Control", "only-if-cached");
    verifyThat(send(withApplicationJson)).isCacheUnsatisfaction();

    // Absent varying fields won't match a request containing them
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .addHeader("Vary", "Accept-Encoding, Accept-Language")
            .addHeader("Vary", "Accept")
            .setHeader("Content-Language", "en-US")
            .setBody("Lit!"));
    verifyThat(send(serverUri))
        .isCacheMiss() // Spanish variant is replaced
        .hasBody("Lit!");
    verifyThat(send(serverUri))
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
    verifyThat(send(requestAlpha)).isCacheMiss().hasBody("alpha");
    verifyThat(send(requestAlpha)).isCacheHit().hasBody("alpha");

    // This matches as values are only different in order
    var requestBeta =
        GET(serverUri)
            .header("My-Header", "val2")
            .header("My-Header", "val3")
            .header("My-Header", "val1");
    verifyThat(send(requestBeta)).isCacheHit().hasBody("alpha");

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("Vary", "My-Header")
            .setBody("beta"));

    // This doesn't match as there're 2 values vs alpha variant's 3
    var requestBeta2 = GET(serverUri).header("My-Header", "val1").header("My-Header", "val2");
    verifyThat(send(requestBeta2)).isCacheMiss().hasBody("beta");
    verifyThat(send(requestBeta2)).isCacheHit().hasBody("beta");

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("Vary", "My-Header")
            .setBody("charlie"));

    // Request with no varying header values doesn't match
    verifyThat(send(serverUri)).isCacheMiss().hasBody("charlie");
    verifyThat(send(serverUri)).isCacheHit().hasBody("charlie");
  }

  @StoreParameterizedTest
  void cacheMovedPermanently(Store store) throws Exception {
    setUpCache(store);
    client = clientBuilder.followRedirects(Redirect.ALWAYS).build();

    server.enqueue(
        new MockResponse()
            .setResponseCode(301)
            .setHeader("Location", "/redirect")
            .setHeader("Cache-Control", "max-age=1"));
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "no-store") // Prevent caching
            .setBody("Ey yo"));
    verifyThat(send(serverUri)).hasCode(200).isCacheMiss().hasBody("Ey yo");

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "no-store") // Prevent caching
            .setBody("Ey yo"));
    verifyThat(send(serverUri))
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

    verifyThat(send(serverUri)).hasCode(301).isCacheHit();
  }

  @StoreParameterizedTest
  void cacheTemporaryRedirectAndRedirectTarget(Store store) throws Exception {
    setUpCache(store);
    client = clientBuilder.followRedirects(Redirect.ALWAYS).build();

    server.enqueue(
        new MockResponse()
            .setResponseCode(307)
            .setHeader("Cache-Control", "max-age=3")
            .setHeader("Location", "/redirect"));
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Ey yo"));
    verifyThat(send(serverUri)).hasCode(200).isCacheMiss().hasBody("Ey yo");

    verifyThat(send(serverUri))
        .hasCode(200)
        .hasUri(serverUri.resolve("/redirect"))
        .isCacheHit()
        .hasBody("Ey yo")
        .previousResponse()
        .hasCode(307)
        .isCacheHit()
        .containsHeader("Location", "/redirect");
    verifyThat(send(serverUri.resolve("/redirect"))).hasCode(200).isCacheHit().hasBody("Ey yo");

    // Disable auto redirection
    client = clientBuilder.followRedirects(Redirect.NEVER).build();

    verifyThat(send(serverUri)).hasCode(307).isCacheHit();
    verifyThat(send(serverUri.resolve("/redirect"))).hasCode(200).isCacheHit().hasBody("Ey yo");

    // Make 200 response stale but retain 307 response's freshness
    clock.advanceSeconds(2);

    // Enable auto redirection
    client = clientBuilder.followRedirects(Redirect.ALWAYS).build();

    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=2").setBody("Hey there"));
    verifyThat(send(serverUri))
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
    verifyThat(send(serverUri)).hasCode(200).isCacheMiss().hasBody("Wakanda forever");

    server.enqueue(new MockResponse().setResponseCode(307).setHeader("Location", "/redirect"));
    verifyThat(send(serverUri))
        .hasCode(200)
        .isCacheHit() // 200 response is cached
        .hasBody("Wakanda forever")
        .previousResponse()
        .hasCode(307)
        .isCacheMiss();
  }

  @ParameterizedTest
  @StoreSpec(tested = StoreType.DISK, fileSystem = FileSystemType.SYSTEM)
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
    verifyThat(send(serverUri)).hasCode(200).isCacheMiss().hasBody("Wakanda forever");

    server.enqueue(
        new MockResponse().setHeader("Cache-Control", "no-store").setBody("Wakanda forever"));
    verifyThat(send(serverUri))
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
    verifyThat(send(serverUri)).hasCode(code).isCacheHit();
    verifyThat(send(serverUri.resolve("/redirect")))
        .hasCode(200)
        .isCacheMiss()
        .hasBody("Wakanda forever");
  }

  @ParameterizedTest
  @StoreSpec(tested = StoreType.DISK, fileSystem = FileSystemType.SYSTEM)
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
    verifyThat(send(serverUri)).hasCode(200).isCacheMiss().hasBody("Wakanda forever");

    server.enqueue(redirectingResponse);
    verifyThat(send(serverUri))
        .hasCode(200)
        .isCacheHit() // Target response is cached
        .hasBody("Wakanda forever")
        .previousResponse()
        .hasCode(code)
        .isCacheMiss(); // Redirecting response isn't cached

    // Disable auto redirection
    client = clientBuilder.followRedirects(Redirect.NEVER).build();

    server.enqueue(redirectingResponse);
    verifyThat(send(serverUri)).hasCode(code).isCacheMiss();
    verifyThat(send(serverUri.resolve("/redirect")))
        .hasCode(200)
        .isCacheHit()
        .hasBody("Wakanda forever");
  }

  @ParameterizedTest
  @StoreSpec(tested = StoreType.DISK, fileSystem = FileSystemType.SYSTEM)
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
    verifyThat(send(serverUri)).hasCode(200).isCacheMiss().hasBody("Wakanda forever");

    server.enqueue(redirectingResponse);
    server.enqueue(
        new MockResponse().setHeader("Cache-Control", "no-store").setBody("Wakanda forever"));
    verifyThat(send(serverUri))
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
    verifyThat(send(serverUri)).hasCode(code).isCacheMiss();
    verifyThat(send(serverUri.resolve("/redirect")))
        .hasCode(200)
        .isCacheMiss()
        .hasBody("Wakanda forever");
  }

  @ParameterizedTest
  @StoreSpec(tested = StoreType.DISK, fileSystem = FileSystemType.SYSTEM)
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
    verifyThat(send(serverUri)).hasCode(200).isCacheMiss().hasBody("Wakanda forever");
    verifyThat(send(serverUri))
        .hasCode(200)
        .isCacheHit() // Target response is cached
        .hasBody("Wakanda forever")
        .previousResponse()
        .hasCode(code)
        .isCacheHit(); // Redirecting response is cached

    // Disable auto redirection
    client = clientBuilder.followRedirects(Redirect.NEVER).build();

    verifyThat(send(serverUri)).hasCode(code).isCacheHit();
    verifyThat(send(serverUri.resolve("/redirect")))
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
        Methanol.newBuilder().interceptor(cache.interceptor()).followRedirects(Redirect.ALWAYS);
    client = clientBuilder.build();

    // The cache only sees the second response
    server.enqueue(new MockResponse().setResponseCode(301).setHeader("Location", "/redirect"));
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Pikachu"));

    verifyThat(send(serverUri))
        .hasCode(200)
        .hasUri(serverUri.resolve("/redirect"))
        .isCacheMiss()
        .hasBody("Pikachu");
    assertNotStored(serverUri.resolve("/redirect"));
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
            .setHeader("Last-Modified", instantToHttpDateString(lastModifiedInstant))
            .setHeader("Date", instantToHttpDateString(dateInstant))
            .setBody("Pikachu"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Pikachu");
    verifyThat(send(serverUri)).isCacheHit().hasBody("Pikachu");
    server.takeRequest(); // Remove initial request

    // Make response stale by 2 seconds
    clock.advanceSeconds(3);

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, stale-while-revalidate=2")
            .setHeader("ETag", "2")
            .setHeader("Last-Modified", instantToHttpDateString(clock.instant().minusSeconds(1)))
            .setHeader("Date", instantToHttpDateString(clock.instant()))
            .setBody("Ricardo"));
    verifyThat(send(serverUri))
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

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, stale-while-revalidate=2")
            .setHeader("ETag", "1")
            .setBody("Pikachu"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Pikachu");
    verifyThat(send(serverUri)).isCacheHit().hasBody("Pikachu");

    // Make response stale by 3 seconds (unsatisfied stale-while-revalidate)
    clock.advanceSeconds(4);

    // Synchronous revalidation is issued when stale-while-revalidate isn't satisfied
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(send(serverUri))
        .isConditionalHit()
        .hasBody("Pikachu")
        .containsHeader("ETag", "1")
        .doesNotContainHeader("Warning");

    // Make response stale by 3 seconds (unsatisfied stale-while-revalidate)
    clock.advanceSeconds(4);

    // Synchronous revalidation is issued when stale-while-revalidate isn't satisfied
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, stale-while-revalidate=2")
            .setHeader("ETag", "2")
            .setBody("Ricardo"));
    verifyThat(send(serverUri))
        .isConditionalMiss()
        .hasBody("Ricardo")
        .containsHeader("ETag", "2")
        .doesNotContainHeader("Warning");
  }

  @ParameterizedTest
  @StoreSpec(tested = StoreType.DISK, fileSystem = FileSystemType.SYSTEM)
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
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Ricardo");
    verifyThat(send(serverUri)).isCacheHit().hasBody("Ricardo");

    // Make response stale by 1 second
    clock.advanceSeconds(2);

    server.enqueue(new MockResponse().setResponseCode(code));
    verifyThat(send(serverUri))
        .hasCode(200)
        .isCacheHit()
        .hasBody("Ricardo")
        .containsHeader("Warning", "110 - \"Response is Stale\"");

    // Make response stale by 2 seconds
    clock.advanceSeconds(1);

    server.enqueue(new MockResponse().setResponseCode(code));
    verifyThat(send(serverUri))
        .hasCode(200)
        .isCacheHit()
        .hasBody("Ricardo")
        .containsHeader("Warning", "110 - \"Response is Stale\"");
  }

  @ParameterizedTest
  @StoreSpec(tested = StoreType.DISK, fileSystem = FileSystemType.SYSTEM)
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
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Ditto");
    verifyThat(send(serverUri)).isCacheHit().hasBody("Ditto");

    // Make response stale by 2 seconds
    clock.advanceSeconds(3);

    // stale-if-error isn't satisfied
    server.enqueue(new MockResponse().setResponseCode(code));
    verifyThat(send(serverUri))
        .hasCode(code)
        .isCacheMissWithCacheResponse()
        .hasBody("")
        .doesNotContainHeader("Warning");
  }

  private static final class FaultyInterceptor implements Interceptor {
    private final Supplier<Throwable> failureFactory;

    FaultyInterceptor(Supplier<Throwable> failureFactory) {
      this.failureFactory = failureFactory;
    }

    FaultyInterceptor(Class<? extends Throwable> failureType) {
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
  @StoreSpec(tested = StoreType.DISK, fileSystem = FileSystemType.SYSTEM)
  @ValueSource(classes = {ConnectException.class, UnknownHostException.class})
  void staleIfErrorWithConnectionFailure(Class<? extends Throwable> failureType, Store store)
      throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, stale-if-error=2")
            .setBody("Jigglypuff"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Jigglypuff");
    verifyThat(send(serverUri)).isCacheHit().hasBody("Jigglypuff");

    // Make response stale by 1 second
    clock.advanceSeconds(2);

    client = clientBuilder.backendInterceptor(new FaultyInterceptor(failureType)).build();
    verifyThat(send(serverUri))
        .hasCode(200)
        .isCacheHit()
        .hasBody("Jigglypuff")
        .containsHeader("Warning", "110 - \"Response is Stale\"");

    // Make response stale by 2 seconds
    clock.advanceSeconds(1);

    server.enqueue(new MockResponse().setBody("huh?"));
    verifyThat(send(serverUri))
        .hasCode(200)
        .isCacheHit()
        .hasBody("Jigglypuff")
        .containsHeader("Warning", "110 - \"Response is Stale\"");
  }

  @ParameterizedTest
  @StoreSpec(tested = StoreType.DISK, fileSystem = FileSystemType.SYSTEM)
  @ValueSource(classes = {ConnectException.class, UnknownHostException.class})
  void unsatisfiedStaleIfErrorWithConnectionFailure(
      Class<? extends Throwable> failureType, Store store) throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, stale-if-error=1")
            .setBody("Ricardo"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Ricardo");
    verifyThat(send(serverUri)).isCacheHit().hasBody("Ricardo");

    // Make response stale by 2 seconds
    clock.advanceSeconds(3);

    client = clientBuilder.backendInterceptor(new FaultyInterceptor(failureType)).build();

    // stale-if-error isn't satisfied
    assertThatExceptionOfType(failureType).isThrownBy(() -> send(serverUri));
  }

  @StoreParameterizedTest
  void staleIfErrorWithInapplicableErrorCode(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, stale-if-error=1")
            .setBody("Eevee"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Eevee");
    verifyThat(send(serverUri)).isCacheHit().hasBody("Eevee");

    // Make response stale by 1 second
    clock.advanceSeconds(2);

    // Only 5xx error codes are applicable to stale-if-error
    server.enqueue(new MockResponse().setResponseCode(404));
    verifyThat(send(serverUri))
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
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Charmander");
    verifyThat(send(serverUri)).isCacheHit().hasBody("Charmander");

    // Make requests fail with a inapplicable exception
    client = clientBuilder.backendInterceptor(new FaultyInterceptor(TestException::new)).build();

    // Make response stale by 1 second
    clock.advanceSeconds(2);

    // stale-if-error isn't satisfied
    assertThatExceptionOfType(TestException.class).isThrownBy(() -> send(serverUri));
  }

  @StoreParameterizedTest
  void staleIfErrorWithUncheckedIOException(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1, stale-if-error=1")
            .setBody("Jynx"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Jynx");
    verifyThat(send(serverUri)).isCacheHit().hasBody("Jynx");

    // Make requests fail with ConnectException disguised as an UncheckedIOException
    client =
        clientBuilder
            .backendInterceptor(
                new FaultyInterceptor(() -> new UncheckedIOException(new ConnectException())))
            .build();

    // Make response stale by 1 second
    clock.advanceSeconds(2);

    // stale-if-error is applicable
    var request = GET(serverUri).header("Cache-Control", "stale-if-error=2");
    verifyThat(send(request))
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
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Psyduck");
    verifyThat(send(serverUri)).isCacheHit().hasBody("Psyduck");

    // Make response stale by 3 seconds
    clock.advanceSeconds(4);

    // Only request's stale-if-error is satisfied
    server.enqueue(new MockResponse().setResponseCode(HTTP_INTERNAL_ERROR));
    var request1 = GET(serverUri).header("Cache-Control", "stale-if-error=3");
    verifyThat(send(request1)).hasCode(200).isCacheHit().hasBody("Psyduck");

    // Refresh response
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(send(serverUri)).isConditionalHit();

    // Make response stale by 2 seconds
    clock.advanceSeconds(3);

    // Unsatisfied request's stale-if-error takes precedence
    server.enqueue(new MockResponse().setResponseCode(500));
    var request2 = GET(serverUri).header("Cache-Control", "stale-if-error=1");
    verifyThat(send(request2)).hasCode(500).isConditionalMiss().hasBody("");
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
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Dwight the trickster");

    // Make response stale
    clock.advanceSeconds(2);

    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(send(serverUri))
        .isConditionalHit()
        .hasBody("Dwight the trickster")
        .containsHeader("Warning", "299 - \"EVERY BODY STAY CALM\""); // Warn code 199 is removed
  }

  /**
   * Tests that status codes in rfc7231 6.1 without Cache-Control or Expires are only cached if
   * defined as cacheable by default ({@link #CACHEABLE_BY_DEFAULT_CODES}).
   */
  @ParameterizedTest
  @ValueSource(
      ints = {
        100, 101, 200, 201, 202, 203, 204, 205, 206, 300, 301, 302, 303, 304, 305, 307, 400, 401,
        402, 403, 404, 405, 406, 407, 408, 409, 410, 411, 412, 413, 414, 415, 416, 417, 426, 500,
        501, 502, 503, 504, 505
      })
  @StoreSpec(tested = StoreType.DISK, fileSystem = FileSystemType.SYSTEM)
  void defaultCacheability(int code, Store store) throws Exception {
    setUpCache(store);
    client =
        clientBuilder
            .version(Version.HTTP_1_1) // HTTP_2 doesn't let 101 pass
            .followRedirects(Redirect.NEVER) // Disable redirections in case code is 3xx
            .build();

    if (code == HTTP_UNAVAILABLE) {
      failOnUnavailableResponses = false;
    }

    // At least after Java 21, the HttpClient ignores 100 responses and never returns them (which is
    // permitted by the spec). Testing cache behavior for 100 is not possible in such case as the
    // client just hangs.
    assumeTrue(code != 100, "HttpClient ignores status code");

    // The HttpClient panics if the server tries to switch protocols without being explicitly asked
    // to.
    assumeTrue(code != 101, "HttpClient panics on unexpected protocol switch");

    // Caching not supported.
    assumeTrue(code != 206, "Caching partial content isn't supported");

    // Last-Modified:      20 seconds from date
    // Heuristic lifetime: 2 seconds
    var lastModifiedInstant = clock.instant();
    clock.advanceSeconds(20);
    var dateInstant = clock.instant();
    var body =
        code == 204 || code == 304 ? "" : "Cache me pls!"; // Body with 204 or 304 causes problems.
    server.enqueue(
        new MockResponse()
            .setResponseCode(code)
            .setHeader("Last-Modified", instantToHttpDateString(lastModifiedInstant))
            .setHeader("Date", instantToHttpDateString(dateInstant))
            .setBody(body));
    verifyThat(send(serverUri)).hasCode(code).hasBody(body).isCacheMiss();

    // Retrain heuristic freshness.
    clock.advanceSeconds(1);

    ResponseVerifier<String> response;
    if (CACHEABLE_BY_DEFAULT_CODES.contains(code)) {
      response = verifyThat(send(serverUri)).isCacheHit();
    } else {
      server.enqueue(
          new MockResponse()
              .setResponseCode(code)
              .setHeader("Last-Modified", instantToHttpDateString(lastModifiedInstant))
              .setHeader("Date", instantToHttpDateString(dateInstant))
              .setBody(body));
      response = verifyThat(send(serverUri)).isCacheMiss();
    }
    response.hasCode(code).hasBody(body);
  }

  @StoreParameterizedTest
  void heuristicExpiration(Store store) throws Exception {
    setUpCache(store);
    // Last-Modified:      30 seconds from date
    // Heuristic lifetime: 3 seconds (10% of duration between Last-Modified & Date)
    // Age:                1 second
    var lastModifiedInstant = clock.instant();
    clock.advanceSeconds(30);
    var dateInstant = clock.instant();
    clock.advanceSeconds(1);
    server.enqueue(
        new MockResponse()
            .setHeader("Last-Modified", instantToHttpDateString(lastModifiedInstant))
            .setHeader("Date", instantToHttpDateString(dateInstant))
            .setBody("Cache me pls!"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Cache me pls!");
    verifyThat(send(serverUri)).isCacheHit().hasBody("Cache me pls!").containsHeader("Age", "1");

    // Retain heuristic freshness (age = 2 secs, heuristic lifetime = 3 secs)
    clock.advanceSeconds(1);

    verifyThat(send(serverUri))
        .isCacheHit()
        .hasBody("Cache me pls!")
        .containsHeader("Age", "2")
        .doesNotContainHeader("Warning");

    // Make response stale (age = 3 secs, heuristic lifetime = 3 secs)
    clock.advanceSeconds(1);

    verifyThat(send(GET(serverUri).cacheControl(CacheControl.newBuilder().onlyIfCached().build())))
        .isCacheUnsatisfaction();

    var revalidationDateInstant = clock.instant();
    clock.advanceSeconds(1); // curr age = 4 seconds, new (revalidated) age = 1 second.
    server.enqueue(
        new MockResponse()
            .setResponseCode(HTTP_NOT_MODIFIED)
            .setHeader("Date", instantToHttpDateString(revalidationDateInstant))
            .setHeader(
                "Last-Modified",
                instantToHttpDateString(revalidationDateInstant.minusSeconds(30))));
    verifyThat(send(serverUri))
        .isConditionalHit()
        .hasBody("Cache me pls!")
        .doesNotContainHeader("Age") // A revalidated response has no Age.
        .doesNotContainHeader("Warning");
    verifyThat(send(serverUri))
        .isCacheHit()
        .hasBody("Cache me pls!")
        .containsHeader("Age", "1")
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
            .setHeader("Last-Modified", instantToHttpDateString(lastModifiedInstant))
            .setHeader("Date", instantToHttpDateString(dateInstant))
            .setBody("Cache me pls!"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Cache me pls!");

    // Retain heuristic freshness (age = 1 day + 1 second, heuristic lifetime = 2 days)
    clock.advance(Duration.ofDays(1).plusSeconds(1));

    verifyThat(send(serverUri))
        .isCacheHit()
        .hasBody("Cache me pls!")
        .containsHeader("Age", Duration.ofDays(1).plusSeconds(1).toSeconds())
        .containsHeader("Warning", "113 - \"Heuristic Expiration\"");
  }

  /** See https://tools.ietf.org/html/rfc7234#section-4.2.3. */
  @StoreParameterizedTest
  void computingAge(Store store) throws Exception {
    setUpCache(store);

    // Simulate response taking 3 seconds to arrive.
    advanceOnSend = Duration.ofSeconds(3);

    // date_value = x
    // age_value = 10
    server.enqueue(
        new MockResponse()
            .setHeader("Date", instantToHttpDateString(clock.instant()))
            .setHeader("Cache-Control", "max-age=60")
            .setHeader("Age", "10"));

    // now = x + 2
    clock.advanceSeconds(2);

    // request_time = x + 2
    // response_time = request_time + 3 = x + 5
    // now = x + 2 + 3 = x + 5
    verifyThat(send(serverUri)) // Put in cache & advance clock
        .isCacheMiss()
        .requestWasSentAt(clock.instant().minusSeconds(3))
        .responseWasReceivedAt(clock.instant());

    // now = x + 10
    clock.advanceSeconds(5);

    // apparent_age = max(0, response_time - date_value) = 5
    // response_delay = response_time - request_time = 3
    // corrected_age_value = age_value + response_delay = 13
    // corrected_initial_age = max(apparent_age, corrected_age_value) = 13
    // resident_time = now - response_time = 5
    // current_age = corrected_initial_age + resident_time = 18
    verifyThat(send(serverUri)).isCacheHit().containsHeader("Age", "18");
  }

  /**
   * See https://tools.ietf.org/html/rfc7234#section-4.2.3. When a date is not present, the time the
   * response was received is used instead.
   */
  @StoreParameterizedTest
  void computingAgeNoDate(Store store) throws Exception {
    setUpCache(store);

    // Simulate response taking 3 seconds to arrive
    advanceOnSend = Duration.ofSeconds(3);

    // age_value = 10
    server.enqueue(
        new MockResponse().setHeader("Cache-Control", "max-age=60").setHeader("Age", "10"));

    // now = x + 2
    clock.advanceSeconds(2);

    // request_time = x + 2
    // response_time = request_time + 3 = x + 5
    // date_value = response_time = x + 5
    // now = x + 2 + 3 = x + 5
    verifyThat(send(serverUri)) // Put in cache & advance clock
        .isCacheMiss()
        .requestWasSentAt(clock.instant().minusSeconds(3))
        .responseWasReceivedAt(clock.instant());

    // now = x + 10
    clock.advanceSeconds(5);

    // apparent_age = max(0, response_time - date_value) = 0
    // response_delay = response_time - request_time = 3
    // corrected_age_value = age_value + response_delay = 13
    // corrected_initial_age = max(apparent_age, corrected_age_value) = 13
    // resident_time = now - response_time = 5
    // current_age = corrected_initial_age + resident_time = 18
    verifyThat(send(serverUri)).isCacheHit().containsHeader("Age", "18");
  }

  @ParameterizedTest
  @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
  @StoreSpec(tested = StoreType.DISK, fileSystem = FileSystemType.SYSTEM)
  void unsafeMethodsInvalidateCache(String method, StoreContext storeContext) throws Exception {
    assertUnsafeMethodInvalidatesCache(storeContext, method, 200, true);
    assertUnsafeMethodInvalidatesCache(storeContext, method, 302, true);
  }

  @ParameterizedTest
  @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
  @StoreSpec(tested = StoreType.DISK, fileSystem = FileSystemType.SYSTEM)
  void unsafeMethodsDoNotInvalidateCacheWithErrorResponse(String method, StoreContext storeContext)
      throws Exception {
    assertUnsafeMethodInvalidatesCache(storeContext, method, 404, false);
    assertUnsafeMethodInvalidatesCache(storeContext, method, 504, false);
  }

  private void assertUnsafeMethodInvalidatesCache(
      StoreContext storeContext, String method, int code, boolean invalidationExpected)
      throws Exception {
    // Perform cleanup for previous call
    if (cache != null) {
      cache.dispose();
    }
    setUpCache(storeContext.createAndRegisterStore());

    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Pikachu"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Pikachu");
    verifyThat(send(serverUri)).isCacheHit().hasBody("Pikachu");

    server.enqueue(new MockResponse().setResponseCode(code).setBody("Charmander"));
    var unsafeRequest = MutableRequest.create(serverUri).method(method, BodyPublishers.noBody());
    verifyThat(send(unsafeRequest)).isCacheMiss();

    if (invalidationExpected) {
      assertNotStored(serverUri);
    } else {
      verifyThat(send(serverUri)).hasCode(200).isCacheHit().hasBody("Pikachu");
    }
  }

  /**
   * Test that an invalidated response causes the URIs referenced via Location & Content-Location to
   * also get invalidated (https://tools.ietf.org/html/rfc7234#section-4.4).
   */
  // TODO find a way to test referenced URIs aren't invalidated if they have different hosts
  @ParameterizedTest
  @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
  @StoreSpec(tested = StoreType.DISK, fileSystem = FileSystemType.SYSTEM)
  void unsafeMethodsInvalidateReferencedUris(String method, Store store) throws Exception {
    setUpCache(store);
    client = clientBuilder.followRedirects(Redirect.NEVER).build();

    server.enqueue(
        new MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
            .setHeader("Cache-Control", "max-age=1")
            .setBody("Pikachu"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Pikachu");

    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Ditto"));
    verifyThat(send(serverUri.resolve("ditto"))).isCacheMiss().hasBody("Ditto");

    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Eevee"));
    verifyThat(send(serverUri.resolve("eevee"))).isCacheMiss().hasBody("Eevee");

    verifyThat(send(serverUri)).isCacheHit().hasBody("Pikachu");
    verifyThat(send(serverUri.resolve("ditto"))).isCacheHit().hasBody("Ditto");
    verifyThat(send(serverUri.resolve("eevee"))).isCacheHit().hasBody("Eevee");

    server.enqueue(
        new MockResponse()
            .setHeader("Location", "ditto")
            .setHeader("Content-Location", "eevee")
            .setBody("Eevee"));
    var unsafeRequest = MutableRequest.create(serverUri).method(method, BodyPublishers.noBody());
    verifyThat(send(unsafeRequest)).isCacheMiss().hasBody("Eevee");
    assertNotStored(serverUri.resolve("ditto"));
    assertNotStored(serverUri.resolve("eevee"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
  @StoreSpec(tested = StoreType.DISK, fileSystem = FileSystemType.SYSTEM)
  void unsafeMethodsAreNotCached(String method, Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=2").setBody("Pikachu"));

    var unsafeRequest = MutableRequest.create(serverUri).method(method, BodyPublishers.noBody());
    verifyThat(send(unsafeRequest)).isCacheMiss().hasBody("Pikachu");
    assertNotStored(serverUri);
  }

  @StoreParameterizedTest
  void headOfCachedGet(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=2").setBody("Mewtwo"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Mewtwo");

    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=2"));
    var head = MutableRequest.create(serverUri).method("HEAD", BodyPublishers.noBody());
    verifyThat(send(head)).isCacheMiss().hasBody("");

    verifyThat(send(serverUri)).isCacheHit().hasBody("Mewtwo");
  }

  @UseHttps
  @StoreParameterizedTest
  void requestsWithPushPromiseHandlersBypassCache(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(
        new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Steppenwolf"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Steppenwolf");
    verifyThat(send(serverUri)).isCacheHit().hasBody("Steppenwolf");

    // Make cached response stale
    clock.advanceSeconds(2);

    // Requests with push promises aren't served by the cache as it can't know what might be pushed
    // by the server. The main response contributes to updating the cache as usual.
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Darkseid"));
    verifyThat(
            send(
                client,
                GET(serverUri),
                BodyHandlers.ofString(),
                PushPromiseHandler.of(__ -> BodyHandlers.ofString(), new ConcurrentHashMap<>())))
        .isCacheMiss()
        .hasBody("Darkseid");
    verifyThat(send(serverUri)).isCacheHit().hasBody("Darkseid");
  }

  private enum PreconditionKind {
    DATE("If-Unmodified-Since", "If-Modified-Since") {
      @Override
      void add(MutableRequest request, String field, Clock clock) {
        request.header(field, instantToHttpDateString(clock.instant().minusSeconds(3)));
      }
    },
    TAG("If-Match", "If-None-Match", "If-Range") { // If range can be either a tag or a date.
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
  @ValueSource(
      strings = {
        "If-Match",
        "If-Unmodified-Since",
        //        "If-None-Match",
        "If-Range",
        //        "If-Modified-Since"
      })
  @StoreSpec(tested = StoreType.DISK, fileSystem = FileSystemType.SYSTEM)
  void requestWithUnsupportedPreconditionIsForwarded(String preconditionField, Store store)
      throws Exception {
    setUpCache(store);

    putInCache(new MockResponse().setBody("For Darkseid").addHeader("Cache-Control", "max-age=1"));

    var request = GET(serverUri);
    PreconditionKind.get(preconditionField).add(request, preconditionField, clock);
    server.enqueue(
        new MockResponse().setBody("For Darkseid").addHeader("Cache-Control", "max-age=1"));
    verifyThat(send(request)).isCacheMiss().hasBody("For Darkseid");
    verifyThat(send(request.removeHeader(preconditionField))).isCacheHit().hasBody("For Darkseid");
  }

  @StoreParameterizedTest
  void ifNoneMatchWithStrongTag(Store store) throws Exception {
    setUpCache(store);

    putInCache(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("ETag", "\"1\"")
            .setBody("abc"));

    verifyThat(send(GET(serverUri).header("If-None-Match", "\"1\"")))
        .isExternallyConditionalCacheHit()
        .hasBody("");
    verifyThat(send(GET(serverUri).header("If-None-Match", "\"2\""))).isCacheHit().hasBody("abc");

    // Weak comparison (used in If-None-Match) matches weak tags to equivalent strong tag.
    verifyThat(send(GET(serverUri).header("If-None-Match", "W/\"1\"")))
        .isExternallyConditionalCacheHit()
        .hasBody("");

    clock.advanceSeconds(2);

    // Preconditions are ignored when the response is stale.
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(send(GET(serverUri).header("If-None-Match", "\"1\"")))
        .isConditionalHit()
        .hasBody("abc");
  }

  @StoreParameterizedTest
  void ifNoneMatchWithWeakTag(Store store) throws Exception {
    setUpCache(store);

    putInCache(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("ETag", "W/\"1\"")
            .setBody("abc"));

    verifyThat(send(GET(serverUri).header("If-None-Match", "W/\"1\"")))
        .isExternallyConditionalCacheHit()
        .hasBody("");
    verifyThat(send(GET(serverUri).header("If-None-Match", "\"2\""))).isCacheHit().hasBody("abc");

    // Weak comparison (used in If-None-Match) matches strong tags to equivalent weak tag.
    verifyThat(send(GET(serverUri).header("If-None-Match", "\"1\"")))
        .isExternallyConditionalCacheHit()
        .hasBody("");

    verifyThat(send(GET(serverUri).header("If-None-Match", "\"\""))).isCacheHit().hasBody("abc");

    clock.advanceSeconds(2);

    // Preconditions are ignored when the response is stale.
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(send(GET(serverUri).header("If-None-Match", "W/\"1\"")))
        .isConditionalHit()
        .hasBody("abc");
  }

  @StoreParameterizedTest
  void ifNoneMatchWithMultipleTagsForStrongTag(Store store) throws Exception {
    setUpCache(store);

    putInCache(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("ETag", "\"1\"")
            .setBody("abc"));

    verifyThat(send(GET(serverUri).header("If-None-Match", "\"1\", \"2\"")))
        .isExternallyConditionalCacheHit()
        .hasBody("");
    verifyThat(send(GET(serverUri).header("If-None-Match", "\"2\", \"1\"")))
        .isExternallyConditionalCacheHit()
        .hasBody("");
    verifyThat(send(GET(serverUri).header("If-None-Match", "W/\"1\", \"2\"")))
        .isExternallyConditionalCacheHit()
        .hasBody("");
    verifyThat(send(GET(serverUri).header("If-None-Match", "\"2\", W/\"4\",  W/\"1\", \"3\"")))
        .isExternallyConditionalCacheHit()
        .hasBody("");

    verifyThat(send(GET(serverUri).header("If-None-Match", "\"2\", \"3\", \"4\"")))
        .isCacheHit()
        .hasBody("abc");
    verifyThat(send(GET(serverUri).header("If-None-Match", "\"12\", \"2\", \"3\", \"4\"")))
        .isCacheHit()
        .hasBody("abc");
  }

  @StoreParameterizedTest
  void ifNoneMatchWithInvalidETag(Store store) throws Exception {
    setUpCache(store);

    putInCache(
        new MockResponse()
            .setBody("abc")
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("ETag", "W/\"1\""));

    verifyThat(send(GET(serverUri).header("If-None-Match", "1"))) // Unquoted.
        .isCacheHit()
        .hasBody("abc");
    verifyThat(send(GET(serverUri).header("If-None-Match", "\"1"))) // Mis-quoted.
        .isCacheHit()
        .hasBody("abc");
    verifyThat(send(GET(serverUri).header("If-None-Match", "1\""))) // Mis-quoted.
        .isCacheHit()
        .hasBody("abc");
    verifyThat(send(GET(serverUri).header("If-None-Match", ""))) // Unquoted.
        .isCacheHit()
        .hasBody("abc");
  }

  @StoreParameterizedTest
  void ifNoneMatchWithStar(Store store) throws Exception {
    setUpCache(store);

    putInCache(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("ETag", "\"1\"")
            .setBody("abc"));

    verifyThat(send(GET(serverUri).header("If-None-Match", "*"))).isCacheHit().hasBody("abc");
  }

  @StoreParameterizedTest
  void ifNoneMatchWithAbsentEtag(Store store) throws Exception {
    setUpCache(store);

    putInCache(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("abc"));

    verifyThat(send(GET(serverUri).header("If-None-Match", "\"1\""))).isCacheHit().hasBody("abc");
  }

  @StoreParameterizedTest
  void ifModifiedSinceWithLastModified(Store store) throws Exception {
    setUpCache(store);

    var date = toUtcDateTime(clock.instant());
    var lastModified = date.minusSeconds(3);
    putInCache(
        new MockResponse()
            .setBody("abc")
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("Date", formatHttpDate(date))
            .setHeader("Last-Modified", formatHttpDate(lastModified)));

    verifyThat(
            send(
                GET(serverUri)
                    .header("If-Modified-Since", formatHttpDate(lastModified.plusSeconds(2)))))
        .isExternallyConditionalCacheHit()
        .hasBody("");
    verifyThat(
            send(
                GET(serverUri)
                    .header("If-Modified-Since", formatHttpDate(lastModified.plusSeconds(1)))))
        .isExternallyConditionalCacheHit()
        .hasBody("");
    verifyThat(send(GET(serverUri).header("If-Modified-Since", formatHttpDate(lastModified))))
        .isExternallyConditionalCacheHit()
        .hasBody("");

    verifyThat(
            send(
                GET(serverUri)
                    .header("If-Modified-Since", formatHttpDate(lastModified.minusSeconds(1)))))
        .isCacheHit()
        .hasBody("abc");
    verifyThat(
            send(
                GET(serverUri)
                    .header("If-Modified-Since", formatHttpDate(lastModified.minusSeconds(2)))))
        .isCacheHit()
        .hasBody("abc");

    clock.advanceSeconds(2);

    // Preconditions are ignored when the response is stale.
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(send(GET(serverUri).header("If-Modified-Since", formatHttpDate(lastModified))))
        .hasCode(HTTP_OK)
        .isConditionalHit()
        .hasBody("abc");
  }

  @StoreParameterizedTest
  void ifModifiedSinceWithDate(Store store) throws Exception {
    setUpCache(store);

    var date = toUtcDateTime(clock.instant());
    putInCache(
        new MockResponse()
            .setBody("abc")
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("Date", formatHttpDate(date)));

    verifyThat(
            send(GET(serverUri).header("If-Modified-Since", formatHttpDate(date.plusSeconds(2)))))
        .isExternallyConditionalCacheHit()
        .hasBody("");
    verifyThat(
            send(GET(serverUri).header("If-Modified-Since", formatHttpDate(date.plusSeconds(1)))))
        .isExternallyConditionalCacheHit()
        .hasBody("");
    verifyThat(send(GET(serverUri).header("If-Modified-Since", formatHttpDate(date))))
        .isExternallyConditionalCacheHit()
        .hasBody("");

    verifyThat(
            send(GET(serverUri).header("If-Modified-Since", formatHttpDate(date.minusSeconds(1)))))
        .isCacheHit()
        .hasBody("abc");
    verifyThat(
            send(GET(serverUri).header("If-Modified-Since", formatHttpDate(date.minusSeconds(2)))))
        .isCacheHit()
        .hasBody("abc");

    clock.advanceSeconds(2);

    // Preconditions are ignored when the response is stale.
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(send(GET(serverUri).header("If-Modified-Since", formatHttpDate(date))))
        .hasCode(HTTP_OK)
        .isConditionalHit()
        .hasBody("abc");
  }

  @StoreParameterizedTest
  void ifModifiedSinceWithTimeResponseReceived(Store store) throws Exception {
    setUpCache(store);

    advanceOnSend = Duration.ofSeconds(1);
    var timeResponseReceived = clock.instant().plus(advanceOnSend);
    putInCache(new MockResponse().setBody("abc").setHeader("Cache-Control", "max-age=2"));

    verifyThat(
            send(
                GET(serverUri)
                    .header(
                        "If-Modified-Since",
                        instantToHttpDateString(timeResponseReceived.plusSeconds(2)))))
        .isExternallyConditionalCacheHit()
        .hasBody("");
    verifyThat(
            send(
                GET(serverUri)
                    .header(
                        "If-Modified-Since",
                        instantToHttpDateString(timeResponseReceived.plusSeconds(1)))))
        .isExternallyConditionalCacheHit()
        .hasBody("");
    verifyThat(
            send(
                GET(serverUri)
                    .header("If-Modified-Since", instantToHttpDateString(timeResponseReceived))))
        .isExternallyConditionalCacheHit()
        .hasBody("");

    verifyThat(
            send(
                GET(serverUri)
                    .header(
                        "If-Modified-Since",
                        instantToHttpDateString(timeResponseReceived.minusSeconds(1)))))
        .isCacheHit()
        .hasBody("abc");
    verifyThat(
            send(
                GET(serverUri)
                    .header(
                        "If-Modified-Since",
                        instantToHttpDateString(timeResponseReceived.minusSeconds(2)))))
        .isCacheHit()
        .hasBody("abc");

    clock.advanceSeconds(3);

    // Preconditions are ignored when the response is stale.
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(
            send(
                GET(serverUri)
                    .header(
                        "If-Modified-Since",
                        instantToHttpDateString(timeResponseReceived.plus(advanceOnSend)))))
        .hasCode(HTTP_OK)
        .isConditionalHit()
        .hasBody("abc");
  }

  @StoreParameterizedTest
  void preconditionPrecedence(Store store) throws Exception {
    setUpCache(store);

    var date = toUtcDateTime(clock.instant());
    var lastModified = date.minusSeconds(3);
    putInCache(
        new MockResponse()
            .setHeader("ETag", "\"1\"")
            .setHeader("date", formatHttpDate(date))
            .setHeader("Last-Modified", formatHttpDate(lastModified))
            .setBody("abc"));

    // If-None-Match takes precedence over If-Modified-Since.
    verifyThat(
            send(
                GET(serverUri)
                    .header("If-None-Match", "\"1\"") // Satisfied.
                    .header("If-Modified-Since", formatHttpDate(lastModified)))) // Unsatisfied.
        .isExternallyConditionalCacheHit()
        .hasBody("");
    verifyThat(
            send(
                GET(serverUri)
                    .header("If-None-Match", "\"2\"") // Satisfied.
                    .header(
                        "If-Modified-Since",
                        formatHttpDate(lastModified.minusSeconds(1))))) // Unsatisfied.
        .isCacheHit()
        .hasBody("abc");
  }

  @StoreParameterizedTest
  void preconditionWithNone200CacheResponse(Store store) throws Exception {
    setUpCache(store);

    var date = toUtcDateTime(clock.instant());
    var lastModified = date.minusSeconds(3);
    putInCache(
        new MockResponse()
            .setResponseCode(HTTP_MOVED_PERM)
            .setHeader("Last-Modified", formatHttpDate(lastModified)));

    // If-Modified-Since isn't evaluated.
    verifyThat(send(GET(serverUri).header("If-Modified-Since", formatHttpDate(lastModified))))
        .isCacheHit();
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
                return new MockResponse().setBody("a").setHeader("Cache-Control", "max-age=1");
              case "b":
                return new MockResponse().setBody("b").setHeader("Cache-Control", "max-age=1");
              default:
                return fail("unexpected path: " + path);
            }
          }
        });

    verifyThat(send(uri1)).isCacheMiss().hasBody("a");
    verifyThat(send(uri1)).isCacheHit().hasBody("a");

    verifyThat(send(uri2)).isCacheMiss().hasBody("b");
    verifyThat(send(uri2)).isCacheHit().hasBody("b");

    assertThat(cache.remove(uri1)).isTrue();
    assertNotStored(uri1);

    assertThat(cache.remove(MutableRequest.GET(uri2))).isTrue();
    assertNotStored(uri2);

    verifyThat(send(uri1)).isCacheMiss().hasBody("a");
    verifyThat(send(uri1)).isCacheHit().hasBody("a");

    verifyThat(send(uri2)).isCacheMiss().hasBody("b");
    verifyThat(send(uri2)).isCacheHit().hasBody("b");

    cache.clear();
    assertNotStored(uri1);
    assertNotStored(uri2);
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
    verifyThat(send(GET(serverUri).header("Accept-Encoding", "gzip"))).isCacheMiss().hasBody("Mew");

    // Removal only succeeds for the request matching the correct response variant

    assertThat(cache.remove(GET(serverUri).header("Accept-Encoding", "deflate"))).isFalse();
    verifyThat(send(GET(serverUri).header("Accept-Encoding", "gzip"))).isCacheHit().hasBody("Mew");

    assertThat(cache.remove(GET(serverUri).header("Accept-Encoding", "gzip"))).isTrue();
    assertNotStored(GET(serverUri).header("Accept-Encoding", "gzip"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"private", "public"})
  @StoreSpec(tested = StoreType.DISK, fileSystem = FileSystemType.SYSTEM)
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
            .setHeader("Last-Modified", instantToHttpDateString(lastModifiedInstant))
            .setHeader("Date", instantToHttpDateString(dateInstant))
            .setBody("Mew"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Mew");
    server.takeRequest(); // Drop first request

    // Retain freshness (heuristic lifetime = 3 seconds, age = 2 seconds)
    clock.advanceSeconds(2);

    verifyThat(send(serverUri)).isCacheHit().hasBody("Mew");

    // Make response stale by 1 second (heuristic lifetime = 3 seconds, age = 4 seconds)
    clock.advanceSeconds(2);

    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(send(serverUri)).isConditionalHit().hasBody("Mew");

    var sentRequest = server.takeRequest();
    assertThat(sentRequest.getHeaders().getInstant("If-Modified-Since"))
        .isEqualTo(lastModifiedInstant);
  }

  @UseHttps // Test SSLSession persistence
  @StoreParameterizedTest
  @StoreSpec(tested = StoreType.DISK)
  void cachePersistence(StoreContext storeContext) throws Exception {
    setUpCache(storeContext.createAndRegisterStore());
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=2").setBody("Eevee"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Eevee");

    cache.close();

    // Retain freshness between sessions
    clock.advanceSeconds(1);

    setUpCache(storeContext.createAndRegisterStore());
    verifyThat(send(serverUri)).isCacheHit().hasBody("Eevee").isCachedWithSsl();

    cache.close();

    // Make response stale between sessions.
    clock.advanceSeconds(1);

    setUpCache(storeContext.createAndRegisterStore());
    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(send(serverUri)).isConditionalHit().hasBody("Eevee").isCachedWithSsl();
  }

  @StoreParameterizedTest
  void cacheSize(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Pikachu"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Pikachu");
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
    assertThatIOException().isThrownBy(() -> send(serverUri));

    server.enqueue(
        new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Jigglypuff"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Jigglypuff");

    // Make response stale by 1 second
    clock.advanceSeconds(2);

    // Attempted revalidation throws & cache update is discarded
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setBody("Jigglypuff")
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
    assertThatIOException().isThrownBy(() -> send(serverUri));

    // Stale cache response is still there
    var request = GET(serverUri).header("Cache-Control", "max-stale=1");
    verifyThat(send(request))
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
    verifyThat(send(serverUri)).isCacheHit().hasBody(body);
  }

  @StoreParameterizedTest
  void errorsWhileWritingDiscardsCaching(Store store) throws Exception {
    var faultyStore = new FaultyStore(store);
    setUpCache(faultyStore);

    // Write failure is ignored & the response completes normally nevertheless.
    faultyStore.allowReads = true;
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Pikachu"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Pikachu");
    assertNotStored(serverUri);

    // Allow the response to be cached
    faultyStore.allowWrites = true;
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Pikachu"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Pikachu");
    verifyThat(send(serverUri)).isCacheHit().hasBody("Pikachu");

    // Make response stale by 1 second
    clock.advanceSeconds(2);

    // Attempted revalidation throws & cache update is discarded
    faultyStore.allowWrites = false;
    server.enqueue(
        new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Charmander"));
    verifyThat(send(serverUri)).isConditionalMiss().hasBody("Charmander");

    // Stale cache response is still there
    var request = GET(serverUri).header("Cache-Control", "max-stale=1");
    verifyThat(send(request))
        .isCacheHit()
        .hasBody("Pikachu")
        .containsHeader("Age", "2")
        .containsHeader("Warning", "110 - \"Response is Stale\"");
  }

  @StoreParameterizedTest
  void errorsWhileReadingArePropagated(Store store) throws Exception {
    var faultyStore = new FaultyStore(store);
    faultyStore.allowWrites = true;
    setUpCache(faultyStore);
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Pikachu"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Pikachu");

    // Read failure is propagated
    assertThatThrownBy(() -> send(serverUri)).isInstanceOf(TestException.class);
  }

  @StoreParameterizedTest
  void uriIterator(Store store) throws Exception {
    setUpCache(store);
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("a"));
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("b"));
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("c"));
    verifyThat(send(serverUri.resolve("/a"))).isCacheMiss().hasBody("a");
    verifyThat(send(serverUri.resolve("/b"))).isCacheMiss().hasBody("b");
    verifyThat(send(serverUri.resolve("/c"))).isCacheMiss().hasBody("c");

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
    verifyThat(send(serverUri.resolve("/a"))).isCacheMiss().hasBody("a");
    verifyThat(send(serverUri.resolve("/b"))).isCacheMiss().hasBody("b");
    verifyThat(send(serverUri.resolve("/c"))).isCacheMiss().hasBody("c");

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

    assertNotStored(serverUri.resolve("/a"));
    assertNotStored(serverUri.resolve("/c"));
    verifyThat(send(serverUri.resolve("/b"))).isCacheHit().hasBody("b");
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
    verifyThat(send(hitUri)).isCacheMiss();

    // requestCount = 2, hitCount = 1
    verifyThat(send(hitUri)).isCacheHit();

    // requestCount = 3, missCount = 2, networkUseCount = 2
    verifyThat(send(missUri)).isCacheMiss();

    // requestCount = 13, missCount = 12, networkUseCount = 12
    for (int i = 0; i < 10; i++) {
      verifyThat(send(missUri)).isCacheMiss();
    }

    assertThat(cache.remove(hitUri)).isTrue();

    // requestCount = 14, missCount = 13, networkUseCount = 13
    verifyThat(send(hitUri)).isCacheMiss();

    // requestCount = 24, hitCount = 11
    for (int i = 0; i < 10; i++) {
      verifyThat(send(hitUri)).isCacheHit();
    }

    // requestCount = 25, missCount = 14 (no network)
    verifyThat(send(GET(missUri).header("Cache-Control", "only-if-cached")))
        .isCacheUnsatisfaction();

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
    verifyThat(send(aUri)).isCacheMiss();

    // a.requestCount = 2, a.hitCount = 1
    verifyThat(send(aUri)).isCacheHit();

    // a.requestCount = 3, a.missCount = 2, a.networkUseCount = 2
    verifyThat(send(GET(aUri).header("Cache-Control", "no-cache"))).isConditionalMiss();

    // a.requestCount = 4, a.hitCount = 2
    verifyThat(send(aUri)).isCacheHit();

    assertThat(cache.remove(aUri)).isTrue();

    // a.requestCount = 5, a.missCount = 3, a.networkUseCount = 2 (network isn't accessed)
    verifyThat(send(GET(aUri).header("Cache-Control", "only-if-cached"))).isCacheUnsatisfaction();

    // b.requestCount = 1, b.missCount = 1, b.networkUseCount = 1
    verifyThat(send(bUri)).isCacheMiss();

    // b.requestCount = 6, b.missCount = 6, b.networkUseCount = 6
    for (int i = 0; i < 5; i++) {
      verifyThat(send(GET(bUri).header("Cache-Control", "no-cache"))).isConditionalMiss();
    }

    // b.requestCount = 7, b.hitCount = 1
    verifyThat(send(bUri)).isCacheHit();

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
    var faultyStore = new FaultyStore(store);
    faultyStore.allowReads = true;
    faultyStore.allowWrites = true;
    setUpCache(faultyStore, StatsRecorder.createConcurrentPerUriRecorder());
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest recordedRequest) {
            return new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Pikachu");
          }
        });

    // writeSuccessCount = 1, a.writeSuccessCount = 1
    verifyThat(send(serverUri.resolve("/a"))).isCacheMiss();

    assertThat(cache.remove(serverUri.resolve("/a"))).isTrue();

    // writeSuccessCount = 2, a.writeSuccessCount = 2
    verifyThat(send(serverUri.resolve("/a"))).isCacheMiss();

    // writeSuccessCount = 3, b.writeSuccessCount = 1
    verifyThat(send(serverUri.resolve("/b"))).isCacheMiss();

    faultyStore.allowWrites = false;

    assertThat(cache.remove(serverUri.resolve("/b"))).isTrue();

    // writeFailureCount = 1, b.writeFailureCount = 1
    verifyThat(send(serverUri.resolve("/b"))).isCacheMiss();

    // writeFailureCount = 2, c.writeFailureCount = 1
    verifyThat(send(serverUri.resolve("/c"))).isCacheMiss();

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
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Pikachu"));
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Pikachu");
    verifyThat(send(serverUri)).isCacheHit().hasBody("Pikachu");
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
    verifyThat(send(serverUri)).isCacheMiss().hasBody("Will Smith");
    verifyThat(send(serverUri))
        .isCacheHit()
        .hasBody("Will Smith")
        .doesNotContainHeader("Content-Encoding")
        .doesNotContainHeader("Content-Length");
  }

  @StoreParameterizedTest
  void requestResponseListener(Store store) throws Exception {
    var listener = new RecordingListener(EventCategory.REQUEST_RESPONSE);
    setUpCache(store, null, listener);

    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Pikachu"));
    var request = GET(serverUri).tag(Integer.class, 1);
    send(request);
    listener.assertNext(OnRequest.class, request);
    listener
        .assertNext(OnNetworkUse.class, request)
        .extracting(event -> event.cacheResponse)
        .isNull();
    listener
        .assertNext(OnResponse.class, request)
        .satisfies(event -> verifyThat(event.response).isCacheMiss());

    send(request);
    listener.assertNext(OnRequest.class, request);
    listener
        .assertNext(OnResponse.class, request)
        .satisfies(event -> verifyThat(event.response).isCacheHit());

    // Make response stale
    clock.advanceSeconds(2);

    server.enqueue(new MockResponse().setResponseCode(HTTP_NOT_MODIFIED));
    send(request);
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

    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Eevee"));
    send(request);
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

    send(request.header("Cache-Control", "only-if-cached"));
    listener.assertNext(OnRequest.class, request);
    listener
        .assertNext(OnResponse.class, request)
        .satisfies(event -> verifyThat(event.response).isCacheUnsatisfaction());
  }

  @StoreParameterizedTest
  void readWriteListener(Store store) throws Exception {
    var listener = new RecordingListener(EventCategory.READ_WRITE);
    var faultyStore = new FaultyStore(store);
    setUpCache(faultyStore, null, listener);

    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Pikachu"));

    var request = GET(serverUri).tag(Integer.class, 1);
    send(request);
    listener
        .assertNext(OnWriteFailure.class, request)
        .extracting(
            event -> Utils.getDeepCompletionCause(event.exception)) // Can be a CompletionException
        .isInstanceOf(TestException.class);

    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Pikachu"));

    faultyStore.allowWrites = true;
    send(request);
    listener.assertNext(OnWriteSuccess.class, request);

    assertThatExceptionOfType(TestException.class).isThrownBy(() -> send(request));
    listener
        .assertNext(OnReadFailure.class, request)
        .extracting(
            event -> Utils.getDeepCompletionCause(event.exception)) // Can be a CompletionException
        .isInstanceOf(TestException.class);

    faultyStore.allowReads = true;
    send(request);
    listener.assertNext(OnReadSuccess.class, request);
  }

  static final class RecordingListener implements Listener {
    private static final int TIMEOUT_SECONDS = 2;

    final EventCategory toRecord;
    final BlockingQueue<Event> events = new LinkedBlockingQueue<>();

    enum EventCategory {
      READ_WRITE,
      REQUEST_RESPONSE
    }

    RecordingListener(EventCategory toRecord) {
      this.toRecord = toRecord;
    }

    Event pollNext() {
      try {
        var event = events.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(event)
            .withFailMessage(() -> "expected an event within " + TIMEOUT_SECONDS + " seconds")
            .isNotNull();
        return event;
      } catch (InterruptedException e) {
        return fail("unexpected exception", e);
      }
    }

    <T extends Event> ObjectAssert<T> assertNext(Class<T> expected, TaggableRequest request) {
      return assertThat(pollNext())
          .asInstanceOf(InstanceOfAssertFactories.type(expected))
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
      if (toRecord == EventCategory.REQUEST_RESPONSE) {
        events.add(new OnRequest(request));
      }
    }

    @Override
    public void onNetworkUse(HttpRequest request, @Nullable TrackedResponse<?> cacheResponse) {
      if (toRecord == EventCategory.REQUEST_RESPONSE) {
        events.add(new OnNetworkUse(request, cacheResponse));
      }
    }

    @Override
    public void onResponse(HttpRequest request, CacheAwareResponse<?> response) {
      if (toRecord == EventCategory.REQUEST_RESPONSE) {
        events.add(new OnResponse(request, response));
      }
    }

    @Override
    public void onReadSuccess(HttpRequest request) {
      if (toRecord == EventCategory.READ_WRITE) {
        events.add(new OnReadSuccess(request));
      }
    }

    @Override
    public void onReadFailure(HttpRequest request, Throwable exception) {
      if (toRecord == EventCategory.READ_WRITE) {
        events.add(new OnReadFailure(request, exception));
      }
    }

    @Override
    public void onWriteSuccess(HttpRequest request) {
      if (toRecord == EventCategory.READ_WRITE) {
        events.add(new OnWriteSuccess(request));
      }
    }

    @Override
    public void onWriteFailure(HttpRequest request, Throwable exception) {
      if (toRecord == EventCategory.READ_WRITE) {
        events.add(new OnWriteFailure(request, exception));
      }
    }

    static class Event {
      final HttpRequest request;

      Event(HttpRequest request) {
        this.request = requireNonNull(request);
      }

      String toStringWithStackTrace(Throwable exception) {
        var writer = new StringWriter();
        exception.printStackTrace(new PrintWriter(writer));
        return getClass().getName()
            + "@"
            + Integer.toHexString(hashCode())
            + "{ exceptionStackTrack = \""
            + writer
            + "\"}";
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
        this.response = requireNonNull(response);
      }
    }

    static final class OnReadSuccess extends Event {
      OnReadSuccess(HttpRequest request) {
        super(request);
      }
    }

    static final class OnReadFailure extends Event {
      final Throwable exception;

      OnReadFailure(HttpRequest request, Throwable exception) {
        super(request);
        this.exception = requireNonNull(exception);
      }

      @Override
      public String toString() {
        return toStringWithStackTrace(exception);
      }
    }

    static final class OnWriteSuccess extends Event {
      OnWriteSuccess(HttpRequest request) {
        super(request);
      }
    }

    static final class OnWriteFailure extends Event {
      final Throwable exception;

      OnWriteFailure(HttpRequest request, Throwable exception) {
        super(request);
        this.exception = requireNonNull(exception);
      }

      @Override
      public String toString() {
        return toStringWithStackTrace(exception);
      }
    }
  }

  private void putInCache(MockResponse response) throws IOException, InterruptedException {
    if (response.getBody() == null) {
      response.setBody("");
    }
    server.enqueue(response);
    verifyThat(send(serverUri)).isCacheMiss().hasBody(response.getBody().readString(UTF_8));
    verifyThat(send(serverUri)).isCacheHit().hasBody(response.getBody().readString(UTF_8));
  }

  /**
   * Ensures requests to serverUri result in a cache hit, retrying if necessary in case a stale
   * response is being updated in background.
   */
  private HttpResponse<String> awaitCacheHit() {
    var request = GET(serverUri).header("Cache-Control", "max-stale=0, only-if-cached");
    return await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () -> send(request),
            response -> ((CacheAwareResponse<String>) response).cacheStatus() == CacheStatus.HIT);
  }

  private void assertNotStored(URI uri) throws Exception {
    assertNotStored(GET(uri));
  }

  private void assertNotStored(MutableRequest request) throws Exception {
    try (var viewer = cache.store().view(HttpCache.toStoreKey(request)).orElse(null)) {
      assertThat(viewer).isNull();
    }

    var cacheControl = CacheControl.newBuilder().onlyIfCached().anyMaxStale().build();
    verifyThat(send(request.cacheControl(cacheControl))).isCacheUnsatisfaction();

    var dispatcher = server.getDispatcher();
    boolean prevFailOnUnavailableResponses = failOnUnavailableResponses;
    failOnUnavailableResponses = false;
    try {
      server.setDispatcher(new QueueDispatcher());
      ((QueueDispatcher) server.getDispatcher())
          .setFailFast(new MockResponse().setResponseCode(HTTP_UNAVAILABLE));
      verifyThat(send(request.copy().removeHeader("Cache-Control")))
          .hasCode(HTTP_UNAVAILABLE)
          .isCacheMiss();
    } finally {
      failOnUnavailableResponses = prevFailOnUnavailableResponses;
      server.setDispatcher(dispatcher);
    }
  }

  private static final class FaultyStore extends ForwardingStore {
    volatile boolean allowReads = false;
    volatile boolean allowWrites = false;

    FaultyStore(Store delegate) {
      super(delegate);
    }

    @Override
    public Optional<Viewer> view(String key) throws IOException, InterruptedException {
      return super.view(key).map(FaultyViewer::new);
    }

    @Override
    public Optional<Editor> edit(String key) throws IOException, InterruptedException {
      return super.edit(key).map(FaultyEditor::new);
    }

    private final class FaultyEditor extends ForwardingEditor {
      private final AtomicInteger failedWrites = new AtomicInteger();
      private volatile boolean committed;

      FaultyEditor(Editor delegate) {
        super(delegate);
      }

      @Override
      public EntryWriter writer() {
        var delegate = super.writer();
        return src -> {
          // To simulate delays, fire an actual write on delegate even if writing is prohibited.
          int written = delegate.write(src);
          if (!allowWrites) {
            failedWrites.incrementAndGet();
            throw new TestException();
          }
          return written;
        };
      }

      @Override
      public void commit(ByteBuffer metadata) throws IOException {
        committed = true;
        super.commit(metadata);
      }

      @Override
      public void close() {
        super.close();
        if (committed && failedWrites.get() > 0) {
          fail("edit is committed despite prohibited writes");
        }
      }
    }

    private final class FaultyViewer extends ForwardingViewer {
      FaultyViewer(Viewer delegate) {
        super(delegate);
      }

      @Override
      public EntryReader newReader() {
        var delegate = super.newReader();
        return dst -> {
          // To simulate delays, fire an actual read on delegate even if reading is prohibited.
          int read = delegate.read(dst);
          if (!allowReads) {
            throw new TestException();
          }
          return read;
        };
      }

      @Override
      public Optional<Editor> edit() throws IOException, InterruptedException {
        return super.edit().map(FaultyEditor::new);
      }
    }
  }
}
