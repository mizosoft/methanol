/*
 * Copyright (c) 2024 Moataz Hussein
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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.function.Unchecked;
import com.github.mizosoft.methanol.testing.ByteBufferListIterator;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorParameterizedTest;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorSpec;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.FailingPublisher;
import com.github.mizosoft.methanol.testing.SubmittablePublisher;
import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.TestSubscription;
import com.github.mizosoft.methanol.testing.TestUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@SuppressWarnings("resource")
@ExtendWith(ExecutorExtension.class)
class ByteChannelBodySubscriberTest {
  @Test
  void subscriberIsCompleteImmediately() {
    assertThat(new ByteChannelBodySubscriber().getBody()).isCompleted();
  }

  @ExecutorParameterizedTest
  void readSmallBody(Executor executor) throws IOException {
    testReadingBody("To be or not to be, that is the question", executor);
  }

  @ExecutorParameterizedTest
  void readLargeBody(Executor executor) throws IOException {
    testReadingBody("To be or not to be, that is the question".repeat(1000), executor);
  }

  void testReadingBody(String body, Executor executor) throws IOException {
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(executor);
    var subscriber = new ByteChannelBodySubscriber();
    upstream.subscribe(subscriber);
    upstream.submitAll(
        () ->
            new ByteBufferListIterator(
                UTF_8.encode(body), Utils.BUFFER_SIZE, TestUtils.BUFFERS_PER_LIST));
    upstream.close();
    assertThat(readString(subscriber.getBody().toCompletableFuture().join()))
        .hasSize(body.length())
        .isEqualTo(body);
  }

  @Test
  @ExecutorSpec(ExecutorType.CACHED_POOL)
  void channelIsInterruptible(Executor executor) throws InterruptedException {
    var subscriber = new ByteChannelBodySubscriber();
    var channel = subscriber.getBody().toCompletableFuture().join();
    assertThat(channel).isInstanceOf(InterruptibleChannel.class);

    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    var gotReaderThread = new CountDownLatch(1);
    var readerThread = new AtomicReference<Thread>();
    var readFuture =
        CompletableFuture.supplyAsync(
            Unchecked.supplier(
                () -> {
                  readerThread.set(Thread.currentThread());
                  gotReaderThread.countDown();
                  return channel.read(ByteBuffer.allocate(1));
                }),
            executor);
    gotReaderThread.await();
    readerThread.get().interrupt();
    assertThat(readFuture)
        .failsWithin(Duration.ofSeconds(TestUtils.TIMEOUT_SECONDS))
        .withThrowableOfType(ExecutionException.class)
        .withCauseInstanceOf(ClosedByInterruptException.class);
  }

  @Test
  @ExecutorSpec(ExecutorType.CACHED_POOL)
  void channelBlocksForAtLeastOneByte(Executor executor) throws IOException {
    var oneByte = ByteBuffer.wrap(new byte[] {'b'});
    var subscriber = new ByteChannelBodySubscriber();
    executor.execute(
        () -> {
          subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
          subscriber.onNext(List.of(oneByte.duplicate()));
        });

    var buffer = ByteBuffer.allocate(2);
    var channel = subscriber.getBody().toCompletableFuture().join();
    channel.read(buffer);
    assertThat(buffer.flip()).isEqualTo(oneByte);
  }

  @Test
  void cancelsUpstreamWhenClosed() throws IOException {
    var subscriber = new ByteChannelBodySubscriber();
    var subscription = new TestSubscription();
    subscriber.onSubscribe(subscription);

    var channel = subscriber.getBody().toCompletableFuture().join();
    channel.close();
    assertThat(subscription.isCancelled()).isTrue();
  }

  @Test
  void throwsFromReadsWhenClosed() throws IOException {
    var subscriber = new ByteChannelBodySubscriber();
    var channel = subscriber.getBody().toCompletableFuture().join();
    channel.close();
    assertThatExceptionOfType(ClosedChannelException.class)
        .isThrownBy(() -> channel.read(ByteBuffer.allocate(1)));
  }

  @Test
  void cancelsUpstreamWhenReaderIsInterrupted() {
    var subscription = new TestSubscription();
    var subscriber = new ByteChannelBodySubscriber();
    subscriber.onSubscribe(subscription);
    Thread.currentThread().interrupt();

    var channel = subscriber.getBody().toCompletableFuture().join();
    assertThatExceptionOfType(ClosedByInterruptException.class)
        .isThrownBy(() -> channel.read(ByteBuffer.allocate(1)));
    assertThat(subscription.isCancelled()).isTrue();
  }

  @Test
  void cancelsUpstreamIfClosedBeforeSubscribing() throws IOException {
    var subscriber = new ByteChannelBodySubscriber();
    subscriber.getBody().toCompletableFuture().join().close();

    var subscription = new TestSubscription();
    subscriber.onSubscribe(subscription);
    assertThat(subscription.isCancelled()).isTrue();
  }

  @Test
  void throwsUpstreamErrors() {
    var subscriber = new ByteChannelBodySubscriber();
    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    subscriber.onError(new TestException());

    var channel = subscriber.getBody().toCompletableFuture().join();
    assertThatIOException()
        .isThrownBy(() -> channel.read(ByteBuffer.allocate(1)))
        .withCauseInstanceOf(TestException.class);
  }

  @Test
  void throwsUpstreamErrorsEvenIfThereIsData() {
    var subscriber = new ByteChannelBodySubscriber();
    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {'a'})));
    subscriber.onError(new TestException());

    var channel = subscriber.getBody().toCompletableFuture().join();
    assertThatIOException()
        .isThrownBy(() -> channel.read(ByteBuffer.allocate(1)))
        .withCauseInstanceOf(TestException.class);
  }

  @Test
  @ExecutorSpec(ExecutorType.CACHED_POOL)
  void throwsUpstreamErrorsWhenPotentiallyBlocking(Executor executor) {
    var subscriber = new ByteChannelBodySubscriber();
    var channel = subscriber.getBody().toCompletableFuture().join();
    executor.execute(
        Unchecked.runnable(
            () -> {
              Thread.sleep(5); // Make it more likely that reader will be blocked.
              new FailingPublisher<List<ByteBuffer>>(TestException::new).subscribe(subscriber);
            }));
    assertThatThrownBy(() -> channel.read(ByteBuffer.allocate(1)))
        .isInstanceOf(IOException.class)
        .hasCauseInstanceOf(TestException.class);
  }

  @Test
  void handlesQueueOverflow() {
    var subscriber = new ByteChannelBodySubscriber();
    var subscription = new TestSubscription();
    subscriber.onSubscribe(subscription);

    int request = (int) subscription.awaitRequest();
    var buffer = UTF_8.encode("abc");
    for (int i = 0; i < request; i++) {
      subscriber.onNext(List.of(buffer.duplicate()));
    }

    // Add 1 item more than requested.
    subscriber.onNext(List.of(buffer.duplicate()));
    assertThat(subscription.isCancelled()).isTrue();

    var channel = subscriber.getBody().toCompletableFuture().join();
    assertThatIOException()
        .isThrownBy(() -> channel.read(ByteBuffer.allocate(1)))
        .withCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  void receiveErrorAfterClosure() throws IOException {
    var subscriber = new ByteChannelBodySubscriber();
    var channel = subscriber.getBody().toCompletableFuture().join();
    channel.close();
    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    subscriber.onError(new TestException());
    assertThatThrownBy(() -> channel.read(ByteBuffer.allocate(1)))
        .isInstanceOf(ClosedChannelException.class);
  }

  private static String readString(ReadableByteChannel channel) throws IOException {
    var output = new ByteArrayOutputStream();
    var bytes = new byte[8096];
    var buffer = ByteBuffer.wrap(bytes);
    while (channel.read(buffer.clear()) > 0) {
      output.write(bytes, 0, buffer.position());
    }
    return output.toString(UTF_8);
  }
}
