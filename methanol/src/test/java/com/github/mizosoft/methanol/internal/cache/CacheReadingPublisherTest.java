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

package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.internal.cache.StoreTesting.view;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.write;
import static com.github.mizosoft.methanol.testing.TestUtils.awaitUninterruptibly;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.TestSubscriber;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension.ExecutorConfig;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension.ExecutorParameterizedTest;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.junit.StoreConfig.FileSystemType;
import com.github.mizosoft.methanol.testing.junit.StoreConfig.StoreType;
import com.github.mizosoft.methanol.testing.junit.StoreExtension;
import com.github.mizosoft.methanol.testing.junit.StoreSpec;
import java.io.IOException;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({ExecutorExtension.class, StoreExtension.class})
class CacheReadingPublisherTest {
  static {
    Awaitility.setDefaultTimeout(Duration.ofSeconds(30));
  }

  @ExecutorParameterizedTest
  @StoreSpec(store = StoreType.MEMORY, fileSystem = FileSystemType.NONE)
  void cacheStringInMemory(Executor executor, Store store)
      throws IOException, InterruptedException {
    testCachingAsString(executor, store);
  }

  @ExecutorParameterizedTest
  @StoreSpec(store = StoreType.DISK, fileSystem = FileSystemType.SYSTEM)
  void cacheStringOnDisk(Executor executor, Store store) throws IOException, InterruptedException {
    testCachingAsString(executor, store);
  }

  @ExecutorParameterizedTest
  @StoreSpec(store = StoreType.REDIS_STANDALONE, fileSystem = FileSystemType.NONE)
  @EnabledIf("com.github.mizosoft.methanol.testing.junit.RedisStandaloneStoreContext#isAvailable")
  void cacheStringOnRedisStandalone(Executor executor, Store store)
      throws IOException, InterruptedException {
    testCachingAsString(executor, store);
  }

  @ExecutorParameterizedTest
  @StoreSpec(store = StoreType.REDIS_CLUSTER, fileSystem = FileSystemType.NONE)
  @EnabledIf("com.github.mizosoft.methanol.testing.junit.RedisClusterStoreContext#isAvailable")
  void cacheStringOnRedisCluster(Executor executor, Store store)
      throws IOException, InterruptedException {
    testCachingAsString(executor, store);
  }

  private void testCachingAsString(Executor executor, Store store)
      throws IOException, InterruptedException {
    write(store, "e1", "", "Cache me please!");

    var publisher = new CacheReadingPublisher(view(store, "e1"), executor);
    var subscriber = BodySubscribers.ofString(UTF_8);
    publisher.subscribe(subscriber);
    assertThat(subscriber.getBody())
        .succeedsWithin(Duration.ofSeconds(20))
        .isEqualTo("Cache me please!");
  }

  @ExecutorParameterizedTest
  void failureInAsyncRead(Executor executor) {
    var failingViewer =
        new TestViewer() {
          @Override
          public int read(ByteBuffer dst) {
            try {
              Thread.sleep(50);
            } catch (InterruptedException e) {
              return fail("unexpected exception", e);
            }
            throw new TestException();
          }
        };
    var publisher = new CacheReadingPublisher(failingViewer, executor);
    var subscriber = new TestSubscriber<List<ByteBuffer>>();
    publisher.subscribe(subscriber);
    subscriber.awaitCompletion();
    assertThat(subscriber.errorCount()).isOne();
    assertThat(subscriber.awaitError())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(TestException.class);
  }

  /** No new reads should be scheduled after the subscription is cancelled. */
  @Test
  @ExecutorConfig(ExecutorType.CACHED_POOL)
  void cancelSubscriptionWhileReadIsPending(Executor executor) {
    var firstReadLatch = new CountDownLatch(1);
    var endReadLatch = new CountDownLatch(1);
    var viewer =
        new TestViewer() {
          final AtomicInteger readAsyncCalls = new AtomicInteger();

          @Override
          public int read(ByteBuffer dst) {
            readAsyncCalls.incrementAndGet();
            firstReadLatch.countDown();
            awaitUninterruptibly(endReadLatch);
            return -1;
          }
        };
    var publisher = new CacheReadingPublisher(viewer, executor);
    var subscriber = new TestSubscriber<List<ByteBuffer>>();
    publisher.subscribe(subscriber);
    awaitUninterruptibly(firstReadLatch);
    subscriber.awaitSubscription().cancel();

    // Trigger CacheReadingPublisher's read completion callback.
    endReadLatch.countDown();

    // No further reads are scheduled.
    assertThat(viewer.readAsyncCalls.get()).isEqualTo(1);

    // The subscriber receives no signals.
    assertThat(subscriber.nextCount()).isZero();
    assertThat(subscriber.completionCount()).isZero();
    assertThat(subscriber.errorCount()).isZero();
  }

  @ExecutorParameterizedTest
  void completionWithoutDemandOnEmptyViewer(Executor executor) {
    var emptyViewer =
        new TestViewer() {
          @Override
          public int read(ByteBuffer dst) {
            return -1;
          }
        };
    var publisher = new CacheReadingPublisher(emptyViewer, executor);
    var subscriber = new TestSubscriber<List<ByteBuffer>>().autoRequest(0);
    publisher.subscribe(subscriber);
    subscriber.awaitCompletion();
    assertThat(subscriber.nextCount()).isEqualTo(0);
  }

  @ExecutorParameterizedTest
  void publisherIsUnicast(Executor executor) {
    var emptyViewer =
        new TestViewer() {
          @Override
          public int read(ByteBuffer dst) {
            return -1;
          }
        };
    var publisher = new CacheReadingPublisher(emptyViewer, executor);
    publisher.subscribe(new TestSubscriber<>());

    var secondSubscriber = new TestSubscriber<>();
    publisher.subscribe(secondSubscriber);
    assertThat(secondSubscriber.awaitError()).isInstanceOf(IllegalStateException.class);
  }

  private abstract static class TestViewer implements Viewer {
    TestViewer() {}

    @Override
    public String key() {
      return fail("unexpected call");
    }

    @Override
    public ByteBuffer metadata() {
      return fail("unexpected call");
    }

    @Override
    public long dataSize() {
      return fail("unexpected call");
    }

    @Override
    public long entrySize() {
      return fail("unexpected call");
    }

    abstract int read(ByteBuffer dst);

    @Override
    public Store.EntryReader newReader() {
      return this::read;
    }

    @Override
    public Optional<Editor> edit() {
      return fail("unexpected call");
    }

    @Override
    public boolean removeEntry() {
      return fail("unexpected call");
    }

    @Override
    public void close() {}
  }
}
