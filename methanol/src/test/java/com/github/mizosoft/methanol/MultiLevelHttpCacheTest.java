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

import static com.github.mizosoft.methanol.internal.cache.HttpDates.toHttpDateString;
import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;

import com.github.mizosoft.methanol.internal.cache.InternalStorageExtension;
import com.github.mizosoft.methanol.testing.MockWebServerExtension;
import com.github.mizosoft.methanol.testing.store.StoreConfig;
import com.github.mizosoft.methanol.testing.store.StoreConfig.StoreType;
import com.github.mizosoft.methanol.testing.store.StoreContext;
import java.io.IOException;
import java.util.List;
import mockwebserver3.MockResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockWebServerExtension.class)
class MultiLevelHttpCacheTest extends AbstractHttpCacheTest {
  private CacheSetup memoryCacheSetup;
  private CacheSetup diskCacheSetup;

  @BeforeEach
  void setUp() throws IOException {
    memoryCacheSetup = createCacheSetup(StoreType.MEMORY);
    diskCacheSetup = createCacheSetup(StoreType.DISK);
    client =
        clientBuilder.cacheChain(List.of(memoryCacheSetup.cache, diskCacheSetup.cache)).build();
  }

  private CacheSetup createCacheSetup(StoreType storeType) throws IOException {
    var storeContext = StoreContext.from(StoreConfig.createDefault(storeType));
    var cache =
        HttpCache.newBuilder()
            .executor(executor)
            .cacheOn(
                InternalStorageExtension.singleton(
                    new EditAwaitableStore(storeContext.createAndRegisterStore(), editAwaiter)))
            .clock(clock)
            .build();
    return new CacheSetup(storeContext, cache);
  }

  @AfterEach
  void tearDown() throws Exception {
    memoryCacheSetup.storeContext.close();
    diskCacheSetup.storeContext.close();
  }

  @Test
  void cacheHit() throws Exception {
    server.enqueue(new MockResponse().setHeader("Cache-Control", "max-age=1").setBody("Pikachu"));
    verifyThat(send(client)) // Memory cache response.
        .isCacheMiss()
        .hasBody("Pikachu")
        .networkResponse() // Disk cache response.
        .isCacheMiss()
        .hasNoBody() // The body is only present in the top-level response.
        .networkResponse() // Actual network response.
        .hasNoBody();
    verifyThat(send(client)).isCacheHit().hasBody("Pikachu");

    verifyThat(send(memoryCacheSetup.client)).isCacheHit().hasBody("Pikachu");
    verifyThat(send(diskCacheSetup.client)).isCacheHit().hasBody("Pikachu");
  }

