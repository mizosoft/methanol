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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.mizosoft.methanol.testutils.dec.Decode;
import com.github.mizosoft.methanol.testutils.dec.Decode.BuffSizeOption;
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

  private static final String GOOD = "ocAXACEazuXPqLgaOX42Jj+EdAT91430gPT27km/6WbK3kTpTWJBJkmAeWoBWebW3oK/qHGuI8e6WIjsH5Qqmrt4ByakvCwb73IT2E7OA3MDpxszTNgAn1xJrzB3qoFjKUOWYBi+VYYbqmhiWlHmHtjbjdVfy3jnR9rs6X7PuzmVyW93/LLKaujeyU6O/8yJu4RSPpCDX8afTBrKXY6Vh/5ZqGfsC9oJGm3XX+klIwK/5sMFqil13dFUJH/xZhMm/JyLMb+HN6gerSzhhBGBAbNBkYaDVHKTZyy28+4XjDnIaY83AkYLSCJ7BIUq0b90zmwYPG4A";
  private static final String BAD = "ocAXACEazuXPqLgaOX42Jj+EdAT91430gPT27km/6WbK3kTpTWJBJkmAeWoBWebW3oK/qHGuI8e6WIjsH5Qqmrt4ByakvCwb73IT2E7OA3MDpxszTNgAn1xJrzB3qoFjKUOWYBi+VYYbqmhiWlHmHtjbjdVfy3jnR9rs0D/xwqbv7QEf2rLKaujeyU6O/8yJu4RSPpCDX8afTBrKXY6Vh/5ZqGfsC9oJGm3XX+klIwK/5sMFqil13dFUJH/xZhMm/JyLMb+HN6gerSzhhBGBAbNBkYaDVHKTZyy28+4XjDnIaY83AkYLSCJ7BIUq0b90zmwYPG4A";

  private static final Base64.Decoder BASE64_DEC = Base64.getDecoder();

  @BeforeAll
  static void loadBrotli() throws IOException {
    BrotliLoader.instance().ensureLoaded();
  }

  @Test
  void correctEncoding() {
    try (var dec = new BrotliDecoder()) {
      assertEquals("br", dec.encoding()); // Sanity check
    }
  }

  @Test
  void decodesGoodStream() throws IOException {
    byte[] goodStream = BASE64_DEC.decode(GOOD);
    for (var so : BuffSizeOption.values()) {
      byte[] decoded = Decode.decode(new BrotliDecoder(), goodStream, so);
      assertArrayEquals(brotli(goodStream), decoded);
    }
  }

  @Test
  void throwsOnBadStream() {
    byte[] badStream = BASE64_DEC.decode(BAD);
    for (var so : BuffSizeOption.values()) {
      assertThrows(IOException.class, () -> Decode.decode(new BrotliDecoder(), badStream, so));
    }
  }

  @Test
  void throwsOnOverflow() {
    byte[] goodStream = BASE64_DEC.decode(GOOD);
    byte[] overflowedStream = Arrays.copyOfRange(goodStream, 0, goodStream.length + 2);
    for (var so : BuffSizeOption.values()) {
      // Overflow can throw from two places: either from decoder_jni setting ERROR status
      // if more input is detected in the pushed block, or if decode() loop detects more
      // input in source after decoder_jni sets DONE flag
      var t = assertThrows(IOException.class,
          () -> Decode.decode(new BrotliDecoder(), overflowedStream, so));
      var msg = t.getMessage();
      assertTrue(msg.equals("corrupt brotli stream") ||
          msg.equals("brotli stream finished prematurely"));
    }
  }

  @Test
  void throwsOnUnderflow() {
    byte[] goodStream = BASE64_DEC.decode(GOOD);
    byte[] underflowedStream = Arrays.copyOfRange(goodStream, 0, goodStream.length - 2);
    for (var so : BuffSizeOption.values()) {
      assertThrows(EOFException.class,
          () -> Decode.decode(new BrotliDecoder(), underflowedStream, so));
    }
  }

  private static byte[] brotli(byte[] compressed) {
    try {
      return new BrotliInputStream(new ByteArrayInputStream(compressed)).readAllBytes();
    } catch (IOException ioe) {
      throw new UncheckedIOException(ioe);
    }
  }
}
