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
    for (int read = 0; read >= 0 && buffer.hasRemaining(); totalRead += read) {
      read = position >= 0 ? channel.read(buffer, position) : channel.read(buffer);
      if (read >= 0) {
        totalRead += read;
        if (position >= 0) {
          position += read;
        }
      }
    }
    if (buffer.hasRemaining()) {
      throw new EOFException(format("expected %d bytes, found %d", byteCount, totalRead));
    }
    return buffer.flip();
  }

  static int readBytes(FileChannel channel, ByteBuffer dst, long position) throws IOException {
    int read = 0;
    while (dst.hasRemaining()) {
      read += channel.read(dst, position);
    }
    return read;
  }

  static void writeBytes(FileChannel channel, ByteBuffer src) throws IOException {
    do {
      channel.write(src);
    } while (src.hasRemaining());
  }

  static int writeBytes(FileChannel channel, ByteBuffer src, long position) throws IOException {
    int remaining = src.remaining();
    while (src.hasRemaining()) {
      position += channel.write(src, position);
    }
    return remaining;
  }
}
