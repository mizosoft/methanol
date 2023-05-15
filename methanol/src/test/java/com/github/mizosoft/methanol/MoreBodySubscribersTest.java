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

package com.github.mizosoft.methanol;

import static com.github.mizosoft.methanol.MoreBodySubscribers.fromAsyncSubscriber;
import static com.github.mizosoft.methanol.MoreBodySubscribers.ofByteChannel;
import static com.github.mizosoft.methanol.MoreBodySubscribers.ofDeferredObject;
import static com.github.mizosoft.methanol.MoreBodySubscribers.ofObject;
import static com.github.mizosoft.methanol.MoreBodySubscribers.ofReader;
import static com.github.mizosoft.methanol.MoreBodySubscribers.withReadTimeout;
import static com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType.FIXED_POOL;
import static com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType.SCHEDULER;
import static com.github.mizosoft.methanol.testing.TestUtils.awaitUninterruptibly;
import static com.github.mizosoft.methanol.testing.TestUtils.toByteArray;
import static java.net.http.HttpResponse.BodySubscribers.discarding;
import static java.net.http.HttpResponse.BodySubscribers.fromSubscriber;
import static java.net.http.HttpResponse.BodySubscribers.ofString;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.testing.ByteBufferListIterator;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorConfig;
import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.TestSubscriber;
import com.github.mizosoft.methanol.testing.TestSubscription;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.reactivestreams.FlowAdapters;
import org.reactivestreams.example.unicast.AsyncIterablePublisher;

@Timeout(5)
@ExtendWith(ExecutorExtension.class)
class MoreBodySubscribersTest {
  @Test
  void ofByteChannel_isCompleted() {
    assertThat(ofByteChannel().getBody()).isCompleted();
  }

  @Test
  @ExecutorConfig(FIXED_POOL)
  void ofByteChannel_readsBody(Executor executor) throws IOException {
    int buffSize = 100;
    int buffsPerList = 5;
    int listCount = 5;
    var str = randomString(buffSize * buffsPerList * listCount);
    var publisher = publisherOf(str, buffSize, buffsPerList, executor);
    var subscriber = ofByteChannel();
    publisher.subscribe(subscriber);

    var outputBuffer = new ByteArrayOutputStream();
    var buffer = ByteBuffer.allocate(128);
    try (var channel = getBody(subscriber)) {
      while (channel.read(buffer.clear()) != -1) {
        outputBuffer.write(toByteArray(buffer.flip()));
      }
    }
    assertThat(outputBuffer.toByteArray()).asString(UTF_8).isEqualTo(str);
  }

  @Test
  @ExecutorConfig(FIXED_POOL)
  void ofByteChannel_isInterruptible(Executor executor) {
    var subscriber = ofByteChannel();
    var channel = getBody(subscriber);
    assertThat(channel).isInstanceOf(InterruptibleChannel.class);

    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    var readLatch = new CountDownLatch(1);
    var readerThread = Thread.currentThread();
    executor.execute(
        () -> {
          awaitUninterruptibly(readLatch);
          readerThread.interrupt();
        });

    assertThatExceptionOfType(ClosedByInterruptException.class)
        .isThrownBy(
            () -> {
              readLatch.countDown();
              channel.read(ByteBuffer.allocate(1));
            });
  }

  @Test
  @ExecutorConfig(FIXED_POOL)
  void ofByteChannel_blocksForAtLeastOneByte(Executor executor) throws IOException {
    var oneByte = ByteBuffer.wrap(new byte[] {'b'});
    var subscriber = ofByteChannel();
    var channel = getBody(subscriber);
    executor.execute(
        () -> {
          subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
          subscriber.onNext(List.of(oneByte.duplicate()));
        });

    var buffer = ByteBuffer.allocate(2);
    channel.read(buffer);
    assertThat(buffer.flip()).isEqualTo(oneByte);
  }

  @Test
  void ofByteChannel_cancelsUpstreamWhenClosed() throws IOException {
    var subscription = new TestSubscription();
    var subscriber = ofByteChannel();
    subscriber.onSubscribe(subscription);

    var channel = getBody(subscriber);
    channel.close();
    assertThat(subscription.isCancelled()).isTrue();
    assertThatExceptionOfType(ClosedChannelException.class)
        .isThrownBy(() -> channel.read(ByteBuffer.allocate(1)));
  }

