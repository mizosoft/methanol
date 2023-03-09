/*
 * Copyright (c) 2023 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.internal.cache;

import static java.lang.String.format;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Read/Write utilities that make sure exactly the requested bytes are read/written. */
public class StoreIO {
  private StoreIO() {}

  static ByteBuffer readNBytes(FileChannel channel, int byteCount) throws IOException {
    return readNBytes(channel, byteCount, -1);
  }

  static ByteBuffer readNBytes(FileChannel channel, int byteCount, long position)
      throws IOException {
    var buffer = ByteBuffer.allocate(byteCount);
    int totalRead = 0;
    int read;
    while (buffer.hasRemaining()
        && (read = position >= 0 ? channel.read(buffer, position) : channel.read(buffer)) >= 0) {
      totalRead += read;
    }
    if (buffer.hasRemaining()) {
      throw new EOFException(format("expected %d bytes, found %d", byteCount, totalRead));
    }
    return buffer.flip();
  }

  //  static int readBytes(FileChannel channel, ByteBuffer dst, long position) throws IOException {
  //    int read = 0;
  //    while (dst.hasRemaining()) {
  //      read += channel.read(dst, position);
  //    }
  //    return read;
  //  }

  static int readBytes(FileChannel channel, ByteBuffer dst) throws IOException {
    int totalRead = 0;
    int read;
    while (dst.hasRemaining() && (read = channel.read(dst)) >= 0) {
      totalRead += read;
    }
    return totalRead;
  }

  static long readBytes(FileChannel channel, ByteBuffer[] dsts) throws IOException {
    long totalRemaining = 0;
    for (var dst : dsts) {
      totalRemaining = Math.addExact(totalRemaining, dst.remaining());
    }

    long totalRead = 0;
    long read;
    while (totalRead < totalRemaining && (read = channel.read(dsts)) >= 0) {
      totalRead = Math.addExact(totalRead, read);
    }
    return totalRead;
  }

  static int writeBytes(FileChannel channel, ByteBuffer src) throws IOException {
    int written = 0;
    do {
      written += channel.write(src);
    } while (src.hasRemaining());
    return written;
  }

  static int writeBytes(FileChannel channel, ByteBuffer src, long position) throws IOException {
    int remaining = src.remaining();
    while (src.hasRemaining()) {
      position += channel.write(src, position);
    }
    return remaining;
  }

  static long writeBytes(FileChannel channel, ByteBuffer[] srcs) throws IOException {
    long totalRemaining = 0;
    for (var src : srcs) {
      totalRemaining = Math.addExact(totalRemaining, src.remaining());
    }

    long totalWritten = 0;
    while (totalWritten < totalRemaining) {
      long written = channel.write(srcs);
      totalWritten = Math.addExact(totalRemaining, written);
    }
    return totalWritten;
  }
}
