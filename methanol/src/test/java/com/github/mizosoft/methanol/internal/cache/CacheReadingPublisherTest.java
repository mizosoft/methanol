/*
 * Copyright (c) 2024 Moataz Abdelnasser
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
import static com.github.mizosoft.methanol.testing.TestUtils.awaitUnchecked;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.from;

import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.cache.Store.EntryReader;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorParameterizedTest;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorSpec;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.RepeatArguments;
import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.TestSubscriber;
import com.github.mizosoft.methanol.testing.TestUtils;
import com.github.mizosoft.methanol.testing.store.StoreConfig.FileSystemType;
import com.github.mizosoft.methanol.testing.store.StoreConfig.StoreType;
import com.github.mizosoft.methanol.testing.store.StoreExtension;
import com.github.mizosoft.methanol.testing.store.StoreSpec;
import java.io.IOException;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({ExecutorExtension.class, StoreExtension.class})
@RepeatArguments(10)
class CacheReadingPublisherTest {
  @ExecutorParameterizedTest
  @StoreSpec(tested = StoreType.MEMORY, fileSystem = FileSystemType.NONE)
  void readSmallStringFromMemory(Executor executor, Store store) throws Exception {
    testReadingSmallString(store, executor);
  }

  @ExecutorParameterizedTest
  @StoreSpec(tested = StoreType.DISK, fileSystem = FileSystemType.SYSTEM)
  void readSmallStringFromDisk(Executor executor, Store store) throws IOException {
    testReadingSmallString(store, executor);
  }

  @ExecutorParameterizedTest
  @StoreSpec(tested = StoreType.REDIS_STANDALONE, fileSystem = FileSystemType.NONE)
  @EnabledIf("com.github.mizosoft.methanol.testing.store.RedisStandaloneStoreContext#isAvailable")
  void readSmallStringFromRedisStandalone(Executor executor, Store store) throws IOException {
    testReadingSmallString(store, executor);
  }

  @ExecutorParameterizedTest
  @StoreSpec(tested = StoreType.REDIS_CLUSTER, fileSystem = FileSystemType.NONE)
  @EnabledIf("com.github.mizosoft.methanol.testing.store.RedisClusterStoreContext#isAvailable")
  void readSmallStringFromRedisCluster(Executor executor, Store store) throws IOException {
    testReadingSmallString(store, executor);
  }

  @ExecutorParameterizedTest
  @StoreSpec(tested = StoreType.MEMORY, fileSystem = FileSystemType.NONE)
  void readLargeStringFromMemory(Executor executor, Store store) throws IOException {
    testReadingLargeString(store, executor);
  }

  @ExecutorParameterizedTest
  @StoreSpec(tested = StoreType.DISK, fileSystem = FileSystemType.SYSTEM)
  void readLargeStringFromDisk(Executor executor, Store store) throws IOException {
    testReadingLargeString(store, executor);
  }

  @ExecutorParameterizedTest
  @StoreSpec(tested = StoreType.REDIS_STANDALONE, fileSystem = FileSystemType.NONE)
  @EnabledIf("com.github.mizosoft.methanol.testing.store.RedisStandaloneStoreContext#isAvailable")
  void readLargeStringFromRedisStandalone(Executor executor, Store store) throws IOException {
    testReadingLargeString(store, executor);
  }

  @ExecutorParameterizedTest
  @StoreSpec(tested = StoreType.REDIS_CLUSTER, fileSystem = FileSystemType.NONE)
  @EnabledIf("com.github.mizosoft.methanol.testing.store.RedisClusterStoreContext#isAvailable")
  void readLargeStringFromRedisCluster(Executor executor, Store store) throws IOException {
    testReadingLargeString(store, executor);
  }

  private void testReadingSmallString(Store store, Executor executor) throws IOException {
    testReadingString("Cache me please!", store, executor);
  }

  private void testReadingLargeString(Store store, Executor executor) throws IOException {
    testReadingString("Cache me please!".repeat(100_000), store, executor);
  }

  private void testReadingString(String str, Store store, Executor executor) throws IOException {
    write(store, "e1", "", str);

    var publisher = new CacheReadingPublisher(view(store, "e1"), executor);
    var subscriber = BodySubscribers.ofString(UTF_8);
    publisher.subscribe(subscriber);
    assertThat(subscriber.getBody())
        .succeedsWithin(Duration.ofSeconds(TestUtils.TIMEOUT_SECONDS))
        .returns(str.length(), from(String::length))
        .isEqualTo(str);
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
  @ExecutorSpec(ExecutorType.CACHED_POOL)
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
            awaitUnchecked(endReadLatch);
            return -1;
          }
        };
    var publisher = new CacheReadingPublisher(viewer, executor);
    var subscriber = new TestSubscriber<List<ByteBuffer>>();
    publisher.subscribe(subscriber);
    awaitUnchecked(firstReadLatch);
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
    public EntryReader newReader() {
      return (dsts, executor) ->
          CompletableFuture.supplyAsync(
              () -> (long) dsts.stream().mapToInt(TestViewer.this::read).sum(), executor);
    }

    @Override
    public CompletableFuture<Optional<Editor>> edit(Executor executor) {
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
