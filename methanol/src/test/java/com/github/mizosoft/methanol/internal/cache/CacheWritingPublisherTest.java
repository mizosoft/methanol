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

import static com.github.mizosoft.methanol.testing.TestUtils.EMPTY_BUFFER;
import static com.github.mizosoft.methanol.testing.TestUtils.awaitUninterruptibly;
import static com.github.mizosoft.methanol.testing.junit.ExecutorExtension.ExecutorType.CACHED_POOL;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.CacheWritingPublisher.Listener;
import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.testing.BodyCollector;
import com.github.mizosoft.methanol.testing.ByteBufferListIterator;
import com.github.mizosoft.methanol.testing.Logging;
import com.github.mizosoft.methanol.testing.SubmittablePublisher;
import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.TestSubscriber;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension.ExecutorConfig;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension.ExecutorParameterizedTest;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ExecutorExtension.class)
class CacheWritingPublisherTest {
  static {
    Logging.disable(CacheWritingPublisher.class);
  }

  @ExecutorParameterizedTest
  void writeString(Executor executor) {
    var editor = new TestEditor();
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher = new CacheWritingPublisher(upstream, editor, EMPTY_BUFFER);
    var subscriber = new StringSubscriber();
    publisher.subscribe(subscriber);
    upstream.submitAll(toResponseBody("Cache me if you can!"));
    upstream.close();
    subscriber.awaitComplete();
    assertThat(subscriber.bodyToString()).isEqualTo("Cache me if you can!");
    assertThat(editor.writtenString()).isEqualTo("Cache me if you can!");

    editor.awaitClose();
    assertThat(editor.discarded).isFalse();
  }

  @Test
  void commitMetadata() {
    var editor = new TestEditor();
    var publisher =
        new CacheWritingPublisher(FlowSupport.emptyPublisher(), editor, UTF_8.encode("abc"));
    var subscriber = new TestSubscriber<>();
    publisher.subscribe(subscriber);
    subscriber.awaitComplete();
    assertThat(editor.metadata)
        .isNotNull()
        .extracting(buffer -> UTF_8.decode(buffer).toString(), STRING)
        .isEqualTo("abc");
  }

  @Test
  void subscribeTwice() {
    var publisher =
        new CacheWritingPublisher(FlowSupport.emptyPublisher(), new TestEditor(), EMPTY_BUFFER);
    publisher.subscribe(new TestSubscriber<>());

    var secondSubscriber = new TestSubscriber<>();
    publisher.subscribe(secondSubscriber);
    secondSubscriber.awaitComplete();
    assertThat(secondSubscriber.errorCount).isOne();
    assertThat(secondSubscriber.lastError).isInstanceOf(IllegalStateException.class);
  }

  /**
   * The publisher shouldn't propagate cancellation upstream and prefer instead to continue caching
   * the body.
   */
  @ExecutorParameterizedTest
  void cancellationIsNotPropagatedIfWriting(Executor executor) {
    var editor = new TestEditor();
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher = new CacheWritingPublisher(upstream, editor, EMPTY_BUFFER);
    var subscriber = new StringSubscriber();
    publisher.subscribe(subscriber);

    // Cancel before submitting items.
    subscriber.awaitOnSubscribe();
    subscriber.subscription.cancel();
    upstream.submitAll(toResponseBody("Cancel me if you can!"));
    upstream.close();

    // Writing completes successfully and cancellation is not propagated.
    editor.awaitClose();
    assertThat(editor.discarded).isFalse();
    assertThat(upstream.firstSubscription().flowInterrupted()).isFalse();
    assertThat(editor.writtenString()).isEqualTo("Cancel me if you can!");

    // Subscriber's cancellation request is satisfied & body flow stops.
    assertThat(subscriber.items)
        .withFailMessage(() -> "unexpectedly received: " + subscriber.bodyToString())
        .isEmpty();
  }

  @ExecutorParameterizedTest
  void cancellationIsPropagatedIfNotWriting(Executor executor) {
    var failingEditor =
        new TestEditor() {
          @Override
          CompletableFuture<Integer> writeAsync(ByteBuffer src) {
            var future = new CompletableFuture<Integer>();
            executeLaterMillis(() -> future.completeExceptionally(new TestException()), 100);
            return future;
          }
        };
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher = new CacheWritingPublisher(upstream, failingEditor, EMPTY_BUFFER);
    var subscriber = new TestSubscriber<List<ByteBuffer>>();
    publisher.subscribe(subscriber);
    upstream.submit(List.of(ByteBuffer.allocate(1))); // Trigger write.

    // Wait till the error is handled and failingEditor is closed.
    failingEditor.awaitClose();

    // This cancellation is propagated as there's nothing being written.
    subscriber.awaitOnSubscribe();
    subscriber.subscription.cancel();
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
          CompletableFuture<Integer> writeAsync(ByteBuffer src) {
            return CompletableFuture.supplyAsync(
                () -> {
                  awaitUninterruptibly(failOnWriteLatch);
                  // This failure causes cancellation to be propagated.
                  throw new TestException();
                });
          }
        };
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher = new CacheWritingPublisher(upstream, failingEditor, EMPTY_BUFFER);
    var subscriber = new TestSubscriber<List<ByteBuffer>>();

    // Cancel subscription eagerly.
    publisher.subscribe(subscriber);
    subscriber.awaitOnSubscribe();
    subscriber.subscription.cancel();
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
    var publisher = new CacheWritingPublisher(upstream, editor, EMPTY_BUFFER);
    var subscriber = new TestSubscriber<List<ByteBuffer>>();
    publisher.subscribe(subscriber);
    upstream.firstSubscription().signalError(new TestException());
    upstream.close();

    subscriber.awaitError();
    assertThat(subscriber.lastError).isInstanceOf(TestException.class);

