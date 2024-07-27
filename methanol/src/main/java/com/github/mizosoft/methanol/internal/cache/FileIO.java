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

package com.github.mizosoft.methanol.internal.cache;

import static java.lang.String.format;

import com.github.mizosoft.methanol.internal.Utils;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Read/Write utilities that make sure exactly the requested bytes are read/written. */
public class FileIO {
  private FileIO() {}

  static ByteBuffer read(FileChannel channel, int byteCount) throws IOException {
    return read(channel, byteCount, -1);
  }

  static ByteBuffer read(FileChannel channel, int byteCount, long position) throws IOException {
    var buffer = ByteBuffer.allocate(byteCount);
    int read = read(channel, buffer, position);
    if (buffer.hasRemaining()) {
      throw new EOFException(format("Expected %d bytes, found %d", byteCount, read));
    }
    return buffer.flip();
  }

  @CanIgnoreReturnValue
  static int read(FileChannel channel, ByteBuffer dst) throws IOException {
    int totalRead = 0;
    int read;
    while (dst.hasRemaining() && (read = channel.read(dst)) >= 0) {
      totalRead += read;
    }
    return totalRead;
  }

  @CanIgnoreReturnValue
  static int read(FileChannel channel, ByteBuffer dst, long position) throws IOException {
    int totalRead = 0;
    int read;
    while (dst.hasRemaining()
        && (read = position >= 0 ? channel.read(dst, position) : channel.read(dst)) >= 0) {
      totalRead += read;
      if (position >= 0) {
        position += read;
      }
    }
    return totalRead;
  }

  @CanIgnoreReturnValue
  static long read(FileChannel channel, ByteBuffer[] dsts) throws IOException {
    long readable = Utils.remaining(dsts);
    long totalRead = 0;
    long read;
    while (totalRead < readable && (read = channel.read(dsts)) >= 0) {
      totalRead += read;
    }
    return totalRead;
  }

  @CanIgnoreReturnValue
  static int write(FileChannel channel, ByteBuffer src) throws IOException {
    return write(channel, src, -1);
  }

  @CanIgnoreReturnValue
  static int write(FileChannel channel, ByteBuffer src, long position) throws IOException {
    int totalWritten = 0;
    while (src.hasRemaining()) {
      int written = position >= 0 ? channel.write(src, position) : channel.write(src);
      totalWritten += written;
      if (position >= 0) {
        position += written;
      }
    }
    return totalWritten;
  }

  @CanIgnoreReturnValue
  static long write(FileChannel channel, ByteBuffer[] srcs) throws IOException {
    long writable = Utils.remaining(srcs);
    long totalWritten = 0;
    while (totalWritten < writable) {
      long written = channel.write(srcs);
      totalWritten += written;
    }
    return totalWritten;
  }
}
