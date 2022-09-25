/*
 * Copyright (c) 2022 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.testing.file;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

public class ForwardingFileChannel extends FileChannel implements ForwardingObject<FileChannel> {
  private final FileChannel delegate;

  public ForwardingFileChannel(FileChannel delegate) {
    this.delegate = delegate;
  }

  @Override
  public FileChannel delegate() {
    return delegate;
  }

  @Override
  public int read(ByteBuffer dst) throws IOException {
    return delegate.read(dst);
  }

  @Override
  public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
    return delegate.read(dsts, offset, length);
  }

  @Override
  public int write(ByteBuffer src) throws IOException {
    return delegate.write(src);
  }

  @Override
  public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
    return delegate.write(srcs, offset, length);
  }

  @Override
  public long position() throws IOException {
    return delegate.position();
  }

  @Override
  public FileChannel position(long newPosition) throws IOException {
    return delegate.position(newPosition);
  }

  @Override
  public long size() throws IOException {
    return delegate.size();
  }

  @Override
  public FileChannel truncate(long size) throws IOException {
    return delegate.truncate(size);
  }

  @Override
  public void force(boolean metaData) throws IOException {
    delegate.force(metaData);
  }

  @Override
  public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
    return delegate.transferTo(position, count, target);
  }

  @Override
  public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
    return delegate.transferFrom(src, position, count);
  }

  @Override
  public int read(ByteBuffer dst, long position) throws IOException {
    return delegate.read(dst, position);
  }

  @Override
  public int write(ByteBuffer src, long position) throws IOException {
    return delegate.write(src, position);
  }

  @Override
  public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
    return delegate.map(mode, position, size);
  }

  @Override
  public FileLock lock(long position, long size, boolean shared) throws IOException {
    return delegate.lock(position, size, shared);
  }

  @Override
  public FileLock tryLock(long position, long size, boolean shared) throws IOException {
    return delegate.tryLock(position, size, shared);
  }

  @Override
  public void implCloseChannel() throws IOException {
    delegate.close();
  }
}
