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

import com.github.mizosoft.methanol.decoder.AsyncDecoder;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Inflater;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** {@code AsyncDecoder} for deflate. */
final class DeflateDecoder implements AsyncDecoder {
  static final String ENCODING = "deflate";

  private static final int CM_MASK = 0x0F00; // Mask for CM half byte
  private static final int CM_DEFLATE = 0x0800; // Deflate CM (8) shifted to CM half byte position

  // A tombstone for inflaterReference indicating the decoder has been closed
  private static final Object CLOSED = new Object();

  // Ideally, we'd have a final Inflater field created with nowrap set to false,
  // as the `deflate` content encoding is defined to be in zlib format (zlib-wrapped).
  // However, some broken servers send raw deflate bytes that aren't zlib-wrapped,
  // so the first two bytes (zlib header) must be peeked first to know if the Inflater
  // is to be created with nowrap set or not (see https://github.com/mizosoft/methanol/issues/25)
  private final AtomicReference<@MonotonicNonNull Object> inflaterReference =
      new AtomicReference<>();

  DeflateDecoder() {}

  @Override
  public String encoding() {
    return ENCODING;
  }

  @Override
  public void decode(ByteSource source, ByteSink sink) throws IOException {
    Inflater inflater;
    var inflaterPlaceholder = inflaterReference.get();
    if (inflaterPlaceholder == null) {
      if (source.remaining() < Short.BYTES) {
        return; // Expect more bytes next round
      }

      var header = ByteBuffer.allocate(Short.BYTES);
      source.pullBytes(header);
      header.flip();
      boolean nowrap = !isProbablyZLibHeader(header.getShort());
      inflater = new Inflater(nowrap);
      if (!inflaterReference.compareAndSet(null, inflater)) {
        inflater.end();
        return;
      }
      inflater.setInput(header.rewind()); // The inflater still has to consume the peeked header
    } else if (inflaterPlaceholder != CLOSED) {
      inflater = (Inflater) inflaterPlaceholder;
    } else {
      return; // The decoder is closed
    }

    InflaterUtils.inflateSource(inflater, source, sink);
    if (inflater.finished()) {
      if (source.hasRemaining()) {
        throw new IOException("deflate stream finished prematurely");
      }
    } else if (source.finalSource()) {
      assert !source.hasRemaining();
      throw new EOFException("unexpected end of deflate stream");
    }
  }

  @Override
  public void close() {
    var inflater = inflaterReference.getAndSet(CLOSED);
    if (inflater instanceof Inflater) { // Not null or CLOSED
      ((Inflater) inflater).end();
    }
  }

  private static boolean isProbablyZLibHeader(short header) {
    // See section 2.2 of https://tools.ietf.org/html/rfc1950
    return (header & CM_MASK) == CM_DEFLATE && header % 31 == 0;
  }
}
