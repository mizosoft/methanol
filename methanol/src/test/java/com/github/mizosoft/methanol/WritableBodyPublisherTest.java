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

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.mizosoft.methanol.testutils.BodyCollector;
import com.github.mizosoft.methanol.testutils.TestException;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import org.junit.jupiter.api.Test;

class WritableBodyPublisherTest {

  @Test
  void contentLengthIsUndefined() {
    assertTrue(WritableBodyPublisher.create().contentLength() < 0);
  }

  @Test
  void writeWithByteChannel() throws IOException {
    var body = WritableBodyPublisher.create();
    try (var writer = Channels.newWriter(body.byteChannel(), US_ASCII)) {
      writer.write("I don't like sand");
    }
    assertEquals("I don't like sand", BodyCollector.collectAscii(body));
  }

  @Test
  void writeWithOutputStream() throws IOException {
    var body = WritableBodyPublisher.create();
    try (var writer = new OutputStreamWriter(body.outputStream(), US_ASCII)) {
      writer.write("I don't like sand");
    }
    assertEquals("I don't like sand", BodyCollector.collectAscii(body));
  }

  @Test
  void flushAfterWriting() throws IOException {
    var subscriber = new TestSubscriber() {
      @Override public void onSubscribe(Subscription subscription) {
        subscription.request(1);
      }
      ByteBuffer item;
      @Override public void onNext(ByteBuffer item) {
        if (this.item != null) throw new AssertionError();
        this.item = item;
      }
    };
    var body = WritableBodyPublisher.create();
    body.subscribe(subscriber);
    body.outputStream().write('a');
    body.flush();
    var written = subscriber.item;
    assertNotNull(written);
    assertEquals(1, written.remaining());
    assertEquals('a', written.get());
  }

  @Test
  void completeByClosingSink_byteChannel() throws IOException {
    var subscriber = new TestSubscriber() {
      boolean completed;
      @Override public void onComplete() { completed = true; }
    };
    var body = WritableBodyPublisher.create();
    body.subscribe(subscriber);
    body.byteChannel().close();
    assertFalse(body.byteChannel().isOpen());
    assertTrue(subscriber.completed);
  }

  @Test
  void completeByClosingSink_outputStream() throws IOException {
    var subscriber = new TestSubscriber() {
      boolean completed;
      @Override public void onComplete() { completed = true; }
    };
    var body = WritableBodyPublisher.create();
    body.subscribe(subscriber);
    body.outputStream().close();
    assertTrue(subscriber.completed);
  }

  @Test
  void closeExceptionally() throws IOException {
    var body = WritableBodyPublisher.create();
    try (var out = body.outputStream())  {
      out.write(new byte[] {'a', 'b', 'c'}); // shouldn't be signalled
      out.flush();
      body.closeExceptionally(new TestException());
    }
    var subscriber = new TestSubscriber() {
      Throwable error;
      @Override public void onError(Throwable t) { this.error = t; }
    };
    body.subscribe(subscriber);
    assertNotNull(subscriber.error);
    assertEquals(TestException.class, subscriber.error.getClass());
  }

  @Test
  void subscribeTwice() {
    var body = WritableBodyPublisher.create();
    body.subscribe(new BodyCollector());
    var secondSubscriber = new TestSubscriber() {
      Throwable error;
      @Override public void onError(Throwable t) { this.error = t; }
    };
    body.subscribe(secondSubscriber);
    assertNotNull(secondSubscriber.error);
    assertEquals(IllegalStateException.class, secondSubscriber.error.getClass());
  }

  @Test
  void writeAfterClosing_byteChannel() {
    var body = WritableBodyPublisher.create();
    body.close();
    assertThrows(ClosedChannelException.class, () -> body.byteChannel().write(ByteBuffer.allocate(1)));
  }

  @Test
  void writeAfterClosing_outputStream() {
    var body = WritableBodyPublisher.create();
    body.close();
    assertThrows(ClosedChannelException.class, () -> body.outputStream().write('m'));
  }

  @Test
  void flushAfterClosing() throws IOException {
    var body = WritableBodyPublisher.create();
    body.outputStream().write(new byte[] {'1', '2', '3'});
    body.close();
    assertThrows(IllegalStateException.class, body::flush);
    assertThrows(IOException.class, body.outputStream()::flush);
  }

  @Test
  void writeAfterFlush() throws IOException {
    var body = WritableBodyPublisher.create();
    try (var writer = new OutputStreamWriter(body.outputStream(), UTF_8)) {
      writer.write("abc");
      writer.flush();
      writer.write("ABC");
    }
    assertEquals("abcABC", BodyCollector.collectUtf8(body));
  }

  private static class TestSubscriber implements Subscriber<ByteBuffer> {
    @Override public void onSubscribe(Subscription subscription) { /* request nothing */ }
    @Override public void onNext(ByteBuffer item) { throw new AssertionError(); }
    @Override public void onError(Throwable throwable) { throw new AssertionError(); }
    @Override public void onComplete() { throw new AssertionError(); }
  }
}
