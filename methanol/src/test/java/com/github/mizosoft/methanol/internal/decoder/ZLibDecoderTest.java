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

package com.github.mizosoft.methanol.internal.decoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.mizosoft.methanol.decoder.AsyncDecoder;
import com.github.mizosoft.methanol.testutils.dec.Decode;
import com.github.mizosoft.methanol.testutils.dec.Decode.BuffSizeOption;
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
    try (var dec = newDecoder()) {
      assertEquals(encoding(), dec.encoding()); // Sanity check
    }
  }

  @Test
  void decodesGoodStream() throws IOException {
    byte[] goodStream = BASE64_DEC.decode(good());
    for (var so : BuffSizeOption.values()) {
      byte[] decoded = Decode.decode(newDecoder(), goodStream, so);
      assertArrayEquals(nativeDecode(goodStream), decoded);
    }
  }

  @Test
  void throwsOnBadStream() {
    byte[] badStream = BASE64_DEC.decode(bad());
    for (var so : BuffSizeOption.values()) {
      assertThrows(ZipException.class, () -> Decode.decode(newDecoder(), badStream, so));
    }
  }

  @Test
  void throwsOnOverflow() {
    byte[] goodStream = BASE64_DEC.decode(good());
    byte[] overflowedStream = Arrays.copyOfRange(goodStream, 0, goodStream.length + 2);
    for (var so : BuffSizeOption.values()) {
      var t = assertThrows(IOException.class,
          () -> Decode.decode(newDecoder(), overflowedStream, so));
      assertTrue(t.getMessage().contains("stream finished prematurely"));
    }
  }

  @Test
  void throwsOnUnderflow() {
    byte[] goodStream = BASE64_DEC.decode(good());
    byte[] underflowedStream = Arrays.copyOfRange(goodStream, 0, goodStream.length - 2);
    for (var so : BuffSizeOption.values()) {
      assertThrows(EOFException.class,
          () -> Decode.decode(newDecoder(), underflowedStream, so));
    }
  }
}
