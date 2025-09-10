/*
 * Copyright (c) 2025 Moataz Hussein
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

import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.from;

import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorSpec;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.TestSubscriberContext;
import com.github.mizosoft.methanol.testing.TestSubscriberExtension;
import com.github.mizosoft.methanol.testing.TestUtils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({TestSubscriberExtension.class, ExecutorExtension.class})
class WritableBodyPublisherTest {
  private TestSubscriberContext subscriberContext;

  @BeforeEach
  void setUp(TestSubscriberContext subscriberContext) {
    this.subscriberContext = subscriberContext;
  }

  @Test
  void contentLengthIsUndefined() {
    verifyThat(WritableBodyPublisher.create()).hasContentLength(-1);
  }

  @Test
  void writeWithByteChannel() throws IOException {
    var publisher = WritableBodyPublisher.create();
    try (var channel = publisher.byteChannel()) {
      channel.write(UTF_8.encode("I don't like sand"));
    }
    verifyThat(publisher).succeedsWith("I don't like sand");
  }

  @Test
  void writeWithOutputStream() throws IOException {
    var publisher = WritableBodyPublisher.create();
    try (var out = publisher.outputStream()) {
      out.write("I don't like sand".getBytes(UTF_8));
    }
    verifyThat(publisher).succeedsWith("I don't like sand");
  }

  @Test
  void flushAfterWritingWithOutputStream() throws IOException {
    var publisher = WritableBodyPublisher.create();
    var subscriber = subscriberContext.<ByteBuffer>createSubscriber().autoRequest(0);
    publisher.subscribe(subscriber);
    subscriber.requestItems(1);

    var out = publisher.outputStream();
    out.write('a');
    publisher.flush();
    assertThat(subscriber.peekAvailable())
        .singleElement()
        .returns(1, from(ByteBuffer::remaining))
        .returns((byte) 'a', from(buffer -> buffer.get(0)));
    out.write('b');
    out.close();

    subscriber.requestItems(1);
    subscriber.awaitCompletion();
    assertThat(subscriber.pollAll())
        .map(ByteBuffer::slice) // Make capacity one to compare equal with buffers below.
        .containsExactly(ByteBuffer.wrap(new byte[] {'a'}), ByteBuffer.wrap(new byte[] {'b'}));
  }

  @Test
  void flushFromOutputStreamAfterWritingWithOutputStream() throws IOException {
    var publisher = WritableBodyPublisher.create();
    var subscriber = subscriberContext.<ByteBuffer>createSubscriber().autoRequest(0);
    publisher.subscribe(subscriber);
    subscriber.requestItems(1);

    var out = publisher.outputStream();
    out.write('a');
    out.flush();
    assertThat(subscriber.peekAvailable())
        .singleElement()
        .returns(1, from(ByteBuffer::remaining))
        .returns((byte) 'a', from(buffer -> buffer.get(0)));
    out.write('b');
    out.close();

    subscriber.requestItems(1);
    subscriber.awaitCompletion();
    assertThat(subscriber.pollAll())
        .map(ByteBuffer::slice) // Make capacity one to compare equal with buffers below.
        .containsExactly(ByteBuffer.wrap(new byte[] {'a'}), ByteBuffer.wrap(new byte[] {'b'}));
  }

  @Test
  void flushAfterWritingWithByteChannel() throws IOException {
    var publisher = WritableBodyPublisher.create();
    var subscriber = subscriberContext.<ByteBuffer>createSubscriber().autoRequest(0);
    publisher.subscribe(subscriber);
    subscriber.requestItems(1);

    var out = publisher.byteChannel();
    out.write(ByteBuffer.wrap(new byte[] {'a'}));
    publisher.flush();
    assertThat(subscriber.peekAvailable())
        .singleElement()
        .returns(1, from(ByteBuffer::remaining))
        .returns((byte) 'a', from(buffer -> buffer.get(0)));
    out.write(ByteBuffer.wrap(new byte[] {'b'}));
    out.close();

    subscriber.requestItems(1);
    subscriber.awaitCompletion();
    assertThat(subscriber.pollAll())
        .map(ByteBuffer::slice) // Make capacity one to compare equal with buffers below.
        .containsExactly(ByteBuffer.wrap(new byte[] {'a'}), ByteBuffer.wrap(new byte[] {'b'}));
  }

  @Test
  void completeByClosingByteChannel() throws IOException {
    var publisher = WritableBodyPublisher.create();
    var subscriber = subscriberContext.<ByteBuffer>createSubscriber();
    publisher.subscribe(subscriber);
    publisher.byteChannel().close();
    assertThat(subscriber.completionCount()).isEqualTo(1);
    assertThat(publisher.byteChannel().isOpen()).isFalse();
  }

  @Test
  void completeByClosingOutputStream() throws IOException {
    var publisher = WritableBodyPublisher.create();
    var subscriber = subscriberContext.<ByteBuffer>createSubscriber();
    publisher.subscribe(subscriber);
    publisher.outputStream().close();
    assertThat(subscriber.completionCount()).isEqualTo(1);

    // The WritableByteChannel view is also closed
    assertThat(publisher.byteChannel().isOpen()).isFalse();
  }

  @Test
  void closeExceptionallyBeforeSubscribing() throws IOException {
    var publisher = WritableBodyPublisher.create();
    try (var out = publisher.outputStream()) {
      out.write(new byte[] {'a', 'b', 'c'}); // Shouldn't be signalled
      out.flush();
      publisher.closeExceptionally(new TestException());
    }

    var subscriber = subscriberContext.<ByteBuffer>createSubscriber();
    publisher.subscribe(subscriber);
    assertThat(subscriber.nextCount()).isZero();
    assertThat(subscriber.awaitError()).isInstanceOf(TestException.class);
  }

  @Test
  void subscribeTwice() {
    var publisher = WritableBodyPublisher.create();
    publisher.subscribe(subscriberContext.createSubscriber());

    var secondSubscriber = subscriberContext.<ByteBuffer>createSubscriber();
    publisher.subscribe(secondSubscriber);
    assertThat(secondSubscriber.awaitError()).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void writeAfterClosingFromByteChannel() {
    var publisher = WritableBodyPublisher.create();
    publisher.close();
    assertThatExceptionOfType(ClosedChannelException.class)
        .isThrownBy(() -> publisher.byteChannel().write(ByteBuffer.allocate(1)));
  }

  @Test
  void writeAfterClosingFromOutputStream() {
    var publisher = WritableBodyPublisher.create();
    publisher.close();
    assertThatExceptionOfType(ClosedChannelException.class)
        .isThrownBy(() -> publisher.outputStream().write('a'));
  }

  @Test
  void flushAfterClosing() throws IOException {
    var publisher = WritableBodyPublisher.create();
    publisher.outputStream().write(new byte[] {'1', '2', '3'});
    publisher.close();
    assertThatIllegalStateException().isThrownBy(publisher::flush);
    assertThatIOException().isThrownBy(publisher.outputStream()::flush);
  }

  @Test
  void writeAfterFlush() throws IOException {
    var publisher = WritableBodyPublisher.create();
    try (var out = publisher.outputStream()) {
      out.write("abc".getBytes(UTF_8));
      out.flush();
      out.write("ABC".getBytes(UTF_8));
    }
    verifyThat(publisher).succeedsWith("abcABC");
  }

  @Test
  void writingInFrames() throws IOException {
    var publisher = WritableBodyPublisher.create(10);
    var subscriber = subscriberContext.<ByteBuffer>createSubscriber();
    publisher.subscribe(subscriber);
    try (var out = publisher.outputStream()) {
      out.write(new byte[10]);
      assertThat(subscriber.pollNext()).returns(10, from(ByteBuffer::remaining));

      out.write(new byte[9]);
      assertThat(subscriber.nextCount()).isOne(); // No new signals are received

      out.write('a');
      assertThat(subscriber.pollNext()).returns(10, from(ByteBuffer::remaining));
    }
  }

  @Test
  @ExecutorSpec(ExecutorType.CACHED_POOL)
  @Timeout(TestUtils.SLOW_TIMEOUT_SECONDS)
  void memoryConsumptionDoesNotExceedQuota(Executor executor)
      throws IOException, InterruptedException {
    var memoryTracker =
        new WritableBodyPublisher.QueuedMemoryTracker() {
          int queued;
          int maxQueued;

          @Override
          public void queued(int size) {
            queued += size;
            maxQueued = Math.max(maxQueued, queued);
          }

          @Override
          public void dequeued(int size) {
            queued -= size;
          }
        };

    int bufferSize = 64;
    int quota = WritableBodyPublisher.queuedMemoryQuotaFor(bufferSize);
    final int totalToWrite = 50 * quota;

    var publisher = WritableBodyPublisher.create(bufferSize, memoryTracker);
    var subscriber = subscriberContext.<ByteBuffer>createSubscriber().autoRequest(0);
    publisher.subscribe(subscriber);

    // Simulate a slow reader.
    var startReader = new CountDownLatch(1);
    var readerLaunched = new CountDownLatch(1);
    var readerDone = new CountDownLatch(1);
    executor.execute(
        () -> {
          try {
            readerLaunched.countDown();
            TestUtils.awaitUnchecked(startReader);
            int totalRead = 0;
            while (totalRead < totalToWrite) {
              subscriber.requestItems(1);
              var buffer = subscriber.pollNext();
              totalRead += buffer.remaining();
              try {
                Thread.sleep(2);
              } catch (InterruptedException e) {
                throw new RuntimeException(e);
              }
            }
            subscriber.awaitCompletion();
          } finally {
            readerDone.countDown();
          }
        });

    readerLaunched.await();
    startReader.countDown();

    // Alternate on buffer sizes.
    int[] bufferSizes = {bufferSize / 2, bufferSize / 2, bufferSize * 2};
    int bufferSizesIndex = 0;

    int totalWritten = 0;
    try (var out = publisher.byteChannel()) {
      while (totalWritten < totalToWrite) {
        int toAllocate =
            Math.min(
                bufferSizes[bufferSizesIndex++ % bufferSizes.length], totalToWrite - totalWritten);
        int written = out.write(ByteBuffer.allocate(toAllocate));
        assertThat(written).isEqualTo(toAllocate);
        totalWritten += toAllocate;
      }
    }

    readerDone.await();
    assertThat(memoryTracker.maxQueued).isEqualTo(quota);
  }

  @Test
  @ExecutorSpec(ExecutorType.CACHED_POOL)
  @Timeout(TestUtils.SLOW_TIMEOUT_SECONDS)
  void memoryConsumptionDoesNotExceedQuotaWithFlushing(Executor executor)
      throws IOException, InterruptedException {
    var memoryTracker =
        new WritableBodyPublisher.QueuedMemoryTracker() {
          int queued;
          int maxQueued;

          @Override
          public void queued(int size) {
            queued += size;
            maxQueued = Math.max(maxQueued, queued);
          }

          @Override
          public void dequeued(int size) {
            queued -= size;
          }
        };

    int bufferSize = 64;
    int quota = WritableBodyPublisher.queuedMemoryQuotaFor(bufferSize);
    final int totalToWrite = 50 * quota;

    var publisher = WritableBodyPublisher.create(bufferSize, memoryTracker);
    var subscriber = subscriberContext.<ByteBuffer>createSubscriber().autoRequest(0);
    publisher.subscribe(subscriber);

    // Simulate a slow reader.
    var startReader = new CountDownLatch(1);
    var readerLaunched = new CountDownLatch(1);
    var readerDone = new CountDownLatch(1);
    executor.execute(
        () -> {
          try {
            readerLaunched.countDown();
            TestUtils.awaitUnchecked(startReader);
            int totalRead = 0;
            while (totalRead < totalToWrite) {
              subscriber.requestItems(1);
              var buffer = subscriber.pollNext();
              totalRead += buffer.remaining();
              try {
                Thread.sleep(2);
              } catch (InterruptedException e) {
                throw new RuntimeException(e);
              }
            }
            subscriber.awaitCompletion();
          } finally {
            readerDone.countDown();
          }
        });

    readerLaunched.await();
    startReader.countDown();

    // Alternate on buffer sizes.
    int[] bufferSizes = {bufferSize / 2, bufferSize / 2, bufferSize * 2};
    int bufferSizesIndex = 0;

    int totalWritten = 0;
    try (var out = publisher.byteChannel()) {
      while (totalWritten < totalToWrite) {
        int toAllocate =
            Math.min(
                bufferSizes[bufferSizesIndex++ % bufferSizes.length], totalToWrite - totalWritten);
        int written = out.write(ByteBuffer.allocate(toAllocate));
        if (bufferSizesIndex % 2 == 0) {
          publisher.flush();
        }
        assertThat(written).isEqualTo(toAllocate);
        totalWritten += toAllocate;
      }
    }

    readerDone.await();
    assertThat(memoryTracker.maxQueued).isEqualTo(quota);
  }
}
