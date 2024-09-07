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

package com.github.mizosoft.methanol.internal.extensions;

import static com.github.mizosoft.methanol.testing.TestUtils.awaitUnchecked;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.testing.ByteBufferCollector;
import com.github.mizosoft.methanol.testing.ByteBufferListIterator;
import com.github.mizosoft.methanol.testing.ExecutorContext;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorParameterizedTest;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.RepeatArguments;
import com.github.mizosoft.methanol.testing.SubmittablePublisher;
import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.TestSubscriberContext;
import com.github.mizosoft.methanol.testing.TestSubscriberExtension;
import com.github.mizosoft.methanol.testing.TestUtils;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({ExecutorExtension.class, TestSubscriberExtension.class})
class PublisherBodySubscriberTest {
  private TestSubscriberContext subscriberContext;

  @BeforeEach
  void setUp(TestSubscriberContext subscriberFactory) {
    this.subscriberContext = subscriberFactory;
  }

  @ExecutorParameterizedTest
  void publishBeforeSubscribingDownstream(Executor executor) {
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var subscriber = new PublisherBodySubscriber();
    var data = data();
    upstream.subscribe(subscriber);
    upstream.submitAll(tokenize(data.duplicate()));
    upstream.close();

    var downstream = subscriberContext.<List<ByteBuffer>>createSubscriber();
    publisherOf(subscriber).subscribe(downstream);
    assertThat(ByteBufferCollector.collectMulti(downstream.pollAll())).isEqualTo(data);
  }

  @ExecutorParameterizedTest
  void publishAfterSubscribingDownstream(Executor executor) {
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var subscriber = new PublisherBodySubscriber();
    upstream.subscribe(subscriber);

    var downstream = subscriberContext.<List<ByteBuffer>>createSubscriber();
    publisherOf(subscriber).subscribe(downstream);

    var data = data();
    upstream.submitAll(tokenize(data.duplicate()));
    upstream.close();
    assertThat(ByteBufferCollector.collectMulti(downstream.pollAll())).isEqualTo(data);
  }

  @ExecutorParameterizedTest
  void onCompleteBeforeSubscribingDownstream(Executor executor) {
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var subscriber = new PublisherBodySubscriber();
    upstream.subscribe(subscriber);
    upstream.close();

    var downstream = subscriberContext.<List<ByteBuffer>>createSubscriber();
    publisherOf(subscriber).subscribe(downstream);
    downstream.awaitCompletion();
    assertThat(downstream.nextCount()).isZero();
    assertThat(downstream.completionCount()).isOne();
  }

  @ExecutorParameterizedTest
  void onErrorBeforeSubscribingDownstream(Executor executor) {
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var subscriber = new PublisherBodySubscriber();
    upstream.subscribe(subscriber);
    upstream.closeExceptionally(new TestException());

    var downstream = subscriberContext.<List<ByteBuffer>>createSubscriber();
    publisherOf(subscriber).subscribe(downstream);
    assertThat(downstream.awaitError()).isInstanceOf(TestException.class);
    assertThat(downstream.nextCount()).isZero();
    assertThat(downstream.errorCount()).isOne();
  }

  @ExecutorParameterizedTest
  void multipleSubscribers(Executor executor) {
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var subscriber = new PublisherBodySubscriber();
    upstream.subscribe(subscriber);
    publisherOf(subscriber).subscribe(subscriberContext.createSubscriber());

    var secondDownstream = subscriberContext.createSubscriber();
    publisherOf(subscriber).subscribe(secondDownstream);
    assertThat(secondDownstream.awaitError()).isInstanceOf(IllegalStateException.class);
  }

  @ExecutorParameterizedTest
  void throwFromOnNext(Executor executor) {
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var subscriber = new PublisherBodySubscriber();
    upstream.subscribe(subscriber);
    upstream.submit(List.of(ByteBuffer.allocate(0)));

    var downstream = subscriberContext.<List<ByteBuffer>>createSubscriber().throwOnNext(true);
    publisherOf(subscriber).subscribe(downstream);
    assertThat(downstream.awaitError()).isInstanceOf(TestException.class);
    assertThat(downstream.nextCount()).isOne();
    upstream.firstSubscription().awaitAbort(); // The subscription is cancelled.

    // These aren't forwarded.
    subscriber.onNext(List.of(ByteBuffer.allocate(0)));
    subscriber.onComplete();
    assertThat(downstream.nextCount()).isOne();
    assertThat(downstream.completionCount()).isZero();
  }

  @ExecutorParameterizedTest
  @RepeatArguments(10)
  void contendedPublish(Executor executor, ExecutorContext executorContext) throws Exception {
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var subscriber = new PublisherBodySubscriber();
    var downstream = subscriberContext.<List<ByteBuffer>>createSubscriber();
    var arrival = new CyclicBarrier(2);
    var threadPool = executorContext.createExecutor(ExecutorType.CACHED_POOL);
    var data = data();
    CompletableFuture.allOf(
            CompletableFuture.runAsync(
                () -> {
                  // Subscribe first so the publisher becomes available.
                  upstream.subscribe(subscriber);

                  awaitUnchecked(arrival);
                  upstream.submitAll(tokenize(data.duplicate()));
                  upstream.close();
                },
                threadPool),
            CompletableFuture.runAsync(
                () -> {
                  awaitUnchecked(arrival);
                  publisherOf(subscriber).subscribe(downstream);
                },
                threadPool))
        .get();
    assertThat(ByteBufferCollector.collectMulti(downstream.pollAll())).isEqualTo(data);
  }

  private static ByteBuffer data() {
    var data = ByteBuffer.allocate(1024);
    var template = UTF_8.encode("Pikachu");
    int templateSize = template.remaining();
    while (data.hasRemaining()) {
      template.rewind().limit(Math.min(data.remaining(), templateSize));
      data.put(template);
    }
    return data.flip();
  }

  private static Iterable<List<ByteBuffer>> tokenize(ByteBuffer buffer) {
    return () -> new ByteBufferListIterator(buffer, 4, TestUtils.BUFFERS_PER_LIST);
  }

  public static Publisher<List<ByteBuffer>> publisherOf(PublisherBodySubscriber subscriber) {
    try {
      return subscriber
          .getBody()
          .toCompletableFuture()
          .get(TestUtils.TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      Throwable cause = e;
      if (cause instanceof ExecutionException) {
        cause = cause.getCause();
      }
      throw new CompletionException(cause);
    }
  }
}
