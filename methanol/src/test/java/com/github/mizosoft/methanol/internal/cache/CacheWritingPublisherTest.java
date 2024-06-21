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

import static com.github.mizosoft.methanol.internal.cache.StoreTesting.assertEntryEquals;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.edit;
import static com.github.mizosoft.methanol.testing.TestUtils.EMPTY_BUFFER;
import static com.github.mizosoft.methanol.testing.TestUtils.awaitUninterruptibly;
import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.CacheWritingPublisher.Listener;
import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.cache.Store.EntryWriter;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.testing.BodyCollector;
import com.github.mizosoft.methanol.testing.ByteBufferListIterator;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorParameterizedTest;
import com.github.mizosoft.methanol.testing.Logging;
import com.github.mizosoft.methanol.testing.SubmittablePublisher;
import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.TestSubscriber;
import com.github.mizosoft.methanol.testing.TestUtils;
import com.github.mizosoft.methanol.testing.store.StoreConfig;
import com.github.mizosoft.methanol.testing.store.StoreExtension;
import com.github.mizosoft.methanol.testing.store.StoreSpec;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.io.IOException;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({ExecutorExtension.class, StoreExtension.class})
@Timeout(5)
class CacheWritingPublisherTest {
  static {
    Logging.disable(CacheWritingPublisher.class);
  }

  @ExecutorParameterizedTest
  @StoreSpec(tested = StoreConfig.StoreType.MEMORY, fileSystem = StoreConfig.FileSystemType.NONE)
  void writeSmallStringToMemory(Executor executor, Store store)
      throws IOException, InterruptedException {
    testWritingSmallString(store, executor);
  }

  @ExecutorParameterizedTest
  @StoreSpec(tested = StoreConfig.StoreType.DISK, fileSystem = StoreConfig.FileSystemType.SYSTEM)
  void writeSmallStringToDisk(Executor executor, Store store)
      throws IOException, InterruptedException {
    testWritingSmallString(store, executor);
  }

  @ExecutorParameterizedTest
  @StoreSpec(
      tested = StoreConfig.StoreType.REDIS_STANDALONE,
      fileSystem = StoreConfig.FileSystemType.NONE)
  @EnabledIf("com.github.mizosoft.methanol.testing.store.RedisStandaloneStoreContext#isAvailable")
  void writeSmallStringToRedisStandalone(Executor executor, Store store)
      throws IOException, InterruptedException {
    testWritingSmallString(store, executor);
  }

  @ExecutorParameterizedTest
  @StoreSpec(
      tested = StoreConfig.StoreType.REDIS_CLUSTER,
      fileSystem = StoreConfig.FileSystemType.NONE)
  @EnabledIf("com.github.mizosoft.methanol.testing.store.RedisClusterStoreContext#isAvailable")
  void writeSmallStringToRedisCluster(Executor executor, Store store)
      throws IOException, InterruptedException {
    testWritingSmallString(store, executor);
  }

  @ExecutorParameterizedTest
  @StoreSpec(tested = StoreConfig.StoreType.MEMORY, fileSystem = StoreConfig.FileSystemType.NONE)
  void writeLargeStringToMemory(Executor executor, Store store)
      throws IOException, InterruptedException {
    testWritingLargeString(store, executor);
  }

  @ExecutorParameterizedTest
  @StoreSpec(tested = StoreConfig.StoreType.DISK, fileSystem = StoreConfig.FileSystemType.SYSTEM)
  void writeLargeStringToDisk(Executor executor, Store store)
      throws IOException, InterruptedException {
    testWritingLargeString(store, executor);
  }

  @ExecutorParameterizedTest
  @StoreSpec(
      tested = StoreConfig.StoreType.REDIS_STANDALONE,
      fileSystem = StoreConfig.FileSystemType.NONE)
  @EnabledIf("com.github.mizosoft.methanol.testing.store.RedisStandaloneStoreContext#isAvailable")
  void writeLargeStringToRedisStandalone(Executor executor, Store store)
      throws IOException, InterruptedException {
    testWritingLargeString(store, executor);
  }

  @ExecutorParameterizedTest
  @StoreSpec(
      tested = StoreConfig.StoreType.REDIS_CLUSTER,
      fileSystem = StoreConfig.FileSystemType.NONE)
  @EnabledIf("com.github.mizosoft.methanol.testing.store.RedisClusterStoreContext#isAvailable")
  void writeLargeStringToRedisCluster(Executor executor, Store store)
      throws IOException, InterruptedException {
    testWritingLargeString(store, executor);
  }

