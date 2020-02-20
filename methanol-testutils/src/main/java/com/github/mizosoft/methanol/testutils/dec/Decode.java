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

package com.github.mizosoft.methanol.testutils.dec;

import com.github.mizosoft.methanol.dec.AsyncDecoder;
import java.io.IOException;

public class Decode {

  private static final int BUFFER_SIZE_MANY = 64;

  private static final int SOURCE_INCREMENT_SCALE = 3;

  private Decode() {}

  public static byte[] decode(AsyncDecoder decoder, byte[] compressed, BuffSizeOption sizeOption)
      throws IOException {
    var source = new IncrementalByteArraySource(
            compressed, sizeOption.inSize, SOURCE_INCREMENT_SCALE * sizeOption.inSize);
    var sink = new ByteArraySink(sizeOption.outSize);
    try (decoder) {
      do {
        source.increment();
        decoder.decode(source, sink);
      } while (!source.finalSource());
      if (source.hasRemaining()) {
        throw new IOException("source not exhausted after being final");
      }
    }
    return sink.toByteArray();
  }

  // Make sure the decoder works well with different buffer size configs
  @SuppressWarnings("unused")
  public enum BuffSizeOption {
    IN_MANY_OUT_MANY(BUFFER_SIZE_MANY, BUFFER_SIZE_MANY),
    IN_ONE_OUT_MANY(1, BUFFER_SIZE_MANY),
    IN_MANY_OUT_ONE(BUFFER_SIZE_MANY, 1),
    IN_ONE_OUT_ONE(1, 1);

    final int inSize;
    final int outSize;

    BuffSizeOption(int inSize, int outSize) {
      this.inSize = inSize;
      this.outSize = outSize;
    }

    static BuffSizeOption[] inOptions() {
      return new BuffSizeOption[] {IN_MANY_OUT_MANY, IN_ONE_OUT_MANY};
    }
  }
}
