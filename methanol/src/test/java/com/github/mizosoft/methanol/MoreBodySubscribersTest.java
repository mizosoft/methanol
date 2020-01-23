/*
 * MIT License
 *
 * Copyright (c) 2019 Moataz Abdelnasser
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

import static com.github.mizosoft.methanol.MoreBodySubscribers.ofByteChannel;
import static com.github.mizosoft.methanol.MoreBodySubscribers.ofReader;
import static com.github.mizosoft.methanol.testing.TestUtils.awaitUninterruptedly;
import static com.github.mizosoft.methanol.testing.TestUtils.encodeAscii;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.testing.BuffListIterator;
import com.github.mizosoft.methanol.testing.TestException;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.reactivestreams.FlowAdapters;
import org.reactivestreams.example.unicast.AsyncIterablePublisher;

class MoreBodySubscribersTest {

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
    subscriber.onNext(List.of(encodeAscii("Minecraft")));
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
    ByteBuffer data = encodeAscii("Such wow");
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

  private static Publisher<List<ByteBuffer>> asciiPublisherOf(
      String str, int buffSize, int buffsPerList) {
    return FlowAdapters.toFlowPublisher(new AsyncIterablePublisher<>(
        iterableOf(encodeAscii(str), buffSize, buffsPerList), ForkJoinPool.commonPool()));
  }

  private static Iterable<List<ByteBuffer>> iterableOf(
      ByteBuffer buffer, int buffSize, int buffsPerList) {
    return () -> new BuffListIterator(buffer, buffSize, buffsPerList);
  }

  /**
   * A subscription that is expected to be cancelled.
   */
  private static class ToBeCancelledSubscription implements Subscription {

    private final AtomicBoolean cancelled;

    ToBeCancelledSubscription() {
      cancelled = new AtomicBoolean();
    }

    @Override
    public void request(long n) {
    }

    @Override
    public void cancel() {
      cancelled.set(true);
    }

    void assertCancelled() {
      assertTrue(cancelled.get(), "Subscription not cancelled");
    }
  }
}