  private void testWritingSmallString(Store store, Executor executor)
      throws IOException, InterruptedException {
    testWritingString("Cache me if you can!", store, executor);
  }

  private void testWritingLargeString(Store store, Executor executor)
      throws IOException, InterruptedException {
    testWritingString("Cache me if you can!".repeat(100_000), store, executor);
  }

  private void testWritingString(String str, Store store, Executor executor)
      throws IOException, InterruptedException {
    var listener =
        new CacheWritingPublisher.Listener() {
          private Object result;

          @Override
          public void onWriteSuccess() {
            result = Boolean.TRUE;
          }

          @Override
          public void onWriteFailure(Throwable exception) {
            result = exception;
          }
        };
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher =
        new CacheWritingPublisher(
            upstream,
            edit(store, "e1"),
            UTF_8.encode("abc"),
            executor,
            listener,
            false,
            true); // Make sure the entry is written when downstream completes.
    var subscriber = BodySubscribers.ofString(UTF_8);
    publisher.subscribe(subscriber);
    upstream.submitAll(toResponseBodyIterable(str, Utils.BUFFER_SIZE));
    upstream.close();
    verifyThat(subscriber).succeedsWith(str);
    if (listener.result instanceof Throwable) {
      fail((Throwable) listener.result);
    }
    assertThat(listener.result).isEqualTo(true);
    assertEntryEquals(store, "e1", "abc", str);
  }

  @ExecutorParameterizedTest
  void writeString(Executor executor) {
    var editor = new TestEditor();
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher = new CacheWritingPublisher(upstream, editor, EMPTY_BUFFER, executor);
    var subscriber = new StringSubscriber();
    publisher.subscribe(subscriber);
    upstream.submitAll(toResponseBodyIterable("Cache me if you can!"));
    upstream.close();
    assertThat(subscriber.bodyToString()).isEqualTo("Cache me if you can!");

    editor.awaitCommit();
    assertThat(editor.writtenString()).isEqualTo("Cache me if you can!");
  }

  @Test
  void commitMetadata() {
    var editor = new TestEditor();
    var publisher =
        new CacheWritingPublisher(
            FlowSupport.emptyPublisher(), editor, UTF_8.encode("abc"), FlowSupport.SYNC_EXECUTOR);
    var subscriber = new TestSubscriber<>();
    publisher.subscribe(subscriber);
    subscriber.awaitCompletion();
    editor.awaitCommit();
    assertThat(editor.metadata)
        .isNotNull()
        .extracting(buffer -> UTF_8.decode(buffer).toString(), STRING)
        .isEqualTo("abc");
  }

  @Test
  void subscribeTwice() {
    var publisher =
        new CacheWritingPublisher(
            FlowSupport.emptyPublisher(),
            new TestEditor(),
            EMPTY_BUFFER,
            FlowSupport.SYNC_EXECUTOR);
    publisher.subscribe(new TestSubscriber<>());

    var secondSubscriber = new TestSubscriber<>();
    publisher.subscribe(secondSubscriber);
    secondSubscriber.awaitCompletion();
    assertThat(secondSubscriber.errorCount()).isOne();
    assertThat(secondSubscriber.awaitError()).isInstanceOf(IllegalStateException.class);
  }

  /**
   * The publisher shouldn't propagate cancellation upstream and prefer instead to continue caching
   * the body.
   */
  @ExecutorParameterizedTest
  void cancellationIsNotPropagatedIfWriting(Executor executor) {
    var editor = new TestEditor();
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher = new CacheWritingPublisher(upstream, editor, EMPTY_BUFFER, executor);
    var subscriber = new StringSubscriber();
    publisher.subscribe(subscriber);

    // Cancel before submitting items.
    subscriber.awaitSubscription().cancel();
    upstream.submitAll(toResponseBodyIterable("Cancel me if you can!"));
    upstream.close();

    // Writing completes successfully and cancellation is not propagated.
    editor.awaitCommit();
    assertThat(editor.writtenString()).isEqualTo("Cancel me if you can!");
    assertThat(upstream.firstSubscription().flowInterrupted()).isFalse();

    // Subscriber's cancellation request is satisfied & body flow stops.
    assertThat(subscriber.peekAvailable())
        .withFailMessage(() -> "unexpectedly received: " + subscriber.bodyToString())
        .isEmpty();
  }