    editor.awaitClose();
    assertThat(editor.discarded).isTrue();
  }

  @ExecutorParameterizedTest
  void failedWriteDiscardsEdit(Executor executor) {
    var failingEditor =
        new TestEditor() {
          @Override
          CompletableFuture<Integer> writeAsync(ByteBuffer src) {
            return CompletableFuture.failedFuture(new TestException());
          }
        };
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher = new CacheWritingPublisher(upstream, failingEditor, EMPTY_BUFFER);
    var subscriber = new TestSubscriber<List<ByteBuffer>>();
    publisher.subscribe(subscriber);
    upstream.submit(List.of(ByteBuffer.allocate(1)));
    upstream.close();
    failingEditor.awaitClose();
    assertThat(failingEditor.discarded).isTrue();
  }

  @ExecutorParameterizedTest
  void failedWriteDoesNotInterruptStream(Executor executor) {
    var failingEditor =
        new TestEditor() {
          @Override
          CompletableFuture<Integer> writeAsync(ByteBuffer src) {
            return CompletableFuture.failedFuture(new TestException());
          }
        };
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher = new CacheWritingPublisher(upstream, failingEditor, EMPTY_BUFFER);
    var subscriber = new StringSubscriber();
    publisher.subscribe(subscriber);
    upstream.submitAll(toResponseBody("Cache me if you can!"));
    upstream.close();

    failingEditor.awaitClose();
    assertThat(failingEditor.discarded).isTrue();

    subscriber.awaitComplete();
    assertThat(subscriber.bodyToString()).isEqualTo("Cache me if you can!");
  }

  /**
   * This test simulates the scenario where writing lags behind downstream consumption. In such
   * case, completion is forwarded downstream and writing continues on background.
   */
  @ExecutorParameterizedTest
  @ExecutorConfig(CACHED_POOL)
  void writeLaggingBehindBodyCompletion(Executor executor) {
    var bodyCompletionLatch = new CountDownLatch(1);
    var laggyEditor =
        new TestEditor() {
          @Override
          CompletableFuture<Integer> writeAsync(ByteBuffer src) {
            return CompletableFuture.runAsync(
                    () -> awaitUninterruptibly(bodyCompletionLatch), executor)
                .thenCompose(__ -> super.writeAsync(src));
          }
        };
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher = new CacheWritingPublisher(upstream, laggyEditor, EMPTY_BUFFER);
    var subscriber = new StringSubscriber();
    publisher.subscribe(subscriber);
    subscriber.awaitOnSubscribe();
    executor.execute(
        () -> {
          upstream.submitAll(toResponseBody("Cyberpunk"));
          upstream.close();
        });

    subscriber.awaitComplete();
    assertThat(subscriber.bodyToString()).isEqualTo("Cyberpunk");

    // Allow the editor to progress.
    bodyCompletionLatch.countDown();
    laggyEditor.awaitClose();
    assertThat(laggyEditor.writtenString()).isEqualTo("Cyberpunk");
  }

  @ExecutorParameterizedTest
  void requestAfterCancellation(Executor executor) {
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher =
        new CacheWritingPublisher(
            upstream, new TestEditor(), EMPTY_BUFFER, Listener.disabled(), true);
    var subscriber = new TestSubscriber<List<ByteBuffer>>();
    subscriber.request = 0;
    publisher.subscribe(subscriber);
    subscriber.awaitOnSubscribe();
    subscriber.subscription.request(2);
    assertThat(upstream.firstSubscription().currentDemand()).isEqualTo(2);

    subscriber.subscription.cancel();

    // This request isn't forwarded upstream as the subscription is cancelled.
    subscriber.subscription.request(1);
    assertThat(upstream.firstSubscription().currentDemand()).isEqualTo(2);
  }

  private static void executeLaterMillis(Runnable task, long millis) {
    CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS).execute(task);
  }

  private static Iterable<List<ByteBuffer>> toResponseBody(String str) {
    return () -> new ByteBufferListIterator(UTF_8.encode(str), 2, 2);
  }

  private static class TestEditor implements Editor {
    private final List<ByteBuffer> writes = new CopyOnWriteArrayList<>();

    volatile @MonotonicNonNull ByteBuffer metadata;
    volatile boolean discarded;
    volatile boolean closed;
    volatile boolean committed;

    @Override
    public String key() {
      return "null-key";
    }

    CompletableFuture<Integer> writeAsync(ByteBuffer src) {
      writes.add(Utils.copy(src));
      int written = src.remaining();
      src.limit(src.position());
      return CompletableFuture.completedFuture(written);
    }

    @Override
    public Store.EntryWriter writer() {
      return this::writeAsync;
    }

    @Override
    public synchronized CompletableFuture<Boolean> commitAsync(ByteBuffer metadata) {
      this.metadata = requireNonNull(metadata);
      committed = true;
      closed = true;
      notifyAll();
      return CompletableFuture.completedFuture(true);
    }

    @Override
    public synchronized void close() {
      discarded = !committed;
      closed = true;
      notifyAll();
    }

    synchronized void awaitClose() {
      while (!closed) {
        try {
          wait();
        } catch (InterruptedException e) {
          fail(e);
        }
      }
    }

    ByteBuffer writtenBytes() {
      return BodyCollector.collect(writes);
    }

    String writtenString() {
      return UTF_8.decode(writtenBytes()).toString();
    }
  }

  private static final class StringSubscriber extends TestSubscriber<List<ByteBuffer>> {
    StringSubscriber() {}

    String bodyToString() {
      var body =
          BodyCollector.collect(
              items.stream().flatMap(Collection::stream).collect(Collectors.toUnmodifiableList()));
      return UTF_8.decode(body).toString();
    }
  }
}