  @Test
  void ofByteChannel_cancelsUpstreamWhenReaderIsInterrupted() {
    var subscription = new TestSubscription();
    var subscriber = ofByteChannel();
    subscriber.onSubscribe(subscription);

    var channel = getBody(subscriber);
    Thread.currentThread().interrupt();
    assertThatExceptionOfType(ClosedByInterruptException.class)
        .isThrownBy(() -> channel.read(ByteBuffer.allocate(1)));
    assertThat(subscription.isCancelled()).isTrue();
  }

  @Test
  void ofByteChannel_cancelsUpstreamIfClosedBeforeSubscribing() throws IOException {
    var subscriber = ofByteChannel();
    getBody(subscriber).close();

    var subscription = new TestSubscription();
    subscriber.onSubscribe(subscription);
    assertThat(subscription.isCancelled()).isTrue();
  }

  @Test
  void ofByteChannel_throwsUpstreamErrors() {
    var subscriber = ofByteChannel();
    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    subscriber.onError(new TestException());

    var channel = getBody(subscriber);
    assertThatIOException()
        .isThrownBy(() -> channel.read(ByteBuffer.allocate(1)))
        .withCauseInstanceOf(TestException.class);
  }

  @Test
  void ofByteChannel_throwsUpstreamErrorsEvenIfThereIsData() {
    var subscriber = ofByteChannel();
    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {'a'})));
    subscriber.onError(new TestException());

    var channel = getBody(subscriber);
    assertThatIOException()
        .isThrownBy(() -> channel.read(ByteBuffer.allocate(1)))
        .withCauseInstanceOf(TestException.class);
  }

  @Test
  void ofByteChannel_handlesQueueOverflowGracefully() {
    var subscription = new TestSubscription();
    var subscriber = ofByteChannel();
    subscriber.onSubscribe(subscription);

    int request = Math.toIntExact(subscription.awaitRequest());
    var buffer = UTF_8.encode("abc");
    for (int i = 0; i < request; i++) {
      subscriber.onNext(List.of(buffer.duplicate()));
    }
    // Add 1 more item than demanded
    subscriber.onNext(List.of(buffer.duplicate()));

    assertThat(subscription.isCancelled()).isTrue();

    var channel = getBody(subscriber);
    assertThatIOException()
        .isThrownBy(() -> channel.read(ByteBuffer.allocate(1)))
        .withCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  void ofReader_isCompleted() {
    assertThat(ofReader(UTF_8).getBody()).isCompleted();
  }

  @Test
  void ofReader_decodesInGivenCharset() throws IOException {
    var str = "لوريم إيبسوم";
    var subscriber = ofReader(UTF_8);
    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    subscriber.onNext(List.of(UTF_8.encode(str)));
    subscriber.onComplete();

    var reader = new BufferedReader(getBody(subscriber));
    assertThat(reader.readLine()).isEqualTo(str);
    assertThat(reader.readLine()).isNull();
  }

  @Test
  void fromAsyncSubscriber_completedToUncompleted() {
    var completedSubscriber = ofByteChannel();
    var uncompletedSubscriber =
        fromAsyncSubscriber(completedSubscriber, __ -> new CompletableFuture<>());
    assertThat(uncompletedSubscriber.getBody()).isNotCompleted();
  }

  @Test
  void fromAsyncSubscriber_uncompletedToCompleted() {
    var uncompletedSubscriber = ofString(US_ASCII);
    var subscriber =
        fromAsyncSubscriber(
            uncompletedSubscriber, __ -> CompletableFuture.completedFuture("Baby yoda"));
    assertThat(subscriber.getBody()).isCompletedWithValue("Baby yoda");
  }

  @Test
  @ExecutorConfig(FIXED_POOL)
  void withReadTimeout_infiniteTimeout(Executor executor) {
    int buffSize = 100;
    int buffsPerList = 5;
    int listCount = 5;
    var body = randomString(buffSize * buffsPerList * listCount);
    var publisher = publisherOf(body, buffSize, buffsPerList, executor);
    var timeoutSubscriber = withReadTimeout(ofString(UTF_8), Duration.ofMillis(Long.MAX_VALUE));
    publisher.subscribe(timeoutSubscriber);
    assertThat(timeoutSubscriber.getBody()).succeedsWithin(Duration.ofSeconds(20)).isEqualTo(body);
  }

  @Test
  void withReadTimeout_timeoutAfterOnSubscribe() {
    var timeoutMillis = 50L;
    var timeoutSubscriber = withReadTimeout(ofString(UTF_8), Duration.ofMillis(timeoutMillis));
    timeoutSubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    assertReadTimeout(timeoutSubscriber, 1, timeoutMillis);
  }

  @Test
  void withReadTimeout_timeoutAfterOnNext() {
    var timeoutMillis = 100L;
    var timeoutSubscriber = withReadTimeout(ofString(UTF_8), Duration.ofMillis(timeoutMillis));
    timeoutSubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    timeoutSubscriber.onNext(List.of(ByteBuffer.allocate(1)));
    timeoutSubscriber.onNext(List.of(ByteBuffer.allocate(1)));
    assertReadTimeout(timeoutSubscriber, 3, timeoutMillis);
  }

  @Test
  @ExecutorConfig(SCHEDULER)
  void withReadTimeout_timeoutAfterOnNextWithCustomScheduler(ScheduledExecutorService scheduler) {
    var timeoutMillis = 100L;
    var timeoutSubscriber =
        withReadTimeout(ofString(UTF_8), Duration.ofMillis(timeoutMillis), scheduler);
    timeoutSubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    timeoutSubscriber.onNext(List.of(ByteBuffer.allocate(1)));
    timeoutSubscriber.onNext(List.of(ByteBuffer.allocate(1)));
    assertReadTimeout(timeoutSubscriber, 3, timeoutMillis);
  }

  @Test
  void withReadTimeout_racyOnError() throws InterruptedException {
    var timeoutMillis = 20L;
    var baseSubscriber = new TestSubscriber<List<ByteBuffer>>();
    var timeoutSubscriber =
        withReadTimeout(fromSubscriber(baseSubscriber), Duration.ofMillis(timeoutMillis));
    timeoutSubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    timeoutSubscriber.onNext(List.of(ByteBuffer.allocate(1)));
    // Race with background timeout task on completing the subscriber exceptionally
    Thread.sleep(timeoutMillis);
    timeoutSubscriber.onError(new TestException());
    assertThat(baseSubscriber.awaitError())
        .isInstanceOfAny(TestException.class, HttpReadTimeoutException.class)
        .satisfies(
            t -> {
              if (t instanceof HttpReadTimeoutException) {
                assertThat(t).hasMessage("read [2] timed out after " + timeoutMillis + " ms");
              }
            });
  }

  @Test
  void withReadTimeout_subscriptionIsCancelledOnTimeout() {
    var timeoutMillis = 50L;
    var baseSubscriber = new TestSubscriber<List<ByteBuffer>>();
    var timeoutSubscriber =
        withReadTimeout(fromSubscriber(baseSubscriber), Duration.ofMillis(timeoutMillis));
    var subscription = new TestSubscription();
    timeoutSubscriber.onSubscribe(subscription);
    assertThat(baseSubscriber.awaitError()).isInstanceOf(HttpReadTimeoutException.class);
    subscription.awaitCancellation(); // Cancellation happens concurrently.
    assertReadTimeout(timeoutSubscriber, 1, timeoutMillis);
  }

  @Test
  void withReadTimeout_rethrowsRejectionFromSubscriptionRequest() {
    var superBusyScheduler =
        new ScheduledThreadPoolExecutor(0) {
          @Override
          public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            throw new RejectedExecutionException();
          }
        };
    var baseSubscriber = new TestSubscriber<>().autoRequest(0); // Request manually.
    var timeoutSubscriber =
        withReadTimeout(
            fromSubscriber(baseSubscriber), Duration.ofSeconds(Long.MAX_VALUE), superBusyScheduler);
    var subscription = new TestSubscription();
    timeoutSubscriber.onSubscribe(subscription);
    baseSubscriber.awaitSubscription();
    assertThatExceptionOfType(RejectedExecutionException.class)
        .isThrownBy(() -> baseSubscriber.requestItems(1));
    assertThat(subscription.isCancelled()).isTrue();
  }

  @Test
  @ExecutorConfig(SCHEDULER)
  void withReadTimeout_handlesRejectionFromOnNextGracefully(ScheduledExecutorService scheduler) {
    var scheduledFuture = new AtomicReference<ScheduledFuture<?>>();
    var busyScheduler =
        new ScheduledThreadPoolExecutor(0) {
          final AtomicBoolean firstSchedule = new AtomicBoolean();

          @Override
          public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            if (firstSchedule.getAndSet(true)) {
              throw new RejectedExecutionException();
            }
            var future = scheduler.schedule(command, delay, unit);
            scheduledFuture.set(future);
            return future;
          }
        };
    var baseSubscriber = new TestSubscriber<>().autoRequest(0);
    var timeoutSubscriber =
        withReadTimeout(
            fromSubscriber(baseSubscriber), Duration.ofSeconds(Long.MAX_VALUE), busyScheduler);
    var subscription = new TestSubscription();

    timeoutSubscriber.onSubscribe(subscription);

    // Request 2 items to trigger a second timeout task from onNext when it receives the first item
    baseSubscriber.requestItems(2);

    timeoutSubscriber.onNext(List.of(ByteBuffer.allocate(1))); // Second timeout is rejected
    timeoutSubscriber.onNext(List.of(ByteBuffer.allocate(1)));
    timeoutSubscriber.onComplete();
    assertThat(baseSubscriber.awaitError()).isInstanceOf(RejectedExecutionException.class);
    assertThat(baseSubscriber.nextCount()).isEqualTo(1); // First item is received
    assertThat(subscription.isCancelled()).isTrue();
    assertThat(scheduledFuture)
        .withFailMessage("First ScheduledFuture isn't cancelled after rejection")
        .hasValueMatching(ScheduledFuture::isCancelled);
  }

  @Test
  @ExecutorConfig(SCHEDULER)
  void withReadTimeout_illegalTimeout(ScheduledExecutorService scheduler) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> withReadTimeout(discarding(), Duration.ofSeconds(0)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> withReadTimeout(discarding(), Duration.ofSeconds(-1)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> withReadTimeout(discarding(), Duration.ofSeconds(0), scheduler));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> withReadTimeout(discarding(), Duration.ofSeconds(-1), scheduler));
  }

  @Test
  @ExecutorConfig(FIXED_POOL)
  void ofObject_stringBody(Executor executor) {
    var publisher = publisherOf("Pikachu", "Pikachu".length(), 1, executor);
    var subscriber = ofObject(TypeRef.from(String.class), MediaType.TEXT_PLAIN);
    publisher.subscribe(subscriber);
    assertThat(getBody(subscriber)).isEqualTo("Pikachu");
  }

  @Test
  @ExecutorConfig(FIXED_POOL)
  void ofDeferredObject_stringBody(Executor executor) {
    var publisher = publisherOf("Pikachu", "Pikachu".length(), 1, executor);
    var subscriber = ofDeferredObject(TypeRef.from(String.class), MediaType.parse("text/plain"));
    assertThat(subscriber.getBody()).isCompleted();

    publisher.subscribe(subscriber);

    var supplier = getBody(subscriber);
    assertThat(supplier.get()).isEqualTo("Pikachu");
  }

  @Test
  void ofObject_unsupported() {
    class InconvertibleType {}
    assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(() -> ofObject(TypeRef.from(InconvertibleType.class), null));
    assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(
            () -> ofObject(TypeRef.from(String.class), MediaType.parse("application/json")));
    assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(() -> ofDeferredObject(TypeRef.from(InconvertibleType.class), null));
    assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(
            () ->
                ofDeferredObject(TypeRef.from(String.class), MediaType.parse("application/json")));
  }

  private Publisher<List<ByteBuffer>> publisherOf(
      String str, int buffSize, int buffsPerList, Executor executor) {
    return FlowAdapters.toFlowPublisher(
        new AsyncIterablePublisher<>(
            iterableOf(UTF_8.encode(str), buffSize, buffsPerList), executor));
  }

  private static void assertReadTimeout(
      BodySubscriber<?> subscriber, int index, long timeoutMillis) {
    assertThat(subscriber.getBody())
        .failsWithin(Duration.ofSeconds(20))
        .withThrowableOfType(ExecutionException.class)
        .havingCause()
        .isInstanceOf(HttpReadTimeoutException.class)
        .withMessage("read [%d] timed out after %d ms", index, timeoutMillis);
  }

  private static <T> CompletableFuture<T> toFuture(BodySubscriber<T> s) {
    return s.getBody().toCompletableFuture();
  }

  private static <T> T getBody(BodySubscriber<T> s) {
    return toFuture(s).join();
  }

  private static String randomString(int len) {
    return ThreadLocalRandom.current()
        .ints('a', 'z' + 1)
        .limit(len)
        .collect(StringBuilder::new, (sb, i) -> sb.append((char) i), StringBuilder::append)
        .toString();
  }

  private static Iterable<List<ByteBuffer>> iterableOf(
      ByteBuffer buffer, int buffSize, int buffsPerList) {
    return () -> new ByteBufferListIterator(buffer, buffSize, buffsPerList);
  }
}
