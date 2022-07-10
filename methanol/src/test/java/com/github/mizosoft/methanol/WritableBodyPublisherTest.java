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

import static com.github.mizosoft.methanol.testutils.Verification.verifyThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.from;

import com.github.mizosoft.methanol.testutils.TestException;
import com.github.mizosoft.methanol.testutils.TestSubscriber;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import org.junit.jupiter.api.Test;

class WritableBodyPublisherTest {
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
    var subscriber = new TestSubscriber<ByteBuffer>();
    subscriber.request = 0;
    publisher.subscribe(subscriber);
    subscriber.awaitSubscribe();
    subscriber.subscription.request(1);

    publisher.outputStream().write('a');
    publisher.flush();
    assertThat(subscriber.items)
        .singleElement()
        .returns(1, from(ByteBuffer::remaining))
        .returns((byte) 'a', from(ByteBuffer::get));
  }

  @Test
  void flushAfterWritingWithByteChannel() throws IOException {
    var publisher = WritableBodyPublisher.create();
    var subscriber = new TestSubscriber<ByteBuffer>();
    subscriber.request = 0;
    publisher.subscribe(subscriber);
    subscriber.awaitSubscribe();
    subscriber.subscription.request(1);

    publisher.byteChannel().write(ByteBuffer.wrap(new byte[] {'a'}));
    publisher.flush();
    assertThat(subscriber.items)
        .singleElement()
        .returns(1, from(ByteBuffer::remaining))
        .returns((byte) 'a', from(ByteBuffer::get));
  }

  @Test
  void completeByClosingByteChannel() throws IOException {
    var publisher = WritableBodyPublisher.create();
    var subscriber = new TestSubscriber<ByteBuffer>();
    publisher.subscribe(subscriber);
    publisher.byteChannel().close();
    assertThat(subscriber.completes).isEqualTo(1);
    assertThat(publisher.byteChannel().isOpen()).isFalse();
  }

  @Test
  void completeByClosingOutputStream() throws IOException {
    var publisher = WritableBodyPublisher.create();
    var subscriber = new TestSubscriber<ByteBuffer>();
    publisher.subscribe(subscriber);
    publisher.outputStream().close();
    assertThat(subscriber.completes).isEqualTo(1);

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

    var subscriber = new TestSubscriber<ByteBuffer>();
    publisher.subscribe(subscriber);
    assertThat(subscriber.items).isEmpty();
    assertThat(subscriber.lastError).isInstanceOf(TestException.class);
  }

  @Test
  void subscribeTwice() {
    var publisher = WritableBodyPublisher.create();
    publisher.subscribe(new TestSubscriber<>());

    var secondSubscriber = new TestSubscriber<ByteBuffer>();
    publisher.subscribe(secondSubscriber);
    assertThat(secondSubscriber.lastError).isInstanceOf(IllegalStateException.class);
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
    var subscriber = new TestSubscriber<ByteBuffer>();
    publisher.subscribe(subscriber);
    try (var out = publisher.outputStream()) {
      out.write(new byte[10]);
      assertThat(subscriber.awaitNextItem()).returns(10, from(ByteBuffer::remaining));

      out.write(new byte[9]);
      assertThat(subscriber.nexts).isOne(); // No new signals are received

      out.write('a');
      assertThat(subscriber.awaitNextItem()).returns(10, from(ByteBuffer::remaining));
    }
  }
}
