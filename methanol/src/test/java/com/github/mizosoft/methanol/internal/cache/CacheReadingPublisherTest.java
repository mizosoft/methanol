package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.testing.StoreConfig.FileSystemType.SYSTEM;
import static com.github.mizosoft.methanol.testing.StoreConfig.StoreType.DISK;
import static com.github.mizosoft.methanol.testing.StoreConfig.StoreType.MEMORY;
import static com.github.mizosoft.methanol.testutils.TestUtils.awaitUninterruptibly;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorConfig;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorParameterizedTest;
import com.github.mizosoft.methanol.testing.StoreConfig;
import com.github.mizosoft.methanol.testing.StoreExtension;
import com.github.mizosoft.methanol.testutils.TestException;
import com.github.mizosoft.methanol.testutils.TestSubscriber;
import java.io.IOException;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({ExecutorExtension.class, StoreExtension.class})
class CacheReadingPublisherTest {
  @ExecutorParameterizedTest
  @ExecutorConfig
  @StoreConfig(store = MEMORY)
  void cacheStringInMemory(Executor executor, Store store) throws IOException {
    testCachingAString(executor, store);
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  @StoreConfig(store = DISK, fileSystem = SYSTEM)
  void cacheStringInDisk(Executor executor, Store store) throws IOException {
    testCachingAString(executor, store);
  }

  private void testCachingAString(Executor executor, Store store) throws IOException {
    try (var editor = notNull(store.edit("e1"))) {
      editor.writeAsync(0, UTF_8.encode("Cache me please!")).join();
      editor.commitOnClose();
    }

    var publisher = new CacheReadingPublisher(notNull(store.view("e1")), executor);
    var subscriber = BodySubscribers.ofString(UTF_8);
    publisher.subscribe(subscriber);
    assertEquals("Cache me please!", getBody(subscriber));
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void failureInAsyncRead(Executor executor) {
    var failedViewer = new TestViewer() {
      @Override
      public CompletableFuture<Integer> readAsync(long position, ByteBuffer dst) {
        var future = new CompletableFuture<Integer>();
        CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS)
            .execute(() -> future.completeExceptionally(new TestException()));
        return future;
      }
    };

    var publisher = new CacheReadingPublisher(failedViewer, executor);
    var subscriber = BodySubscribers.ofByteArray();
    publisher.subscribe(subscriber);

    var cause = assertThrows(CompletionException.class, toFuture(subscriber)::join).getCause();
    assertEquals(TestException.class, cause.getClass());
  }

  /** No new reads should be scheduled when the subscription is cancelled. */
  @ExecutorParameterizedTest
  @ExecutorConfig
  void cancelSubscriptionWhileReadIsPending(Executor executor) throws InterruptedException {
    var firstRead = new CountDownLatch(1);
    var subscriptionCancelled = new CountDownLatch(1);
    var viewer = new TestViewer() {
      private final AtomicInteger readAsyncCalls = new AtomicInteger();

      @Override
      public CompletableFuture<Integer> readAsync(long position, ByteBuffer dst) {
        readAsyncCalls.incrementAndGet();
        firstRead.countDown();
        return CompletableFuture.supplyAsync(
            () -> {
              awaitUninterruptibly(subscriptionCancelled);
              int count = dst.remaining();
              dst.position(dst.limit()); // Simulate reading
              return count;
            });
      }
    };

    var publisher = new CacheReadingPublisher(viewer, executor);
    var subscriber = new TestSubscriber<List<ByteBuffer>>();
    publisher.subscribe(BodySubscribers.fromSubscriber(subscriber));
    awaitUninterruptibly(firstRead);
    subscriber.awaitSubscribe();
    subscriber.subscription.cancel();
    subscriptionCancelled.countDown();

    TimeUnit.MILLISECONDS.sleep(100);
    assertEquals(1, viewer.readAsyncCalls.get());
    assertEquals(0, subscriber.nexts);
    assertEquals(0, subscriber.completes);
    assertEquals(0, subscriber.errors);
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
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
    assertEquals(0, subscriber.nexts);
  }

  private static <T> @NonNull T notNull(T value) {
    assertNotNull(value);
    return value;
  }

  private static <T> CompletableFuture<T> toFuture(BodySubscriber<T> s) {
    return s.getBody().toCompletableFuture();
  }

  private static <T> T getBody(BodySubscriber<T> s) {
    return toFuture(s).join();
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
    public void close() {}
  }
}
