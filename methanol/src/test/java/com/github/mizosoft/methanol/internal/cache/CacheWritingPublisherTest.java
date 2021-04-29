package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType.CACHED_POOL;
import static com.github.mizosoft.methanol.testutils.TestUtils.awaitUninterruptibly;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.flow.AbstractSubscription;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorConfig;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorParameterizedTest;
import com.github.mizosoft.methanol.testutils.BodyCollector;
import com.github.mizosoft.methanol.testutils.BuffListIterator;
import com.github.mizosoft.methanol.testutils.TestException;
import com.github.mizosoft.methanol.testutils.TestSubscriber;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
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
  @ExecutorParameterizedTest
  @ExecutorConfig
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
    assertEquals("Cache me if you can!", subscriber.bodyToString());
    assertEquals("Cache me if you can!", editor.writtenToString());

    editor.awaitClose();
    assertFalse(editor.discarded);
  }

  @Test
  void subscribeTwice() {
    var publisher = new CacheWritingPublisher(
        FlowSupport.emptyPublisher(), new TestEditor());
    publisher.subscribe(new TestSubscriber<>());

    var secondSubscriber = new TestSubscriber<>();
    publisher.subscribe(secondSubscriber);

    secondSubscriber.awaitComplete();
    assertEquals(1, secondSubscriber.errors);
    assertThrows(IllegalStateException.class, () -> { throw secondSubscriber.lastError; });
  }

  /**
   * The subscriber shouldn't propagate cancellation upstream and prefer to complete caching the
   * body.
   */
  @ExecutorParameterizedTest
  @ExecutorConfig
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

    // Writing completes successfully and cancellation is propagated
    editor.awaitClose();
    assertFalse(editor.discarded);
    assertFalse(upstream.firstSubscription().flowInterrupted);
    assertEquals("Cancel me if you can!", editor.writtenToString());

    // Subscriber's cancellation request is satisfied & body flow stops
    assertTrue(subscriber.items.isEmpty(), () -> "Received: " + subscriber.bodyToString());
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void cancellationIsPropagatedIfNotWriting(Executor executor) {
    var failingEditor = new TestEditor() {
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
    // Don't complete upstream

    // Wait till error is handled and failingEditor is closed
    failingEditor.awaitClose();

    // This cancellation is propagated as there's nothing being written anymore
    subscriber.subscription.cancel();

    var subscription = upstream.firstSubscription();
    subscription.awaitAbort();
    assertTrue(subscription.flowInterrupted);
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void cancellationIsPropagatedLaterOnFailedWrite(Executor executor) {
    var cancelledSubscription = new CountDownLatch(1);
    var failingEditor = new TestEditor() {
      @Override
      public CompletableFuture<Integer> writeAsync(long position, ByteBuffer src) {
        return CompletableFuture.supplyAsync(() -> {
          awaitUninterruptibly(cancelledSubscription);
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
    // Don't complete upstream

    subscriber.subscription.cancel();
    cancelledSubscription.countDown();

    var subscription = upstream.firstSubscription();
    subscription.awaitAbort();
    assertTrue(subscription.flowInterrupted);
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
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
    assertThrows(TestException.class, () -> { throw subscriber.lastError; });

    editor.awaitClose();
    assertTrue(editor.discarded);
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void failedWriteDiscardsEdit(Executor executor) {
    var failingEditor = new TestEditor() {
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
    assertTrue(failingEditor.discarded);
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void failedWriteDoesNotInterruptStream(Executor executor) {
    var failingEditor = new TestEditor() {
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
    assertTrue(failingEditor.discarded);

    subscriber.awaitComplete();
    assertEquals("Cache me if you can!", subscriber.bodyToString());
  }

  /**
   * This test simulates the scenario where some (or all) of the writes don't have a chance to
   * finish before upstream calls our onComplete(). In such case, completion is forwarded downstream
   * and writing continues on background.
   */
  @ExecutorParameterizedTest
  @ExecutorConfig(CACHED_POOL)
  void writeLaggingBehindBodyCompletion(Executor threadPool) {
    var bodyCompletion = new CountDownLatch(1);
    var editor = new TestEditor() {
      @Override
      public CompletableFuture<Integer> writeAsync(long position, ByteBuffer src) {
        return CompletableFuture.runAsync(() -> awaitUninterruptibly(bodyCompletion), threadPool)
            .thenCompose(__ -> super.writeAsync(position, src));
      }
    };
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(threadPool);
    var publisher = new CacheWritingPublisher(upstream, editor);
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
    bodyCompletion.countDown();
    assertEquals("Cyberpunk", subscriber.bodyToString());

    editor.awaitClose();
    assertEquals("Cyberpunk", editor.writtenToString());
  }

  private static void executeLaterMillis(Runnable task, long millis) {
    CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS).execute(task);
  }

  private static ByteBuffer collect(Collection<List<ByteBuffer>> buffers) {
    return BodyCollector.collect(
        buffers.stream().flatMap(Collection::stream).collect(Collectors.toUnmodifiableList()));
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
        assertEquals(p, write.position, "non-sequential right");

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
      return UTF_8.decode(collect(super.items)).toString();
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
      assertFalse(subscriptions.isEmpty(), "nothing has subscribed yet");
      return subscriptions.get(0);
    }

    void submit(T item) {
      subscriptions.forEach(s -> s.signal(item));
    }

    void submitAll(Iterable<T> items) {
      items.forEach(this::submit);
    }

    @Override
    public void close() {
      subscriptions.forEach(SubmittableSubscription::signalCompletion);
    }

    static final class SubmittableSubscription<T> extends AbstractSubscription<T> {
      private final ConcurrentLinkedQueue<T> items = new ConcurrentLinkedQueue<>();

      private volatile boolean complete;
      volatile boolean aborted;
      volatile boolean flowInterrupted;

      SubmittableSubscription(Subscriber<? super T> downstream, Executor executor) {
        super(downstream, executor);
      }

      @Override
      protected long emit(Subscriber<? super T> downstream, long emit) {
        T item;
        long submitted = 0L;
        while (true) {
          if (items.isEmpty() && complete) {
            cancelOnComplete(downstream);
            return 0L;
          } else if (submitted >= emit
              || (item = items.poll()) == null) { // Exhausted either demand or items
            return submitted;
          } else if (submitOnNext(downstream, item)) {
            submitted++;
          } else {
            return 0L;
          }
        }
      }

      @Override
      protected synchronized void abort(boolean flowInterrupted) {
        aborted = true;
        this.flowInterrupted = flowInterrupted;
        notifyAll();
      }

      synchronized void awaitAbort() {
        while (!aborted) {
          try {
            wait();
          } catch (InterruptedException e) {
            fail(e);
          }
        }
      }

      void signal(T item) {
        items.offer(item);
        signal(false);
      }

      void signalCompletion() {
        complete = true;
        signal(true);
      }
    }
  }
}
