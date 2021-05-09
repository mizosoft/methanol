package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType.CACHED_POOL;
import static com.github.mizosoft.methanol.testutils.TestUtils.awaitUninterruptibly;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.mizosoft.methanol.internal.cache.CacheWritingPublisher.Listener;
import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorConfig;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorParameterizedTest;
import com.github.mizosoft.methanol.testing.SubmittableSubscription;
import com.github.mizosoft.methanol.testutils.BodyCollector;
import com.github.mizosoft.methanol.testutils.BuffListIterator;
import com.github.mizosoft.methanol.testutils.Logging;
import com.github.mizosoft.methanol.testutils.TestException;
import com.github.mizosoft.methanol.testutils.TestSubscriber;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
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
    var publisher = new CacheWritingPublisher(upstream, editor);
    var subscriber = new StringSubscriber();

    publisher.subscribe(subscriber);
    subscriber.awaitSubscribe();

    try (upstream) {
      upstream.submitAll(toResponseBody("Cache me if you can!"));
    }

    subscriber.awaitComplete();
    assertThat(subscriber.bodyToString()).isEqualTo("Cache me if you can!");
    assertThat(editor.writtenToString()).isEqualTo("Cache me if you can!");

    editor.awaitClose();
    assertThat(editor.discarded).isFalse();
  }

  @Test
  void subscribeTwice() {
    var publisher = new CacheWritingPublisher(FlowSupport.emptyPublisher(), new TestEditor());
    publisher.subscribe(new TestSubscriber<>());

    var secondSubscriber = new TestSubscriber<>();
    publisher.subscribe(secondSubscriber);

    secondSubscriber.awaitComplete();
    assertThat(secondSubscriber.errors).isOne();
    assertThat(secondSubscriber.lastError).isInstanceOf(IllegalStateException.class);
  }

  /**
   * The publisher shouldn't propagate cancellation upstream and prefer to complete caching the
   * body.
   */
  @ExecutorParameterizedTest
  void cancellationIsNotPropagatedIfWriting(Executor executor) {
    var editor = new TestEditor();
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher = new CacheWritingPublisher(upstream, editor);
    var subscriber = new StringSubscriber();

    publisher.subscribe(subscriber);
    subscriber.awaitSubscribe();
    subscriber.subscription.cancel();

    try (upstream) {
      upstream.submitAll(toResponseBody("Cancel me if you can!"));
    }

    // Writing completes successfully and cancellation is not propagated
    editor.awaitClose();
    assertThat(editor.discarded).isFalse();
    assertThat(upstream.firstSubscription().flowInterrupted).isFalse();
    assertThat(editor.writtenToString()).isEqualTo("Cancel me if you can!");

    // Subscriber's cancellation request is satisfied & body flow stops
    assertThat(subscriber.items)
        .withFailMessage(() -> "Unexpectedly received: " + subscriber.bodyToString())
        .isEmpty();
  }

  @ExecutorParameterizedTest
  void cancellationIsPropagatedIfNotWriting(Executor executor) {
    var failingEditor =
        new TestEditor() {
          @Override
          public CompletableFuture<Integer> writeAsync(long position, ByteBuffer src) {
            var future = new CompletableFuture<Integer>();
            executeLaterMillis(() -> future.completeExceptionally(new TestException()), 100);
            return future;
          }
        };
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher = new CacheWritingPublisher(upstream, failingEditor);
    var subscriber = new TestSubscriber<List<ByteBuffer>>();

    publisher.subscribe(subscriber);
    subscriber.awaitSubscribe();

    upstream.submit(List.of(ByteBuffer.allocate(1))); // Trigger write

    // Wait till the error is handled and failingEditor is closed
    failingEditor.awaitClose();

    // This cancellation is propagated as there's nothing being written
    subscriber.subscription.cancel();

    var subscription = upstream.firstSubscription();
    subscription.awaitAbort();
    assertThat(subscription.flowInterrupted).isTrue();
  }

  @ExecutorParameterizedTest
  void cancellationIsPropagatedLaterOnFailedWrite(Executor executor) {
    var cancelledSubscriptionLatch = new CountDownLatch(1);
    var failingEditor =
        new TestEditor() {
          @Override
          public CompletableFuture<Integer> writeAsync(long position, ByteBuffer src) {
            return CompletableFuture.supplyAsync(
                () -> {
                  awaitUninterruptibly(cancelledSubscriptionLatch);
                  // This failure causes cancellation to be propagated
                  throw new TestException();
                });
          }
        };
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher = new CacheWritingPublisher(upstream, failingEditor);
    var subscriber = new TestSubscriber<List<ByteBuffer>>();

    publisher.subscribe(subscriber);
    subscriber.awaitSubscribe();

    upstream.submit(List.of(ByteBuffer.allocate(1))); // Trigger write

    subscriber.subscription.cancel();

    // Cancellation isn't propagated until the editor fails
    assertThat(upstream.firstSubscription().flowInterrupted).isFalse();

    cancelledSubscriptionLatch.countDown();

    var subscription = upstream.firstSubscription();
    subscription.awaitAbort();
    assertThat(subscription.flowInterrupted).isTrue();
  }

  @ExecutorParameterizedTest
  void errorFromUpstreamDiscardsEdit(Executor executor) {
    var editor = new TestEditor();
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher = new CacheWritingPublisher(upstream, editor);
    var subscriber = new TestSubscriber<List<ByteBuffer>>();

    publisher.subscribe(subscriber);
    subscriber.awaitSubscribe();

    try (upstream) {
      upstream.firstSubscription().signalError(new TestException());
    }

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
          public CompletableFuture<Integer> writeAsync(long position, ByteBuffer src) {
            return CompletableFuture.failedFuture(new TestException());
          }
        };
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher = new CacheWritingPublisher(upstream, failingEditor);
    var subscriber = new TestSubscriber<List<ByteBuffer>>();

    publisher.subscribe(subscriber);
    subscriber.awaitSubscribe();

    try (upstream) {
      upstream.submit(List.of(ByteBuffer.allocate(1))); // Trigger write
    }

    failingEditor.awaitClose();
    assertThat(failingEditor.discarded).isTrue();
  }

  @ExecutorParameterizedTest
  void failedWriteDoesNotInterruptStream(Executor executor) {
    var failingEditor =
        new TestEditor() {
          @Override
          public CompletableFuture<Integer> writeAsync(long position, ByteBuffer src) {
            return CompletableFuture.failedFuture(new TestException());
          }
        };
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher = new CacheWritingPublisher(upstream, failingEditor);
    var subscriber = new StringSubscriber();

    publisher.subscribe(subscriber);
    subscriber.awaitSubscribe();

    try (upstream) {
      upstream.submitAll(toResponseBody("Cache me if you can!"));
    }

    failingEditor.awaitClose();
    assertThat(failingEditor.discarded).isTrue();

    subscriber.awaitComplete();
    assertThat(subscriber.bodyToString()).isEqualTo("Cache me if you can!");
  }

  /**
   * This test simulates the scenario where some (or all) of the writes don't have a chance to
   * finish before upstream calls our onComplete(). In such case, completion is forwarded downstream
   * and writing continues on background.
   */
  @ExecutorParameterizedTest
  @ExecutorConfig(CACHED_POOL)
  void writeLaggingBehindBodyCompletion(Executor threadPool) {
    var bodyCompletionLatch = new CountDownLatch(1);
    var laggyEditor =
        new TestEditor() {
          @Override
          public CompletableFuture<Integer> writeAsync(long position, ByteBuffer src) {
            return CompletableFuture.runAsync(
                    () -> awaitUninterruptibly(bodyCompletionLatch), threadPool)
                .thenCompose(__ -> super.writeAsync(position, src));
          }
        };
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(threadPool);
    var publisher = new CacheWritingPublisher(upstream, laggyEditor);
    var subscriber = new StringSubscriber();

    publisher.subscribe(subscriber);
    subscriber.awaitSubscribe();

    threadPool.execute(
        () -> {
          try (upstream) {
            upstream.submitAll(toResponseBody("Cyberpunk"));
          }
        });

    subscriber.awaitComplete();

    // Allow the editor to progress
    bodyCompletionLatch.countDown();
    assertThat(subscriber.bodyToString()).isEqualTo("Cyberpunk");

    laggyEditor.awaitClose();
    assertThat(laggyEditor.writtenToString()).isEqualTo("Cyberpunk");
  }

  @ExecutorParameterizedTest
  void requestAfterCancellation(Executor executor) {
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var publisher =
        new CacheWritingPublisher(upstream, new TestEditor(), Listener.disabled(), true);
    var subscriber = new TestSubscriber<List<ByteBuffer>>();
    subscriber.request = 0;

    publisher.subscribe(subscriber);
    subscriber.awaitSubscribe();

    subscriber.subscription.request(2);
    assertThat(upstream.firstSubscription().currentDemand()).isEqualTo(2);

    subscriber.subscription.cancel();

    // This request isn't forwarded upstream as the subscription is cancelled
    subscriber.subscription.request(1);
    assertThat(upstream.firstSubscription().currentDemand()).isEqualTo(2);
  }

  private static void executeLaterMillis(Runnable task, long millis) {
    CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS).execute(task);
  }

  private static Iterable<List<ByteBuffer>> toResponseBody(String str) {
    return () -> new BuffListIterator(UTF_8.encode(str), 2, 2);
  }

  private static class TestEditor implements Editor {
    private final List<WriteRequest> writes = new CopyOnWriteArrayList<>();
    @MonotonicNonNull ByteBuffer metadata;
    volatile boolean discarded;
    volatile boolean closed;
    volatile boolean committed;

    @Override
    public String key() {
      return "null-key";
    }

    @Override
    public void metadata(ByteBuffer metadata) {
      this.metadata = metadata;
    }

    @Override
    public CompletableFuture<Integer> writeAsync(long position, ByteBuffer src) {
      writes.add(new WriteRequest(position, src.duplicate()));
      int written = src.remaining();
      src.limit(src.position());
      return CompletableFuture.completedFuture(written);
    }

    @Override
    public void commitOnClose() {
      committed = true;
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

    ByteBuffer written() {
      long p = 0;
      var buffers = new ArrayList<ByteBuffer>();
      for (var write : writes) {
        assertThat(write.position)
            .withFailMessage("non-sequential write")
            .isEqualTo(p);

        buffers.add(write.buffer);
        p += write.buffer.remaining();
      }
      return BodyCollector.collect(buffers);
    }

    String writtenToString() {
      return UTF_8.decode(written()).toString();
    }

    static final class WriteRequest {
      final long position;
      final ByteBuffer buffer;

      WriteRequest(long position, ByteBuffer buffer) {
        this.position = position;
        this.buffer = buffer;
      }
    }
  }

  private static final class StringSubscriber extends TestSubscriber<List<ByteBuffer>> {
    StringSubscriber() {}

    String bodyToString() {
      var body = BodyCollector.collect(
          items.stream().flatMap(Collection::stream).collect(Collectors.toUnmodifiableList()));
      return UTF_8.decode(body).toString();
    }
  }

  /**
   * Similar to {@link java.util.concurrent.SubmissionPublisher} but doesn't require the executor to
   * operate concurrently.
   */
  private static final class SubmittablePublisher<T> implements Publisher<T>, AutoCloseable {
    private final List<SubmittableSubscription<T>> subscriptions = new CopyOnWriteArrayList<>();
    private final Executor executor;

    SubmittablePublisher(Executor executor) {
      this.executor = executor;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
      var subscription = new SubmittableSubscription<T>(subscriber, executor);
      subscriptions.add(subscription);
      subscription.signal(true); // Apply onSubscribe
    }

    SubmittableSubscription<T> firstSubscription() {
      assertThat(subscriptions).withFailMessage("nothing has subscribed yet").isNotEmpty();
      return subscriptions.get(0);
    }

    void submit(T item) {
      subscriptions.forEach(s -> s.submit(item));
    }

    void submitAll(Iterable<T> items) {
      items.forEach(this::submit);
    }

    @Override
    public void close() {
      subscriptions.forEach(SubmittableSubscription::complete);
    }
  }
}
