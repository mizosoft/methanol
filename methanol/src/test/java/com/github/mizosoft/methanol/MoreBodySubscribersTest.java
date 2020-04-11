/*
 * Copyright (c) 2019, 2020 Moataz Abdelnasser
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

import static com.github.mizosoft.methanol.MoreBodySubscribers.fromAsyncSubscriber;
import static com.github.mizosoft.methanol.MoreBodySubscribers.ofByteChannel;
import static com.github.mizosoft.methanol.MoreBodySubscribers.ofDeferredObject;
import static com.github.mizosoft.methanol.MoreBodySubscribers.ofObject;
import static com.github.mizosoft.methanol.MoreBodySubscribers.ofReader;
import static com.github.mizosoft.methanol.MoreBodySubscribers.withReadTimeout;
import static com.github.mizosoft.methanol.testutils.TestUtils.awaitUninterruptedly;
import static java.net.http.HttpResponse.BodySubscribers.discarding;
import static java.net.http.HttpResponse.BodySubscribers.ofString;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.testutils.BuffListIterator;
import com.github.mizosoft.methanol.testutils.TestException;
import com.github.mizosoft.methanol.testutils.TestSubscriber;
import com.github.mizosoft.methanol.testutils.TestUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.FlowAdapters;
import org.reactivestreams.example.unicast.AsyncIterablePublisher;

class MoreBodySubscribersTest {

  private Executor executor;
  private ScheduledExecutorService scheduler;

  @BeforeEach
  void setupExecutor() {
    executor = Executors.newFixedThreadPool(8);
    scheduler = Executors.newSingleThreadScheduledExecutor();
  }

  @AfterEach
  void shutdownExecutor() {
    TestUtils.shutdown(executor, scheduler);
  }

  @Test
  void ofByteChannel_isCompleted() {
    var subscriber = ofByteChannel();
    assertNotNull(toFuture(subscriber).getNow(null));
  }

  @Test
  void ofByteChannel_readsBody() throws IOException {
    int buffSize = 100;
    int buffsPerList = 5;
    int listCount = 5;
    var str = rndAlpha(buffSize * buffsPerList * listCount);
    var publisher = asciiPublisherOf(str, buffSize, buffsPerList);
    var subscriber = ofByteChannel();
    publisher.subscribe(subscriber);
    var channel = getBody(subscriber);
    int n;
    byte[] bytes = new byte[0];
    var buff = ByteBuffer.allocate(128);
    while ((n = channel.read(buff.clear())) != -1) {
      int p = bytes.length;
      bytes = Arrays.copyOf(bytes, bytes.length + n);
      buff.flip().get(bytes, p, n);
    }
    assertEquals(str, new String(bytes, US_ASCII));
  }

  @Test
  void ofByteChannel_isInterruptible() {
    var subscriber = ofByteChannel();
    var channel = getBody(subscriber);
    assertTrue(channel instanceof InterruptibleChannel);
    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    var awaitRead = new CountDownLatch(1);
    var readerThread = Thread.currentThread();
    new Thread(() -> {
      awaitUninterruptedly(awaitRead);
      readerThread.interrupt();
    }).start();
    assertThrows(ClosedByInterruptException.class, () -> {
      awaitRead.countDown();
      channel.read(ByteBuffer.allocate(1));
    });
  }

  @Test
  void ofByteChannel_blocksForAtLeastOneByte() throws IOException {
    var oneByte = ByteBuffer.wrap(new byte[] {'b'});
    var subscriber = ofByteChannel();
    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    subscriber.onNext(List.of(oneByte.duplicate()));
    var channel = getBody(subscriber);
    var twoBytes = ByteBuffer.allocate(2);
    channel.read(twoBytes); // Should block for reading only 1 byte
    assertEquals(oneByte, twoBytes.flip().slice());
  }

  @Test
  void ofByteChannel_cancelsUpstreamWhenClosed() throws IOException {
    var subscription = new ToBeCancelledSubscription();
    var subscriber = ofByteChannel();
    subscriber.onSubscribe(subscription);
    var channel = getBody(subscriber);
    channel.close();
    assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
    subscription.assertCancelled();
  }

  @Test
  void ofByteChannel_cancelsUpstreamWhenInterrupted() {
    var subscription = new ToBeCancelledSubscription();
    var subscriber = ofByteChannel();
    subscriber.onSubscribe(subscription);
    var channel = getBody(subscriber);
    Thread.currentThread().interrupt();
    assertThrows(ClosedByInterruptException.class, () -> channel.read(ByteBuffer.allocate(1)));
    subscription.assertCancelled();
  }

  @Test
  void ofByteChannel_cancelsUpstreamIfClosedBeforeSubscribing() throws IOException {
    var subscription = new ToBeCancelledSubscription();
    var subscriber = ofByteChannel();
    getBody(subscriber).close();
    subscriber.onSubscribe(subscription);
    subscription.assertCancelled();
  }

  @Test
  void ofByteChannel_throwsUpstreamErrors() {
    var subscriber = ofByteChannel();
    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    subscriber.onError(new TestException());
    var channel = getBody(subscriber);
    var ex = assertThrows(IOException.class, () -> channel.read(ByteBuffer.allocate(1)));
    assertThrows(TestException.class, () -> { throw ex.getCause(); });
  }

  @Test
  void ofByteChannel_throwsUpstreamErrorsEvenIfThereIsData() {
    var subscriber = ofByteChannel();
    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    subscriber.onNext(List.of(US_ASCII.encode("Minecraft")));
    subscriber.onError(new TestException());
    var channel = getBody(subscriber);
    var ex = assertThrows(IOException.class, () -> channel.read(ByteBuffer.allocate(1)));
    assertThrows(TestException.class, () -> { throw ex.getCause(); });
  }

  @Test
  void ofByteChannel_handlesQueueOverflowGracefully() {
    var demand = new AtomicLong();
    var subscription = new ToBeCancelledSubscription() {
      @Override
      public void request(long n) {
        demand.set(n);
      }
    };
    var subscriber = ofByteChannel();
    subscriber.onSubscribe(subscription);
    ByteBuffer data = US_ASCII.encode("Such wow");
    LongStream.rangeClosed(1, demand.incrementAndGet()) // Add 1 more
        .forEach(i -> subscriber.onNext(List.of(data.duplicate())));
    subscription.assertCancelled();
    var channel = getBody(subscriber);
    var ex = assertThrows(IOException.class, () -> channel.read(ByteBuffer.allocate(1)));
    assertThrows(IllegalStateException.class, () -> { throw ex.getCause(); });
  }

  @Test
  void ofReader_isCompleted() {
    var subscriber = ofReader(US_ASCII);
    assertNotNull(toFuture(subscriber).getNow(null));
  }

  @Test
  void ofReader_decodesInGivenCharset() throws IOException {
    var str = "جافا ههه";
    var subscriber = ofReader(UTF_8);
    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    subscriber.onNext(List.of(UTF_8.encode(str)));
    subscriber.onComplete();
    var read = new BufferedReader(getBody(subscriber)).readLine();
    assertEquals(str, read);
  }

  @Test
  void fromAsyncSubscriber_completionDependsOnGivenFinisher() {
    var completedSubscriber = ofByteChannel();
    var subscriber1 = fromAsyncSubscriber(completedSubscriber,
        s -> new CompletableFuture<>()); // Finisher doesn't complete
    assertFalse(toFuture(subscriber1).isDone());
    var uncompletedSubscriber = ofString(US_ASCII);
    var subscriber2 = fromAsyncSubscriber(uncompletedSubscriber,
        s -> CompletableFuture.completedFuture("Baby yoda")); // Finisher completes
    assertEquals("Baby yoda", toFuture(subscriber2).getNow(null));
  }

  @Test
  void fromAsyncSubscriber_forwardsBodyToDownstream() {
    int buffSize = 100;
    int buffsPerList = 5;
    int listCount = 10;
    var str = rndAlpha(buffSize * buffsPerList * listCount);
    var publisher = asciiPublisherOf(str, buffSize, buffsPerList);
    var subscriber = fromAsyncSubscriber(ofString(US_ASCII), MoreBodySubscribersTest::toFuture);
    publisher.subscribe(subscriber);
    assertEquals(str, getBody(subscriber));
  }

  @Test
  void fromAsyncSubscriber_downstreamErrorsAreRelayed() {
    var subscription = new ToBeCancelledSubscription();
    var badDownstream = new ToBeOnErroredSubscriber() {
      @Override public void onNext(List<ByteBuffer> item) {
        throw new TestException();
      }
    };
    var subscriber = fromAsyncSubscriber(badDownstream, s -> s.completion);
    subscriber.onSubscribe(subscription);
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{'a'})));
    subscriber.onComplete(); // Shouldn't be normally completed
    subscription.assertCancelled();
    badDownstream.assertOnErrored(TestException.class);
  }

  @Test
  void fromAsyncSubscriber_deferredCancellationAfterDownstreamError() {
    var badDownstream = new ToBeOnErroredSubscriber() {
      @Override public void onSubscribe(Subscription subscription) {
        subscription.request(5); // Trigger onNext
      }
      @Override public void onNext(List<ByteBuffer> item) {
        throw new TestException();
      }
    };
    // Doesn't detect cancellation promptly
    var laggySubscription = new ToBeCancelledSubscription() {
      Subscriber<List<ByteBuffer>> subscriber;
      void apply(Subscriber<List<ByteBuffer>> subscriber) {
        this.subscriber = subscriber;
        subscriber.onSubscribe(this);
      }
      @Override public void request(long n) {
        // produce n elements only once
        var s = subscriber;
        subscriber = null;
        if (s != null) {
          for (int i = 0; i < n; i++) {
            s.onNext(List.of(ByteBuffer.wrap(new byte[]{'a'})));
          }
          s.onComplete();
        }
      }
    };
    var subscriber = fromAsyncSubscriber(badDownstream, s -> s.completion);
    laggySubscription.apply(subscriber);
    laggySubscription.assertCancelled();
    badDownstream.assertOnErrored(TestException.class);
  }

  @Test
  void withReadTimeout_infiniteTimeout() {
    int buffSize = 100;
    int buffsPerList = 5;
    int listCount = 5;
    var body = rndAlpha(buffSize * buffsPerList * listCount);
    var publisher = asciiPublisherOf(body, buffSize, buffsPerList);
    var baseSubscriber = ofString(US_ASCII);
    var timeoutSubscriber = withReadTimeout(baseSubscriber, Duration.ofMillis(Long.MAX_VALUE));
    publisher.subscribe(timeoutSubscriber);
    assertEquals(body, getBody(timeoutSubscriber));
  }

  @Test
  void withReadTimeout_timeoutAfterOnSubscribe() {
    var timeoutMillis = 50L;
    var baseSubscriber = ofString(US_ASCII);
    var timeoutSubscriber = withReadTimeout(baseSubscriber, Duration.ofMillis(timeoutMillis));
    timeoutSubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    assertReadTimeout(timeoutSubscriber, 0, timeoutMillis);
  }

  @Test
  void withReadTimeout_timeoutAfterOnNext() {
    var timeoutMillis = 100L;
    var baseSubscriber = ofString(US_ASCII);
    var timeoutSubscriber = withReadTimeout(baseSubscriber, Duration.ofMillis(timeoutMillis));
    timeoutSubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    timeoutSubscriber.onNext(List.of(ByteBuffer.allocate(1))); // itemIndex++ -> 1
    timeoutSubscriber.onNext(List.of(ByteBuffer.allocate(1))); // itemIndex++ -> 2
    assertReadTimeout(timeoutSubscriber, 2, timeoutMillis);
  }

  @Test
  void withReadTimeout_timeoutAfterOnNext_customScheduler() {
    var timeoutMillis = 100L;
    var baseSubscriber = ofString(US_ASCII);
    var timeoutSubscriber = withReadTimeout(
        baseSubscriber, Duration.ofMillis(timeoutMillis), scheduler);
    timeoutSubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    timeoutSubscriber.onNext(List.of(ByteBuffer.allocate(1))); // itemIndex++ -> 1
    timeoutSubscriber.onNext(List.of(ByteBuffer.allocate(1))); // itemIndex++ -> 2
    assertReadTimeout(timeoutSubscriber, 2, timeoutMillis);
  }

  @Test
  void withReadTimeout_racyOnError() {
    var timeoutMillis = 100L;
    var baseSubscriber = new TestSubscriber<List<ByteBuffer>>();
    var timeoutSubscriber = withReadTimeout(
        BodySubscribers.fromSubscriber(baseSubscriber), Duration.ofMillis(timeoutMillis));
    timeoutSubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    timeoutSubscriber.onNext(List.of(ByteBuffer.allocate(1))); // itemIndex++ -> 1
    try {
      Thread.sleep(timeoutMillis);
      timeoutSubscriber.onError(new TestException());
      Thread.sleep(10);
    } catch (InterruptedException e) {
      fail(e);
    }
    baseSubscriber.awaitError();
    assertEquals(1, baseSubscriber.errors);
    var e = baseSubscriber.lastError;
    assertTrue(
        e instanceof TestException || e instanceof HttpReadTimeoutException, e.toString());
    if (e instanceof HttpReadTimeoutException) {
      assertEquals("read [1] timed out after 100 ms", e.getMessage());
    }
  }

  @Test
  void withReadTimeout_subscriptionIsCancelledOnTimeout() {
    var timeoutMillis = 50L;
    var baseSubscriber = new TestSubscriber<List<ByteBuffer>>();
    var timeoutSubscriber = withReadTimeout(
        BodySubscribers.fromSubscriber(baseSubscriber), Duration.ofMillis(timeoutMillis));
    var subscription = new ToBeCancelledSubscription();
    timeoutSubscriber.onSubscribe(subscription);
    baseSubscriber.awaitError();
    assertEquals("read [0] timed out after 50 ms", baseSubscriber.lastError.getMessage());
    subscription.assertCancelled();
  }

  @Test
  void withReadTimeout_handlesOverflowGracefully() {
    var requestCount = 5L;
    var baseSubscriber = new TestSubscriber<List<ByteBuffer>>();
    var timeoutSubscriber = withReadTimeout(
        BodySubscribers.fromSubscriber(baseSubscriber), Duration.ofMillis(Long.MAX_VALUE));
    var subscription = new ToBeCancelledSubscription();
    baseSubscriber.request = 0L; // request manually
    timeoutSubscriber.onSubscribe(subscription);
    baseSubscriber.awaitSubscribe();
    baseSubscriber.subscription.request(requestCount);
    for (int i = 0; i < requestCount + 2; i++) {
      timeoutSubscriber.onNext(List.of(ByteBuffer.allocate(1)));
    }
    timeoutSubscriber.onComplete();
    baseSubscriber.awaitError();
    assertEquals(requestCount, baseSubscriber.nexts);
    assertEquals(1, baseSubscriber.errors);
    assertEquals(0, baseSubscriber.completes);
    assertSame(IllegalStateException.class, baseSubscriber.lastError.getClass());
    subscription.assertCancelled();
  }

  @Test
  void withReadTimeout_rethrowsRejectionFromRequest() {
    var superBusyScheduler = new ScheduledThreadPoolExecutor(0) {
      @Override
      public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        throw new RejectedExecutionException();
      }
    };
    var baseSubscriber = new TestSubscriber<>();
    var timeoutSubscriber = withReadTimeout(
        BodySubscribers.fromSubscriber(baseSubscriber),
        Duration.ofSeconds(Long.MAX_VALUE),
        superBusyScheduler);
    var subscription = new ToBeCancelledSubscription();
    baseSubscriber.request = 0L; // request manually
    timeoutSubscriber.onSubscribe(subscription);
    baseSubscriber.awaitSubscribe();
    assertThrows(RejectedExecutionException.class, () -> baseSubscriber.subscription.request(1L));
    subscription.assertCancelled();
  }

  @Test
  void withReadTimeout_handlesRejectionFromOnNextGracefully() {
    var taskRef = new AtomicReference<ScheduledFuture<?>>();
    var busyScheduler = new ScheduledThreadPoolExecutor(0) {
      final AtomicBoolean taken = new AtomicBoolean();
      @Override
      public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        if (taken.getAndSet(true)) {
          throw new RejectedExecutionException();
        }
        var task = scheduler.schedule(command, delay, unit);
        taskRef.set(task);
        return task;
      }
    };
    var baseSubscriber = new TestSubscriber<>();
    var timeoutSubscriber = withReadTimeout(
        BodySubscribers.fromSubscriber(baseSubscriber),
        Duration.ofSeconds(Long.MAX_VALUE),
        busyScheduler);
    var subscription = new ToBeCancelledSubscription();
    baseSubscriber.request = 2L; // request 2 to trigger schedule from onNext on second item
    timeoutSubscriber.onSubscribe(subscription);
    baseSubscriber.request = 0L; // disable requests
    timeoutSubscriber.onNext(List.of(ByteBuffer.allocate(1)));
    timeoutSubscriber.onNext(List.of(ByteBuffer.allocate(1)));
    timeoutSubscriber.onComplete();
    baseSubscriber.awaitComplete();
    assertEquals(1, baseSubscriber.errors);
    assertEquals(0, baseSubscriber.completes);
    assertEquals(0, baseSubscriber.nexts);
    assertTrue(baseSubscriber.lastError instanceof RejectedExecutionException);
    subscription.assertCancelled();
    assertNotNull(taskRef.get());
    assertTrue(taskRef.get().isCancelled());
  }

  @Test
  void withReadTimeout_illegalTimeout() {
    var zero = Duration.ofSeconds(0);
    var negative = Duration.ofSeconds(-1);
    assertThrows(IllegalArgumentException.class, () -> withReadTimeout(discarding(), zero));
    assertThrows(IllegalArgumentException.class, () -> withReadTimeout(discarding(), negative));
    assertThrows(IllegalArgumentException.class,
        () -> withReadTimeout(discarding(), zero, scheduler));
    assertThrows(IllegalArgumentException.class,
        () -> withReadTimeout(discarding(), zero, scheduler));
  }

  @Test
  void ofObject_stringBody() {
    var sample = "sample string";
    var publisher = asciiPublisherOf(sample, sample.length(), 1);
    var subscriber = ofObject(TypeRef.from(String.class), null);
    publisher.subscribe(subscriber);
    assertEquals(sample, getBody(subscriber));
  }

  @Test
  void ofDeferredObject_stringBody() {
    var sample = "sample string";
    var publisher = asciiPublisherOf(sample, sample.length(), 1);
    var subscriber = ofDeferredObject(TypeRef.from(String.class), MediaType.of("text", "plain"));
    assertNotNull(toFuture(subscriber).getNow(null));
    publisher.subscribe(subscriber);
    var supplier = getBody(subscriber);
    assertEquals(sample, supplier.get());
  }

  @Test
  void ofObject_ofDeferredObject_unsupported() {
    class Misplaced {}
    assertThrows(UnsupportedOperationException.class,
        () -> ofObject(TypeRef.from(Misplaced.class), null));
    assertThrows(UnsupportedOperationException.class,
        () -> ofObject(TypeRef.from(String.class), MediaType.parse("application/json")));
    assertThrows(UnsupportedOperationException.class,
        () -> ofDeferredObject(TypeRef.from(Misplaced.class), null));
    assertThrows(UnsupportedOperationException.class,
        () -> ofDeferredObject(TypeRef.from(String.class), MediaType.parse("application/json")));
  }

  private Publisher<List<ByteBuffer>> asciiPublisherOf(
      String str, int buffSize, int buffsPerList) {
    return FlowAdapters.toFlowPublisher(new AsyncIterablePublisher<>(
        iterableOf(US_ASCII.encode(str), buffSize, buffsPerList), executor));
  }

  private static void assertReadTimeout(
      BodySubscriber<?> subscriber, int index, long timeoutMillis) {
    var ex = assertThrows(ExecutionException.class, toFuture(subscriber)::get);
    var cause = ex.getCause();
    assertSame(HttpReadTimeoutException.class, cause.getClass());
    assertEquals(
        String.format("read [%d] timed out after %d ms", index, timeoutMillis), cause.getMessage());
  }

  private static <T> CompletableFuture<T> toFuture(BodySubscriber<T> s) {
    return s.getBody().toCompletableFuture();
  }

  private static <T> T getBody(BodySubscriber<T> s) {
    return toFuture(s).join();
  }

  private static String rndAlpha(int len) {
    return ThreadLocalRandom.current()
        .ints('a', 'z' + 1)
        .limit(len)
        .collect(StringBuilder::new, (sb, i) -> sb.append((char) i), StringBuilder::append)
        .toString();
  }

  private static Iterable<List<ByteBuffer>> iterableOf(
      ByteBuffer buffer, int buffSize, int buffsPerList) {
    return () -> new BuffListIterator(buffer, buffSize, buffsPerList);
  }

  /**
   * A subscription that is expected to be cancelled.
   */
  private static class ToBeCancelledSubscription implements Subscription {

    private volatile boolean cancelled;

    ToBeCancelledSubscription() {}

    @Override
    public void request(long n) {}

    @Override
    public void cancel() {
      cancelled = true;
    }

    void assertCancelled() {
      assertTrue(cancelled, "Subscription not cancelled");
    }
  }

  private static class ToBeOnErroredSubscriber implements Subscriber<List<ByteBuffer>> {

    final CompletableFuture<Void> completion;

    ToBeOnErroredSubscriber() {
      completion = new CompletableFuture<>();
    }

    @Override
    public void onSubscribe(Subscription subscription) {}

    @Override
    public void onNext(List<ByteBuffer> item) {}

    @Override
    public void onError(Throwable throwable) {
      if (!completion.completeExceptionally(throwable)) {
        fail("Multiple error completions");
      }
    }

    @Override
    public void onComplete() {
      fail("Being completed normally");
    }

    void assertOnErrored(Class<? extends Throwable> clz) {
      CompletionException e = assertThrows(CompletionException.class,
          () -> completion.getNow(null));
      assertEquals(clz, e.getCause().getClass());
    }
  }
}
