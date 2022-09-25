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

package com.github.mizosoft.methanol.brotli.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import com.github.mizosoft.methanol.testing.decoder.Decode;
import com.github.mizosoft.methanol.testing.decoder.Decode.BufferSizeOption;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Base64;
import org.brotli.dec.BrotliInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BrotliDecoderTest {
  private static final String GOOD =
      "ocAXACEazuXPqLgaOX42Jj+EdAT91430gPT27km/6WbK3kTpTWJBJkmAeWoBWebW3oK/qHGuI8e6WIjsH5Qqmrt4ByakvCwb73IT2E7OA3MDpxszTNgAn1xJrzB3qoFjKUOWYBi+VYYbqmhiWlHmHtjbjdVfy3jnR9rs6X7PuzmVyW93/LLKaujeyU6O/8yJu4RSPpCDX8afTBrKXY6Vh/5ZqGfsC9oJGm3XX+klIwK/5sMFqil13dFUJH/xZhMm/JyLMb+HN6gerSzhhBGBAbNBkYaDVHKTZyy28+4XjDnIaY83AkYLSCJ7BIUq0b90zmwYPG4A";
  private static final String BAD =
      "ocAXACEazuXPqLgaOX42Jj+EdAT91430gPT27km/6WbK3kTpTWJBJkmAeWoBWebW3oK/qHGuI8e6WIjsH5Qqmrt4ByakvCwb73IT2E7OA3MDpxszTNgAn1xJrzB3qoFjKUOWYBi+VYYbqmhiWlHmHtjbjdVfy3jnR9rs0D/xwqbv7QEf2rLKaujeyU6O/8yJu4RSPpCDX8afTBrKXY6Vh/5ZqGfsC9oJGm3XX+klIwK/5sMFqil13dFUJH/xZhMm/JyLMb+HN6gerSzhhBGBAbNBkYaDVHKTZyy28+4XjDnIaY83AkYLSCJ7BIUq0b90zmwYPG4A";

  private static final Base64.Decoder BASE64_DEC = Base64.getDecoder();

  @BeforeAll
  static void loadBrotli() throws IOException {
    BrotliLoader.instance().ensureLoaded();
  }

  @Test
  void correctEncoding() {
    try (var decoder = new BrotliDecoder()) {
      assertThat(decoder.encoding()).isEqualTo("br"); // Sanity check
    }
  }

  @Test
  void decodesGoodStream() throws IOException {
    var goodStream = BASE64_DEC.decode(GOOD);
    for (var option : BufferSizeOption.values()) {
      assertThat(Decode.decode(new BrotliDecoder(), goodStream, option))
          .isEqualTo(brotliUnzip(goodStream));
    }
  }

  @Test
  void throwsOnBadStream() {
    var badStream = BASE64_DEC.decode(BAD);
    for (var option : BufferSizeOption.values()) {
      assertThatIOException()
          .isThrownBy(() -> Decode.decode(new BrotliDecoder(), badStream, option));
    }
  }

  @Test
  void throwsOnOverflow() {
    var goodStream = BASE64_DEC.decode(GOOD);
    var overflowedStream = Arrays.copyOfRange(goodStream, 0, goodStream.length + 2);
    for (var option : BufferSizeOption.values()) {
      // Overflow can throw from two places: either from decoder_jni setting ERROR status
      // if more input is detected in the pushed block, or if decode() loop detects more
      // input in source after decoder_jni sets DONE flag
      assertThatIOException()
          .isThrownBy(() -> Decode.decode(new BrotliDecoder(), overflowedStream, option))
          .extracting(Throwable::getMessage, STRING)
          .containsAnyOf("corrupt brotli stream", "brotli stream finished prematurely");
    }
  }

  @Test
  void throwsOnUnderflow() {
    var goodStream = BASE64_DEC.decode(GOOD);
    var underflowedStream = Arrays.copyOfRange(goodStream, 0, goodStream.length - 2);
    for (var option : BufferSizeOption.values()) {
      assertThatExceptionOfType(EOFException.class)
          .isThrownBy(() -> Decode.decode(new BrotliDecoder(), underflowedStream, option));
    }
  }

  private static byte[] brotliUnzip(byte[] compressed) {
    try {
      return new BrotliInputStream(new ByteArrayInputStream(compressed)).readAllBytes();
    } catch (IOException ioe) {
      throw new UncheckedIOException(ioe);
    }
  }
}
