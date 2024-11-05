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

package com.github.mizosoft.methanol.internal.decoder;

import com.github.mizosoft.methanol.decoder.AsyncDecoder;
import java.io.EOFException;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.util.zip.Inflater;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** {@code AsyncDecoder} for deflate. */
final class DeflateDecoder implements AsyncDecoder {
  static final String ENCODING = "deflate";

  /** Mask for CM. */
  private static final int CM_MASK = 0x0F00;

  /** Deflate CM (8) shifted to its position in the header. */
  private static final int SHIFTED_CM_DEFLATE = 0x0800;

  /** A tombstone for {@link #inflaterReference} indicating the decoder has been closed. */
  private static final Object CLOSED = new Object();

  private static final VarHandle INFLATER_REFERENCE;

  static {
    try {
      INFLATER_REFERENCE =
          MethodHandles.lookup()
              .findVarHandle(DeflateDecoder.class, "inflaterReference", Object.class);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * Ideally, we'd have a final {@code Inflater} field created with nowrap set to false, as the
   * deflate content encoding is defined to be in zlib format (zlib-wrapped). However, some servers
   * send raw deflate bytes that aren't zlib-wrapped, so the first two bytes (zlib header) must be
   * peeked first to know if the inflater is to be created with nowrap set or not (see
   * https://github.com/mizosoft/methanol/issues/25).
   */
  @SuppressWarnings("unused") // VarHandle indirection.
  private @MonotonicNonNull Object inflaterReference;

  DeflateDecoder() {}

  @Override
  public String encoding() {
    return ENCODING;
  }

  @Override
  public void decode(ByteSource source, ByteSink sink) throws IOException {
    var inflaterPlaceholder = inflaterReference;
    if (inflaterPlaceholder == CLOSED) {
      return;
    }

    Inflater inflater;
    if (inflaterPlaceholder != null) {
      inflater = (Inflater) inflaterPlaceholder;
    } else if (source.remaining() >= Short.BYTES) {
      var header = ByteBuffer.allocate(Short.BYTES);
      source.pullBytes(header);
      header.flip();

      // Tell the Inflater to not expect zlib wrapping if such wrapping couldn't be detected.
      boolean nowrap = !isProbablyZLibHeader(header.getShort());
      inflater = new Inflater(nowrap);
      if (!INFLATER_REFERENCE.compareAndSet(this, null, inflater)) {
        inflater.end(); // The decoder was closed concurrently.
        return;
      }

      // The inflater still has to consume the peeked header.
      inflater.setInput(header.rewind());
    } else if (source.finalSource()) {
      throw new EOFException("Unexpected end of deflate stream");
    } else {
      return; // Expect more input.
    }

    InflaterUtils.inflateSource(inflater, source, sink);
    if (inflater.finished()) {
      if (source.hasRemaining()) {
        throw new IOException("Deflate stream finished prematurely");
      }
    } else if (source.finalSource()) {
      assert !source.hasRemaining();
      throw new EOFException("Unexpected end of deflate stream");
    }
  }

  @Override
  public void close() {
    var inflater = INFLATER_REFERENCE.getAndSet(this, CLOSED);
    if (inflater instanceof Inflater) { // Not null or CLOSED
      ((Inflater) inflater).end();
    }
  }

  private static boolean isProbablyZLibHeader(short header) {
    // See section 2.2 of https://tools.ietf.org/html/rfc1950.
    return (header & CM_MASK) == SHIFTED_CM_DEFLATE && header % 31 == 0;
  }
}
