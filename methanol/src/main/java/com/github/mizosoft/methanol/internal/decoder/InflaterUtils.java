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

import static java.util.Objects.requireNonNullElse;

import com.github.mizosoft.methanol.decoder.AsyncDecoder.ByteSink;
import com.github.mizosoft.methanol.decoder.AsyncDecoder.ByteSource;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

class InflaterUtils {
  private InflaterUtils() {}

  /** Keeps inflating source either till it's exhausted or the inflater is finished. */
  static void inflateSource(Inflater inflater, ByteSource source, ByteSink sink)
      throws ZipException {
    while (source.hasRemaining() && !inflater.finished()) {
      inflateBlock(inflater, source.currentSource(), sink.currentSink());
    }
  }

  /** Same as {@code inflateSource} but with computing the CRC32 checksum of inflated bytes. */
  static void inflateSourceWithChecksum(
      Inflater inflater, ByteSource source, ByteSink sink, CRC32 checksum) throws ZipException {
    while (source.hasRemaining() && !inflater.finished()) {
      inflateBlockWithChecksum(inflater, source.currentSource(), sink.currentSink(), checksum);
    }
  }

  // Inflate [in, out] block (`in` is swapped only if inflater needs input)
  private static void inflateBlock(Inflater inflater, ByteBuffer in, ByteBuffer out)
      throws ZipException {
    if (inflater.needsInput()) {
      inflater.setInput(in);
    } else if (inflater.needsDictionary()) {
      throw new ZipException("missing preset dictionary");
    }
    try {
      inflater.inflate(out);
    } catch (DataFormatException e) {
      throw new ZipException(requireNonNullElse(e.getMessage(), "wrong deflate format"));
    }
  }

  private static void inflateBlockWithChecksum(
      Inflater inflater, ByteBuffer in, ByteBuffer out, CRC32 checksum) throws ZipException {
    out.mark();
    inflateBlock(inflater, in, out);
    int originalLimit = out.limit();
    int writtenLimit = out.position();
    out.reset();
    checksum.update(out.limit(writtenLimit)); // Consumes to written limit AKA original position
    out.limit(originalLimit);
  }
}
