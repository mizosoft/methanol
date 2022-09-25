/*
 * Copyright (c) 2021 Moataz Abdelnasser
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
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.writeEntry;
import static com.github.mizosoft.methanol.testing.StoreConfig.FileSystemType.SYSTEM;
import static com.github.mizosoft.methanol.testing.StoreConfig.StoreType.DISK;
import static com.github.mizosoft.methanol.testing.StoreConfig.StoreType.MEMORY;
import static com.github.mizosoft.methanol.testing.TestUtils.awaitUninterruptibly;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorParameterizedTest;
import com.github.mizosoft.methanol.testing.StoreConfig;
import com.github.mizosoft.methanol.testing.StoreExtension;
import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.TestSubscriber;
import java.io.IOException;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({ExecutorExtension.class, StoreExtension.class})
class CacheReadingPublisherTest {
  static {
    Awaitility.setDefaultTimeout(Duration.ofSeconds(30));
  }

  @ExecutorParameterizedTest
  @StoreConfig(store = MEMORY)
  void cacheStringInMemory(Executor executor, Store store) throws IOException {
    testCachingAString(executor, store);
  }

  @ExecutorParameterizedTest
  @StoreConfig(store = DISK, fileSystem = SYSTEM)
  void cacheStringInDisk(Executor executor, Store store) throws IOException {
    testCachingAString(executor, store);
  }

  private void testCachingAString(Executor executor, Store store) throws IOException {
    writeEntry(store, "e1", "", "Cache me please!");

    var publisher = new CacheReadingPublisher(view(store, "e1"), executor);
    var subscriber = BodySubscribers.ofString(UTF_8);
    publisher.subscribe(subscriber);
    assertThat(subscriber.getBody())
        .succeedsWithin(Duration.ofSeconds(20))
        .isEqualTo("Cache me please!");
  }

  @ExecutorParameterizedTest
  void failureInAsyncRead(Executor executor) {
    var failingViewer = new TestViewer() {
      @Override
      public CompletableFuture<Integer> readAsync(long position, ByteBuffer dst) {
        var future = new CompletableFuture<Integer>();
        CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS)
            .execute(() -> future.completeExceptionally(new TestException()));
        return future;
      }
    };

    var publisher = new CacheReadingPublisher(failingViewer, executor);
    var subscriber = new TestSubscriber<List<ByteBuffer>>();
    publisher.subscribe(subscriber);

    subscriber.awaitComplete();
    assertThat(subscriber.errorCount).isOne();
    assertThat(subscriber.errorCount).isOne();
    assertThat(subscriber.lastError).isInstanceOf(TestException.class);
  }

  /** No new reads should be scheduled when the subscription is cancelled. */
  @ExecutorParameterizedTest
  void cancelSubscriptionWhileReadIsPending(Executor executor) throws InterruptedException {
    var firstReadLatch = new CountDownLatch(1);
    var readAsyncFuture = new CompletableFuture<Integer>();
    var viewer = new TestViewer() {
      final AtomicInteger readAsyncCalls = new AtomicInteger();

      @Override
      public CompletableFuture<Integer> readAsync(long position, ByteBuffer dst) {
        readAsyncCalls.incrementAndGet();
        firstReadLatch.countDown();
        return readAsyncFuture;
      }
    };

    var publisher = new CacheReadingPublisher(viewer, executor);
    var subscriber = new TestSubscriber<List<ByteBuffer>>();
    publisher.subscribe(subscriber);
    awaitUninterruptibly(firstReadLatch);
    subscriber.awaitOnSubscribe();
    subscriber.subscription.cancel();

    // Trigger CacheReadingPublisher's read completion callback
    readAsyncFuture.complete(null);

    // No further reads are scheduled
    assertThat(viewer.readAsyncCalls.get()).isEqualTo(1);

    // The subscriber receives no signals
    assertThat(subscriber.nextCount).isZero();
    assertThat(subscriber.completionCount).isZero();
    assertThat(subscriber.errorCount).isZero();
  }

  @ExecutorParameterizedTest
  void completionWithoutDemandOnEmptyViewer(Executor executor) {
    var emptyViewer = new TestViewer() {
      @Override
      public CompletableFuture<Integer> readAsync(long position, ByteBuffer dst) {
        return CompletableFuture.completedFuture(-1);
      }
    };

    var publisher = new CacheReadingPublisher(emptyViewer, executor);
    var subscriber = new TestSubscriber<List<ByteBuffer>>();
    subscriber.request = 0L; // Request nothing
    publisher.subscribe(subscriber);
    subscriber.awaitComplete();
    assertThat(subscriber.nextCount).isEqualTo(0);
  }

  @Test
  void publisherIsUnicast() {
    var emptyViewer = new TestViewer() {
      @Override
      public CompletableFuture<Integer> readAsync(long position, ByteBuffer dst) {
        return CompletableFuture.completedFuture(-1);
      }
    };

    var publisher = new CacheReadingPublisher(emptyViewer, FlowSupport.SYNC_EXECUTOR);
    publisher.subscribe(new TestSubscriber<>());

    var secondSubscriber = new TestSubscriber<>();
    publisher.subscribe(secondSubscriber);
    secondSubscriber.awaitError();
    assertThat(secondSubscriber.lastError).isInstanceOf(IllegalStateException.class);
  }

  private abstract static class TestViewer implements Viewer {
    TestViewer() {}

    @Override
    public String key() {
      throw new AssertionError();
    }

    @Override
    public ByteBuffer metadata() {
      throw new AssertionError();
    }

    @Override
    public long dataSize() {
      throw new AssertionError();
    }

    @Override
    public long entrySize() {
      throw new AssertionError();
    }

    @Override
    public Store.@Nullable Editor edit() {
      throw new AssertionError();
    }

    @Override
    public boolean removeEntry() {
      throw new AssertionError();
    }

    @Override
    public void close() {}
  }
}