  @ExecutorParameterizedTest
  void cancellationIsPropagatedIfNotWriting(Executor executor) {
    var failingEditor =
        new TestEditor() {
          @Override
          int write(ByteBuffer src) {
            try {
              Thread.sleep(50);
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
            throw new TestException();
          }
        };
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher = new CacheWritingPublisher(upstream, failingEditor, EMPTY_BUFFER, executor);
    var subscriber = new TestSubscriber<List<ByteBuffer>>();
    publisher.subscribe(subscriber);
    upstream.submit(List.of(ByteBuffer.allocate(1))); // Trigger write.

    // Wait till the error is handled and failingEditor is closed.
    failingEditor.awaitDiscard();

    // This cancellation is propagated as there's nothing being written.
    subscriber.awaitSubscription().cancel();
    var subscription = upstream.firstSubscription();
    subscription.awaitAbort();
    assertThat(subscription.flowInterrupted()).isTrue();
  }

  @ExecutorParameterizedTest
  void cancellationIsPropagatedLaterOnFailedWrite(Executor executor) {
    var failOnWriteLatch = new CountDownLatch(1);
    var failingEditor =
        new TestEditor() {
          @Override
          int write(ByteBuffer src) {
            awaitUninterruptibly(failOnWriteLatch);
            // This failure causes cancellation to be propagated.
            throw new TestException();
          }
        };
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher =
        new CacheWritingPublisher(
            upstream,
            failingEditor,
            EMPTY_BUFFER,
            ForkJoinPool.commonPool()); // Use an async pool to avoid blocking indefinitely.
    var subscriber = new TestSubscriber<List<ByteBuffer>>();

    // Cancel subscription eagerly.
    publisher.subscribe(subscriber);
    subscriber.awaitSubscription().cancel();
    upstream.submit(List.of(ByteBuffer.allocate(1)));

    // Cancellation isn't propagated until the editor fails.
    assertThat(upstream.firstSubscription().flowInterrupted()).isFalse();
    failOnWriteLatch.countDown();
    var subscription = upstream.firstSubscription();
    subscription.awaitAbort();
    assertThat(subscription.flowInterrupted()).isTrue();
  }

  @ExecutorParameterizedTest
  void errorFromUpstreamDiscardsEdit(Executor executor) {
    var editor = new TestEditor();
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher = new CacheWritingPublisher(upstream, editor, EMPTY_BUFFER, executor);
    var subscriber = new TestSubscriber<List<ByteBuffer>>();
    publisher.subscribe(subscriber);
    upstream.firstSubscription().fireOrKeepAliveOnError(new TestException());
    upstream.close();

    assertThat(subscriber.awaitError()).isInstanceOf(TestException.class);

    editor.awaitDiscard();
  }

  @ExecutorParameterizedTest
  void failedWriteDiscardsEdit(Executor executor) {
    var failingEditor =
        new TestEditor() {
          @Override
          int write(ByteBuffer src) {
            throw new TestException();
          }
        };
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher = new CacheWritingPublisher(upstream, failingEditor, EMPTY_BUFFER, executor);
    var subscriber = new TestSubscriber<List<ByteBuffer>>();
    publisher.subscribe(subscriber);
    upstream.submit(List.of(ByteBuffer.allocate(1)));
    upstream.close();
    failingEditor.awaitDiscard();
    subscriber.awaitCompletion();
    assertThat(subscriber.errorCount()).isZero();
  }

  @ExecutorParameterizedTest
  void failedWriteDoesNotInterruptStream(Executor executor) {
    var failingEditor =
        new TestEditor() {
          @Override
          int write(ByteBuffer src) {
            throw new TestException();
          }
        };
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher = new CacheWritingPublisher(upstream, failingEditor, EMPTY_BUFFER, executor);
    var subscriber = new StringSubscriber();
    publisher.subscribe(subscriber);
    upstream.submitAll(toResponseBodyIterable("Cache me if you can!"));
    upstream.close();
    failingEditor.awaitDiscard();
    assertThat(subscriber.bodyToString()).isEqualTo("Cache me if you can!");
  }

  /**
   * This test simulates the scenario where writing lags behind downstream consumption. In such
   * case, completion is forwarded downstream and writing continues on background.
   */
  @ExecutorParameterizedTest
  void writeLaggingBehindBodyCompletion(Executor executor) {
    var bodyCompletionLatch = new CountDownLatch(1);
    var laggyEditor =
        new TestEditor() {
          @Override
          int write(ByteBuffer src) {
            awaitUninterruptibly(bodyCompletionLatch);
            return super.write(src);
          }
        };
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher =
        new CacheWritingPublisher(
            upstream,
            laggyEditor,
            EMPTY_BUFFER,
            ForkJoinPool.commonPool()); // Use an async pool to avoid blocking indefinitely.
    var subscriber = new StringSubscriber();
    publisher.subscribe(subscriber);
    subscriber.awaitSubscription();
    executor.execute(
        () -> {
          upstream.submitAll(toResponseBodyIterable("Cyberpunk"));
          upstream.close();
        });

    assertThat(subscriber.bodyToString()).isEqualTo("Cyberpunk");

    // Allow the editor to progress.
    bodyCompletionLatch.countDown();
    laggyEditor.awaitCommit();
    assertThat(laggyEditor.writtenString()).isEqualTo("Cyberpunk");
  }

  @ExecutorParameterizedTest
  void requestAfterCancellation(Executor executor) {
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher =
        new CacheWritingPublisher(
            upstream, new TestEditor(), EMPTY_BUFFER, executor, Listener.disabled(), true, false);
    var subscriber = new TestSubscriber<List<ByteBuffer>>().autoRequest(0);
    publisher.subscribe(subscriber);
    subscriber.requestItems(2);
    assertThat(upstream.firstSubscription().currentDemand()).isEqualTo(2);

    subscriber.awaitSubscription().cancel();

    // This request isn't forwarded upstream as the subscription is cancelled.
    subscriber.requestItems(1);
    assertThat(upstream.firstSubscription().currentDemand()).isEqualTo(2);
  }

  // TODO replace ForkJoinPool

  @ExecutorParameterizedTest
  void waitForCommitToCompleteDownstreamWithWrite_stochasticWithAsyncExecutors(Executor executor) {
    var calledWrite = new CountDownLatch(1);
    var editor =
        new TestEditor() {
          @Override
          int write(ByteBuffer src) {
            calledWrite.countDown();
            return super.write(src);
          }
        };
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher =
        new CacheWritingPublisher(
            upstream,
            editor,
            EMPTY_BUFFER,
            ForkJoinPool.commonPool(),
            Listener.disabled(),
            false,
            true);
    var subscriber = new StringSubscriber();
    publisher.subscribe(subscriber);
    upstream.submitAll(toResponseBodyIterable("Pikachu"));
    upstream.close();
    awaitUninterruptibly(calledWrite);
    assertThat(subscriber.completionCount()).isZero();

    editor.awaitCommit();
    subscriber.awaitCompletion();
    assertThat(editor.writtenString()).isEqualTo("Pikachu");
    assertThat(subscriber.completionCount()).isOne();
    assertThat(subscriber.bodyToString()).isEqualTo("Pikachu");
  }

  @ExecutorParameterizedTest
  void waitForCommitWithFailedWrite_stochasticWithAsyncExecutors(Executor executor) {
    var calledWrite = new CountDownLatch(1);
    var editor =
        new TestEditor() {
          @Override
          int write(ByteBuffer src) {
            calledWrite.countDown();
            throw new TestException();
          }
        };
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher =
        new CacheWritingPublisher(
            upstream,
            editor,
            EMPTY_BUFFER,
            ForkJoinPool.commonPool(),
            Listener.disabled(),
            false,
            true);
    var subscriber = new StringSubscriber();
    publisher.subscribe(subscriber);
    upstream.submitAll(toResponseBodyIterable("Pikachu"));
    awaitUninterruptibly(calledWrite);
    assertThat(subscriber.completionCount()).isZero();

    editor.awaitDiscard();

    upstream.close();
    subscriber.awaitCompletion();
    assertThat(subscriber.errorCount()).isZero();
    assertThat(subscriber.bodyToString()).isEqualTo("Pikachu");
  }

  @ExecutorParameterizedTest
  void waitForCommitWithDelayedFailedWrite_stochasticWithAsyncExecutors(Executor executor) {
    var calledWrite = new CountDownLatch(1);
    var leaveWrite = new CountDownLatch(1);
    var editor =
        new TestEditor() {
          @Override
          int write(ByteBuffer src) {
            calledWrite.countDown();
            awaitUninterruptibly(leaveWrite);
            throw new TestException();
          }
        };
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher =
        new CacheWritingPublisher(
            upstream,
            editor,
            EMPTY_BUFFER,
            ForkJoinPool.commonPool(),
            Listener.disabled(),
            false,
            true);
    var subscriber = new StringSubscriber();
    publisher.subscribe(subscriber);
    upstream.submitAll(toResponseBodyIterable("Pikachu"));
    upstream.close();
    awaitUninterruptibly(calledWrite);
    assertThat(subscriber.completionCount()).isZero();

    leaveWrite.countDown();
    editor.awaitDiscard();
    subscriber.awaitCompletion();
    assertThat(subscriber.errorCount()).isZero();
    assertThat(subscriber.bodyToString()).isEqualTo("Pikachu");
  }

  private static Iterable<List<ByteBuffer>> toResponseBodyIterable(String str) {
    return toResponseBodyIterable(str, 2);
  }

  private static Iterable<List<ByteBuffer>> toResponseBodyIterable(String str, int bufferSize) {
    return () ->
        new ByteBufferListIterator(UTF_8.encode(str), bufferSize, TestUtils.BUFFERS_PER_LIST);
  }

  private static class TestEditor implements Editor {
    private static final int TIMEOUT_SECONDS = 3;

    private final Lock lock = new ReentrantLock();
    private final Condition closedCondition = lock.newCondition();

    @GuardedBy("lock")
    private final List<ByteBuffer> writes = new ArrayList<>();

    volatile @MonotonicNonNull ByteBuffer metadata;
    volatile boolean closed;
    volatile boolean committed;

    TestEditor() {}

    @Override
    public String key() {
      return "null-key";
    }

    int write(ByteBuffer src) {
      lock.lock();
      try {
        int written = src.remaining();
        writes.add(Utils.copy(src));
        return written;
      } finally {
        lock.unlock();
      }
    }

    @Override
    public EntryWriter writer() {
      return new EntryWriter() {
        @Override
        public int write(ByteBuffer src) {
          return TestEditor.this.write(src);
        }

        @Override
        public long write(List<ByteBuffer> srcs) {
          return srcs.stream().mapToInt(this::write).sum();
        }
      };
    }

    @Override
    public void commit(ByteBuffer metadata) {
      lock.lock();
      try {
        this.metadata = requireNonNull(metadata);
        committed = true;
        closed = true;
        closedCondition.signalAll();
      } finally {
        lock.unlock();
      }
    }

    @Override
    public void close() {
      lock.lock();
      try {
        closed = true;
        closedCondition.signalAll();
      } finally {
        lock.unlock();
      }
    }

    void awaitClose() {
      long remainingNanos = TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
      lock.lock();
      try {
        while (!closed) {
          try {
            if (remainingNanos <= 0) {
              fail("expected to be closed within " + TIMEOUT_SECONDS + " seconds");
            }
            remainingNanos = closedCondition.awaitNanos(remainingNanos);
          } catch (InterruptedException e) {
            fail("unexpected exception", e);
          }
        }
      } finally {
        lock.unlock();
      }
    }

    void awaitCommit() {
      awaitClose();
      assertThat(committed).withFailMessage("expected the edit to be committed").isTrue();
    }

    void awaitDiscard() {
      awaitClose();
      assertThat(committed).withFailMessage("expected the edit to be discarded").isFalse();
    }

    ByteBuffer writtenBytes() {
      lock.lock();
      try {
        return BodyCollector.collect(writes);
      } finally {
        lock.unlock();
      }
    }

    String writtenString() {
      lock.lock();
      try {
        assertThat(committed).withFailMessage("Not commited").isTrue();
      } finally {
        lock.unlock();
      }
      return UTF_8.decode(writtenBytes()).toString();
    }
  }

  private static final class StringSubscriber extends TestSubscriber<List<ByteBuffer>> {
    StringSubscriber() {}

    String bodyToString() {
      var body =
          BodyCollector.collect(
              pollAll().stream().flatMap(List::stream).collect(Collectors.toUnmodifiableList()));
      return UTF_8.decode(body).toString();
    }
  }
}
