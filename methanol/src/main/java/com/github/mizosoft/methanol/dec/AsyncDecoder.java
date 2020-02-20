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

package com.github.mizosoft.methanol.dec;

import com.github.mizosoft.methanol.internal.Utils;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * An object that decompresses {@code ByteBuffer} chunks of a compressed stream in a non-blocking
 * manner. An {@code AsyncDecoder} can be used with a {@link AsyncBodyDecoder} to craft an
 * implementation of the {@link com.github.mizosoft.methanol.BodyDecoder} interface.
 */
public interface AsyncDecoder extends AutoCloseable {

  /** Returns this decoder's encoding. */
  String encoding();

  /**
   * Processes whatever data available from the given source, writing decompressed bytes to the
   * given sink.
   *
   * @param source the source of compressed bytes
   * @param sink the sink of decompressed bytes
   * @throws IOException if an error occurs while decoding
   */
  void decode(ByteSource source, ByteSink sink) throws IOException;

  /**
   * Releases any resources associated with the decoder. Must be both idempotent and thread safe.
   */
  @Override
  void close();

  /**
   * A source of bytes from which the compressed stream can be read as {@code ByteBuffer} chunks.
   */
  interface ByteSource {

    /**
     * Returns a read-only {@code ByteBuffer} from which the compressed stream can be read. An empty
     * buffer is returned if all buffers for this source were exhausted.
     */
    ByteBuffer currentSource();

    /**
     * Pulls {@code min(source.remaining(), dst.remaining())} bytes from this source to the given
     * buffer.
     *
     * @param dst the destination buffer
     */
    default void pullBytes(ByteBuffer dst) {
      int toCopy = (int) Math.min(remaining(), dst.remaining());
      while (toCopy > 0) {
        toCopy -= Utils.copyRemaining(currentSource(), dst);
      }
    }

    /** Returns total remaining bytes from this source. */
    long remaining();

    /** Returns {@code true} if this source has remaining bytes. */
    default boolean hasRemaining() {
      return remaining() > 0;
    }

    /**
     * Returns true if this is the final source and no more decode operations are to be expected.
     */
    boolean finalSource();
  }

  /**
   * A sink of bytes to which the decompressed stream can be written as {@code ByteBuffer} chunks.
   */
  interface ByteSink {

    /**
     * Returns a {@code ByteBuffer} with available space to which the decompressed stream can be
     * written.
     */
    ByteBuffer currentSink();

    /**
     * Pushes {@code src.remaining()} bytes from the given buffer to this sink.
     *
     * @param src the source buffer
     */
    default void pushBytes(ByteBuffer src) {
      while (src.hasRemaining()) {
        Utils.copyRemaining(src, currentSink());
      }
    }
  }
}
