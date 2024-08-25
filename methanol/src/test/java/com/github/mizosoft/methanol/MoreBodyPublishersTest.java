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

package com.github.mizosoft.methanol;

import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorSpec;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.TestException;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow.Subscriber;
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
  @ExecutorSpec(ExecutorType.CACHED_POOL)
  void ofOutputStream_basicWriting(Executor executor) {
    verifyThat(
            MoreBodyPublishers.ofOutputStream(
                out -> out.write("Pikachu".getBytes(UTF_8)), executor))
        .succeedsWith("Pikachu");
  }

  @Test
  @ExecutorSpec(ExecutorType.CACHED_POOL)
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
  @ExecutorSpec(ExecutorType.CACHED_POOL)
  void ofWritableByteChannel_basicWriting(Executor executor) {
    verifyThat(
            MoreBodyPublishers.ofWritableByteChannel(
                out -> out.write(UTF_8.encode("Pikachu")), executor))
        .succeedsWith("Pikachu");
  }

  @Test
  @ExecutorSpec(ExecutorType.CACHED_POOL)
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
  @ExecutorSpec(ExecutorType.CACHED_POOL)
  void ofOutputStream_onlySubmitsIfSubscribed(ExecutorService service) throws Exception {
    var inWriter = new AtomicBoolean();
    MoreBodyPublishers.ofOutputStream(out -> inWriter.set(true), service);
    service.shutdown();
    assertThat(service.awaitTermination(0, TimeUnit.SECONDS)).isTrue(); // No tasks are submitted.
    assertThat(inWriter).isFalse();
  }

  @Test
  @ExecutorSpec(ExecutorType.CACHED_POOL)
  void ofOutputStream_onlySubmitsIfNotCancelledOnSubscribe(ExecutorService service)
      throws Exception {
    var inWriter = new AtomicBoolean();
    var publisher = MoreBodyPublishers.ofOutputStream(out -> inWriter.set(true), service);
    publisher.subscribe(
        new Subscriber<>() {
          @Override
          public void onSubscribe(Subscription subscription) {
            subscription.cancel();
          }

          @Override
          public void onNext(ByteBuffer item) {}

          @Override
          public void onError(Throwable throwable) {}

          @Override
          public void onComplete() {}
        });
    service.shutdown();
    assertThat(service.awaitTermination(0, TimeUnit.SECONDS)).isTrue(); // No tasks are submitted.
    assertThat(inWriter).isFalse();
  }
}
