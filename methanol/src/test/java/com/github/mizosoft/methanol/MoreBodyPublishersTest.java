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

import static com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType.FIXED_POOL;
import static com.github.mizosoft.methanol.testutils.Verification.verifyThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorConfig;
import com.github.mizosoft.methanol.testutils.TestException;
import com.github.mizosoft.methanol.testutils.TestSubscriber;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.CharBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ExecutorExtension.class)
class MoreBodyPublishersTest {
  @Test
  void ofMediaType() {
    verifyThat(
            MoreBodyPublishers.ofMediaType(
                BodyPublishers.ofString("Pikachu"), MediaType.parse("text/plain")))
        .hasMediaType("text/plain")
        .succeedsWith("Pikachu");
  }

  @Test
  void ofObject_charBufferBody() {
    var charBuffer = CharBuffer.wrap("Pikachu");
    verifyThat(MoreBodyPublishers.ofObject(charBuffer, MediaType.parse("text/plain")))
        .succeedsWith("Pikachu");
  }

  @Test
  void ofObject_unsupported() {
    assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(() -> MoreBodyPublishers.ofObject(123, null));
    assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(
            () -> MoreBodyPublishers.ofObject("something", MediaType.parse("application/*")));
  }

  @Test
  @ExecutorConfig(FIXED_POOL)
  void ofOutputStream_basicWriting(Executor executor) {
    verifyThat(
            MoreBodyPublishers.ofOutputStream(
                out -> out.write("Pikachu".getBytes(UTF_8)), executor))
        .succeedsWith("Pikachu");
  }

  @Test
  @ExecutorConfig(FIXED_POOL)
  void ofOutputStream_exceptionalCompletion(Executor executor) {
    verifyThat(
            MoreBodyPublishers.ofOutputStream(
                out -> {
                  throw new TestException();
                },
                executor))
        .failsWith(TestException.class);
  }

  @Test
  @ExecutorConfig(FIXED_POOL)
  void ofWritableByteChannel_basicWriting(Executor executor) {
    verifyThat(
            MoreBodyPublishers.ofWritableByteChannel(
                out -> out.write(UTF_8.encode("Pikachu")), executor))
        .succeedsWith("Pikachu");
  }

  @Test
  @ExecutorConfig(FIXED_POOL)
  void ofWritableByteChannel_exceptionalCompletion(Executor executor) {
    verifyThat(
            MoreBodyPublishers.ofWritableByteChannel(
                out -> {
                  throw new TestException();
                },
                executor))
        .failsWith(TestException.class);
  }

  @Test
  @ExecutorConfig(FIXED_POOL)
  void ofOutputStream_onlySubmitsOnSubscribe(ExecutorService service) throws Exception {
    var inWriter = new AtomicBoolean();
    MoreBodyPublishers.ofOutputStream(out -> inWriter.set(true), service);
    service.shutdown();
    assertThat(service.awaitTermination(0, TimeUnit.SECONDS)).isTrue();
    assertThat(inWriter).isFalse();
  }

  @Test
  @ExecutorConfig(FIXED_POOL)
  void ofOutputStream_onlySubmitsIfNotCancelledOnSubscribe(ExecutorService service) throws Exception {
    var inWriter = new AtomicBoolean();
    var publisher = MoreBodyPublishers.ofOutputStream(out -> inWriter.set(true), service);
    publisher.subscribe(
        new TestSubscriber<>() {
          @Override
          public synchronized void onSubscribe(Subscription subscription) {
            super.onSubscribe(subscription);
            subscription.cancel();
          }
        });

    service.shutdown();
    assertThat(service.awaitTermination(0, TimeUnit.SECONDS)).isTrue();
    assertThat(inWriter).isFalse();
  }
}