  @Test
  void conditionalCacheHit() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("ETag", "\"1\"")
            .setHeader("X-Version", "1")
            .setBody("Pikachu"));
    verifyThat(send(client)).isCacheMiss().hasBody("Pikachu");

    clock.advanceSeconds(2);

    server.enqueue(
        new MockResponse().setHeader("X-Version", "2").setResponseCode(HTTP_NOT_MODIFIED));
    verifyThat(send())
        .isConditionalMiss()
        .containsHeader("X-Version", "2")
        .hasBody("Pikachu")
        .networkResponse()
        .isConditionalHit()
        .containsHeader("X-Version", "2")
        .containsRequestHeader("If-None-Match", "\"1\"")
        .hasNoBody()
        .networkResponse()
        .containsHeader("X-Version", "2")
        .containsRequestHeader("If-None-Match", "\"1\"")
        .hasNoBody();
    verifyThat(send(client)).isCacheHit().containsHeader("X-Version", "2").hasBody("Pikachu");

    verifyThat(send(memoryCacheSetup.client))
        .isCacheHit()
        .containsHeader("X-Version", "2")
        .hasBody("Pikachu");
    verifyThat(send(diskCacheSetup.client))
        .isCacheHit()
        .containsHeader("X-Version", "2")
        .hasBody("Pikachu");
  }

  @Test
  void conditionalCacheMiss() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("ETag", "\"1\"")
            .setBody("Pikachu"));
    verifyThat(send(client)).isCacheMiss().hasBody("Pikachu");

    clock.advanceSeconds(2);

    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("ETag", "\"2\"")
            .setBody("Mew"));
    verifyThat(send())
        .isConditionalMiss()
        .containsHeader("ETag", "\"2\"")
        .hasBody("Mew")
        .networkResponse()
        .isConditionalMiss()
        .containsHeader("ETag", "\"2\"")
        .containsRequestHeader("If-None-Match", "\"1\"")
        .hasNoBody()
        .networkResponse()
        .containsHeader("ETag", "\"2\"")
        .containsRequestHeader("If-None-Match", "\"1\"")
        .hasNoBody();
    verifyThat(send(client)).isCacheHit().containsHeader("ETag", "\"2\"").hasBody("Mew");

    verifyThat(send(memoryCacheSetup.client))
        .isCacheHit()
        .containsHeader("ETag", "\"2\"")
        .hasBody("Mew");
    verifyThat(send(diskCacheSetup.client))
        .isCacheHit()
        .containsHeader("ETag", "\"2\"")
        .hasBody("Mew");
  }

  @Test
  void conditionalCacheHitServedBySecondCacheWithEtag() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("ETag", "\"1\"")
            .setHeader("X-Version", "1")
            .setBody("Pikachu"));
    verifyThat(send(client)).isCacheMiss().hasBody("Pikachu");

    clock.advanceSeconds(2);

    // Update the disk cache.
    server.enqueue(
        new MockResponse().setResponseCode(HTTP_NOT_MODIFIED).setHeader("X-Version", "2"));
    verifyThat(send(diskCacheSetup.client))
        .isConditionalHit()
        .containsHeader("X-Version", "2")
        .hasBody("Pikachu");

    // A conditional hit is served from the disk cache (no network).
    verifyThat(send())
        .isConditionalHit()
        .containsHeader("X-Version", "2")
        .hasBody("Pikachu")
        .networkResponse()
        .isExternallyConditionalCacheHit()
        .containsRequestHeader("If-None-Match", "\"1\"")
        .hasNoBody();
    verifyThat(send(client)).isCacheHit().containsHeader("X-Version", "2").hasBody("Pikachu");
  }

  @Test
  void conditionalCacheHitServedBySecondCacheWithIfModifiedSince() throws Exception {
    var lastModified = toUtcDateTime(clock.instant()).minusSeconds(1);
    server.enqueue(
        new MockResponse()
            .setHeader("Cache-Control", "max-age=1")
            .setHeader("Last-Modified", toHttpDateString(lastModified))
            .setHeader("X-Version", "1")
            .setBody("Pikachu"));
    verifyThat(send(client)).isCacheMiss().hasBody("Pikachu");

    clock.advanceSeconds(2);

    // Update the disk cache.
    server.enqueue(
        new MockResponse().setResponseCode(HTTP_NOT_MODIFIED).setHeader("X-Version", "2"));
    verifyThat(send(diskCacheSetup.client))
        .isConditionalHit()
        .containsHeader("X-Version", "2")
        .hasBody("Pikachu");

    // A conditional hit is served from the disk cache (no network).
    verifyThat(send())
        .isConditionalHit()
        .containsHeader("X-Version", "2")
        .hasBody("Pikachu")
        .networkResponse()
        .isExternallyConditionalCacheHit()
        .containsRequestHeader("If-Modified-Since", toHttpDateString(lastModified))
        .hasNoBody();
    verifyThat(send(client)).isCacheHit().containsHeader("X-Version", "2").hasBody("Pikachu");
  }

  private static final class CacheSetup {
    final StoreContext storeContext;
    final HttpCache cache;
    final Methanol client;

    CacheSetup(StoreContext storeContext, HttpCache cache) {
      this.storeContext = storeContext;
      this.cache = cache;
      this.client = Methanol.newBuilder().cache(cache).build();
    }
  }
}
