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

package com.github.mizosoft.methanol.testing;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class BufferTokenizer {
  private BufferTokenizer() {}

  public static List<List<ByteBuffer>> tokenizeToLists(
      ByteBuffer source, int[] listSizes, int[] bufferSizes) {
    var tokens = new ArrayList<List<ByteBuffer>>();
    for (int i = 0, j = 0; // i (listSizes) j (bufferSizes)
        source.hasRemaining();
        i = (i + 1) % listSizes.length) {
      var token = new ArrayList<ByteBuffer>();
      for (int x = 0;
          source.hasRemaining() && x < listSizes[i];
          x++, j = (j + 1) % bufferSizes.length) {
        var buffer = ByteBuffer.allocate(bufferSizes[j]);
        TestUtils.copyRemaining(source, buffer);
        token.add(buffer.flip().asReadOnlyBuffer());
      }
      tokens.add(List.copyOf(token));
    }
    return List.copyOf(tokens);
  }
}
