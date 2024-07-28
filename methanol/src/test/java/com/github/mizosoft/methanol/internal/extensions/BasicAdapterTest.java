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
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BasicAdapterTest {
  @Test
  void encoding(@TempDir Path tempDir) throws IOException {
    var encoder = BasicAdapter.newEncoder();
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

    var file = Files.createTempFile(tempDir, BasicAdapterTest.class.getName(), "");
    Files.writeString(file, "Pikachu");
    verifyThat(encoder).converting(file).succeedsWith("Pikachu");
  }

  @Test
  void unsupportedEncoding() {
    verifyThat(BasicAdapter.newEncoder()).converting(1).isNotSupported();
  }

  @Test
  void decoding() {
    var decoder = BasicAdapter.newDecoder();
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
  }

  @Test
  void unsupportedDecoding() {
    verifyThat(BasicAdapter.newDecoder()).converting(Integer.class).isNotSupported();
  }
}
