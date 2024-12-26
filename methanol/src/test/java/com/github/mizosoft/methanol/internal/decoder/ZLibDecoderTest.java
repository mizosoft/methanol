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

package com.github.mizosoft.methanol.internal.decoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.github.mizosoft.methanol.decoder.AsyncDecoder;
import com.github.mizosoft.methanol.testing.decoder.Decode;
import com.github.mizosoft.methanol.testing.decoder.Decode.BufferSizeOption;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.ZipException;
import org.junit.jupiter.api.Test;

abstract class ZLibDecoderTest {
  static final Base64.Decoder BASE64_DEC = Base64.getDecoder();

  ZLibDecoderTest() {}

  abstract String good();

  abstract String bad();

  abstract String encoding();

  abstract AsyncDecoder newDecoder();

  abstract byte[] nativeDecode(byte[] compressed);

  @Test
  void correctEncoding() {
    try (var decoder = newDecoder()) {
      assertThat(decoder.encoding()).isEqualTo(encoding()); // Sanity check
    }
  }

  @Test
  void decodesGoodStream() throws IOException {
    var goodStream = BASE64_DEC.decode(good());
    for (var option : BufferSizeOption.values()) {
      assertThat(Decode.decode(newDecoder(), goodStream, option))
          .isEqualTo(nativeDecode(goodStream));
    }
  }

  @Test
  void throwsOnBadStream() {
    var badStream = BASE64_DEC.decode(bad());
    for (var option : BufferSizeOption.values()) {
      assertThatExceptionOfType(ZipException.class)
          .isThrownBy(() -> Decode.decode(newDecoder(), badStream, option));
    }
  }

  @Test
  void throwsOnOverflow() {
    var goodStream = BASE64_DEC.decode(good());
    var overflowedStream = Arrays.copyOfRange(goodStream, 0, goodStream.length + 2);
    for (var option : BufferSizeOption.values()) {
      assertThatExceptionOfType(IOException.class)
          .isThrownBy(() -> Decode.decode(newDecoder(), overflowedStream, option))
          .withMessageContaining("stream finished prematurely");
    }
  }

  @Test
  void throwsOnUnderflow() {
    var goodStream = BASE64_DEC.decode(good());
    var underflowedStream = Arrays.copyOfRange(goodStream, 0, goodStream.length - 2);
    for (var option : BufferSizeOption.values()) {
      assertThatExceptionOfType(EOFException.class)
          .isThrownBy(() -> Decode.decode(newDecoder(), underflowedStream, option));
    }
  }
}
