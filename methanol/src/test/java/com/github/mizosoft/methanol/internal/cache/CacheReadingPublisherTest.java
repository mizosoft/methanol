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

package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.internal.cache.StoreTesting.view;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.write;
import static com.github.mizosoft.methanol.testing.TestUtils.awaitUninterruptibly;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.TestSubscriber;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension.ExecutorParameterizedTest;
import com.github.mizosoft.methanol.testing.junit.StoreExtension;
import com.github.mizosoft.methanol.testing.junit.StoreSpec;
import com.github.mizosoft.methanol.testing.junit.StoreSpec.FileSystemType;
import com.github.mizosoft.methanol.testing.junit.StoreSpec.StoreType;
import java.io.IOException;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({ExecutorExtension.class, StoreExtension.class})
class CacheReadingPublisherTest {
  static {
    Awaitility.setDefaultTimeout(Duration.ofSeconds(30));
  }

  @ExecutorParameterizedTest
  @StoreSpec(store = StoreType.MEMORY, fileSystem = FileSystemType.NONE)
  void cacheStringInMemory(Executor executor, Store store) throws IOException {
    testCachingAsString(executor, store);
  }

  @ExecutorParameterizedTest
  @StoreSpec(store = StoreType.DISK, fileSystem = FileSystemType.SYSTEM)
  void cacheStringOnDisk(Executor executor, Store store) throws IOException {
    testCachingAsString(executor, store);
  }

  @ExecutorParameterizedTest
  @StoreSpec(store = StoreType.REDIS, fileSystem = FileSystemType.NONE)
  void cacheStringOnRedis(Executor executor, Store store) throws IOException {
    testCachingAsString(executor, store);
  }

  private void testCachingAsString(Executor executor, Store store) throws IOException {
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
          public CompletableFuture<Integer> readAsync(ByteBuffer dst) {
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

  /** No new reads should be scheduled after the subscription is cancelled. */
  @ExecutorParameterizedTest
  void cancelSubscriptionWhileReadIsPending(Executor executor) {
    var firstReadLatch = new CountDownLatch(1);
    var readAsyncFuture = new CompletableFuture<Integer>();
    var viewer =
        new TestViewer() {
          final AtomicInteger readAsyncCalls = new AtomicInteger();

          @Override
          public CompletableFuture<Integer> readAsync(ByteBuffer dst) {
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

    // Trigger CacheReadingPublisher's read completion callback.
    readAsyncFuture.complete(null);

    // No further reads are scheduled.
    assertThat(viewer.readAsyncCalls.get()).isEqualTo(1);

    // The subscriber receives no signals.
    assertThat(subscriber.nextCount).isZero();
    assertThat(subscriber.completionCount).isZero();
    assertThat(subscriber.errorCount).isZero();
  }

  @ExecutorParameterizedTest
  void completionWithoutDemandOnEmptyViewer(Executor executor) {
    var emptyViewer =
        new TestViewer() {
          @Override
          public CompletableFuture<Integer> readAsync(ByteBuffer dst) {
            return CompletableFuture.completedFuture(-1);
          }
        };
    var publisher = new CacheReadingPublisher(emptyViewer, executor);
    var subscriber = new TestSubscriber<List<ByteBuffer>>();
    subscriber.request = 0L; // Request nothing.
    publisher.subscribe(subscriber);
    subscriber.awaitComplete();
    assertThat(subscriber.nextCount).isEqualTo(0);
  }

  @Test
  void publisherIsUnicast() {
    var emptyViewer =
        new TestViewer() {
          @Override
          public CompletableFuture<Integer> readAsync(ByteBuffer dst) {
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

    abstract CompletableFuture<Integer> readAsync(ByteBuffer dst);

    @Override
    public Store.EntryReader newReader() {
      return this::readAsync;
    }

    @Override
    public CompletableFuture<Optional<Editor>> editAsync() {
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
