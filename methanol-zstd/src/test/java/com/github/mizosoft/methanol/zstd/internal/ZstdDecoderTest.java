/*
 * Copyright (c) 2026 Konrad Windszus
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

package com.github.mizosoft.methanol.zstd.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import com.github.mizosoft.methanol.testing.decoder.Decode;
import com.github.mizosoft.methanol.testing.decoder.Decode.BufferSizeOption;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ZstdDecoderTest {
  private static final String GOOD;
  private static final String BAD;

  static {
    // Generate valid zstd-compressed data by compressing a known string
    var testData = "The quick brown fox jumps over the lazy dog. ".repeat(50);
    var compressed = Zstd.compress(testData.getBytes());
    GOOD = Base64.getEncoder().encodeToString(compressed);

    // Generate invalid compressed data by corrupting the middle of valid data
    var corruptedData = compressed.clone();
    if (corruptedData.length > 10) {
      corruptedData[10] ^= 0xFF; // Flip bits in the middle
    }
    BAD = Base64.getEncoder().encodeToString(corruptedData);
  }

  private static final Base64.Decoder BASE64_DEC = Base64.getDecoder();

  @Test
  void correctEncoding() {
    try (var decoder = new ZstdDecoder()) {
      assertThat(decoder.encoding()).isEqualTo("zstd");
    }
  }

  @Test
  void decodesGoodStream() throws IOException {
    var goodStream = BASE64_DEC.decode(GOOD);
    for (var option : BufferSizeOption.values()) {
      assertThat(Decode.decode(new ZstdDecoder(), goodStream, option))
          .isEqualTo(zstdUnzip(goodStream));
    }
  }

  @Test
  void throwsOnBadStream() {
    var badStream = BASE64_DEC.decode(BAD);
    for (var option : BufferSizeOption.values()) {
      assertThatIOException()
          .isThrownBy(() -> Decode.decode(new ZstdDecoder(), badStream, option));
    }
  }

  @Test
  void throwsOnOverflow() {
    var goodStream = BASE64_DEC.decode(GOOD);
    var overflowedStream = Arrays.copyOf(goodStream, goodStream.length + 2);
    for (var option : BufferSizeOption.values()) {
      assertThatIOException()
          .isThrownBy(() -> Decode.decode(new ZstdDecoder(), overflowedStream, option));
    }
  }

  @Test
  void throwsOnUnderflow() {
    var goodStream = BASE64_DEC.decode(GOOD);
    var underflowedStream = Arrays.copyOfRange(goodStream, 0, goodStream.length - 2);
    for (var option : BufferSizeOption.values()) {
      assertThatIOException()
          .isThrownBy(() -> Decode.decode(new ZstdDecoder(), underflowedStream, option));
    }
  }

  private static byte[] zstdUnzip(byte[] compressed) {
    try {
      return new ZstdInputStream(new ByteArrayInputStream(compressed)).readAllBytes();
    } catch (IOException ioe) {
      throw new UncheckedIOException(ioe);
    }
  }
}
