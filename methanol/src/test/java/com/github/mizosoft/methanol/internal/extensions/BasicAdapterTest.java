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

import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.TestSubscriber;
import com.github.mizosoft.methanol.testing.TestSubscriberContext;
import com.github.mizosoft.methanol.testing.TestSubscriberExtension;
import com.github.mizosoft.methanol.testing.TestUtils;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Flow.Publisher;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestSubscriberExtension.class)
class BasicAdapterTest {
  @Test
  void encoding(@TempDir Path tempDir) throws IOException {
    var encoder = BasicAdapter.encoder();
    verifyThat(encoder).converting("Pikachu").succeedsWith("Pikachu");
    verifyThat(encoder).converting("é€Pikachu€é").succeedsWith("é€Pikachu€é");
    verifyThat(encoder)
        .converting("é€Pikachu€é")
        .withMediaType(MediaType.parse("text/plain; charset=utf-8"))
        .succeedsWith("é€Pikachu€é");
    verifyThat(encoder)
        .converting("é€Pikachu€é")
        .withMediaType(MediaType.parse("text/plain; charset=ascii"))
        .succeedsWith("??Pikachu??");
    verifyThat(encoder)
        .converting(new byte[] {1, 2, 3})
        .succeedsWith(ByteBuffer.wrap(new byte[] {1, 2, 3}));
    verifyThat(encoder)
        .converting(new ByteArrayInputStream("Pikachu".getBytes(UTF_8)))
        .succeedsWith("Pikachu");
    verifyThat(encoder)
        .<Supplier<InputStream>>converting(
            () -> new ByteArrayInputStream("Pikachu".getBytes(UTF_8)))
        .succeedsWith("Pikachu");
    verifyThat(encoder)
        .converting(List.of(new byte[] {1}, new byte[] {1, 2}, new byte[] {1, 2, 3}))
        .succeedsWith(ByteBuffer.wrap(new byte[] {1, 1, 2, 1, 2, 3}));

    var file = Files.createTempFile(tempDir, BasicAdapterTest.class.getName(), "");
    Files.writeString(file, "Pikachu");
    verifyThat(encoder).converting(file).succeedsWith("Pikachu");
  }

  @Test
  void unsupportedEncoding() {
    verifyThat(BasicAdapter.encoder()).converting(1).isNotSupported();

    // For non-scalars the UnsupportedOperation is thrown during transmission.
    verifyThat(BasicAdapter.encoder())
        .<Supplier<Integer>>converting(() -> 1)
        .failsWith(UnsupportedOperationException.class);
  }

  @Test
  @EnabledForJreRange(
      min = JRE.JAVA_18) // Earlier JDK versions weren't catching exceptions from Iterator::next.
  void unsupportedEncoding_byteArrayIterable() {
    verifyThat(BasicAdapter.encoder())
        .converting(List.of(1, 2, 3))
        .failsWith(UnsupportedOperationException.class);
  }

  @Test
  void failingInputStreamSupplier() {
    verifyThat(BasicAdapter.encoder())
        .<Supplier<InputStream>>converting(
            () -> {
              throw new TestException();
            })
        .failsWith(TestException.class);
  }

  @Test
  void failingSubscriberForFailingInputStreamSupplier(TestSubscriber<ByteBuffer> subscriber) {
    Supplier<InputStream> supplier =
        () -> {
          throw new TestException();
        };
    var publisher = BasicAdapter.encoder().toBody(supplier, MediaType.ANY);
    subscriber.throwOnSubscribe(true);
    publisher.subscribe(subscriber);
    assertThat(subscriber.awaitError())
        .isInstanceOf(TestException.class)
        .hasSuppressedException(new TestException());
  }

  @Test
  void decoding(TestSubscriberContext subscriberContext) {
    var decoder = BasicAdapter.decoder();
    verifyThat(decoder).converting(String.class).withBody("Pikachu").succeedsWith("Pikachu");
    verifyThat(decoder)
        .converting(InputStream.class)
        .withBody("Pikachu")
        .completedBody()
        .satisfies(in -> assertThat(new String(in.readAllBytes(), UTF_8)).isEqualTo("Pikachu"));
    verifyThat(decoder)
        .converting(Reader.class)
        .withBody("Pikachu")
        .completedBody()
        .satisfies(
            reader -> assertThat(new BufferedReader(reader).readLine()).isEqualTo("Pikachu"));
    verifyThat(decoder)
        .converting(Reader.class)
        .withBody("é€Pikachu€é")
        .completedBody()
        .satisfies(
            reader -> assertThat(new BufferedReader(reader).readLine()).isEqualTo("é€Pikachu€é"));
    verifyThat(decoder)
        .converting(Reader.class)
        .withMediaType(MediaType.parse("text/plain; charset=utf-8"))
        .withBody("é€Pikachu€é")
        .completedBody()
        .satisfies(
            reader -> assertThat(new BufferedReader(reader).readLine()).isEqualTo("é€Pikachu€é"));
    verifyThat(decoder)
        .converting(Reader.class)
        .withMediaType(MediaType.parse("text/plain; charset=ascii"))
        .withBody("é€Pikachu€é")
        .completedBody()
        .satisfies(
            reader ->
                assertThatExceptionOfType(MalformedInputException.class)
                    .isThrownBy(() -> new BufferedReader(reader).readLine()));
    verifyThat(decoder)
        .converting(byte[].class)
        .withBody(ByteBuffer.wrap(new byte[] {1, 2, 3}))
        .succeedsWith(new byte[] {1, 2, 3});
    verifyThat(decoder)
        .converting(ByteBuffer.class)
        .withBody(ByteBuffer.wrap(new byte[] {1, 2, 3}))
        .succeedsWith(ByteBuffer.wrap(new byte[] {1, 2, 3}));
    verifyThat(decoder)
        .converting(new TypeRef<Stream<String>>() {})
        .withBody("A\nB\nC\r\nD")
        .completedBody()
        .asInstanceOf(InstanceOfAssertFactories.STREAM)
        .containsExactly("A", "B", "C", "D");
    verifyThat(decoder)
        .converting(new TypeRef<Publisher<List<ByteBuffer>>>() {})
        .withBody("Pikachu")
        .body()
        .satisfies(
            publisher -> {
              var subscriber = subscriberContext.<List<ByteBuffer>>createSubscriber();
              publisher.subscribe(subscriber);
              var body =
                  subscriber.pollAll().stream()
                      .flatMap(List::stream)
                      .reduce(
                          (b1, b2) ->
                              ByteBuffer.allocate(b1.remaining() + b2.remaining())
                                  .put(b1)
                                  .put(b2)
                                  .flip())
                      .orElse(TestUtils.EMPTY_BUFFER);
              assertThat(UTF_8.decode(body).toString()).isEqualTo("Pikachu");
            });
    verifyThat(decoder).converting(Void.class).withBody("Pikachu").succeedsWith(null);
  }

  @Test
  void unsupportedDecoding() {
    verifyThat(BasicAdapter.decoder()).converting(Integer.class).isNotSupported();
  }
}
