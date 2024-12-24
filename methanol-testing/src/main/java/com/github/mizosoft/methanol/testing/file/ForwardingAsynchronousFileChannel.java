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

package com.github.mizosoft.methanol.testing.file;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.Utils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.FileLock;
import java.util.concurrent.Future;

public class ForwardingAsynchronousFileChannel extends AsynchronousFileChannel
    implements ForwardingObject {
  private final AsynchronousFileChannel delegate;

  public ForwardingAsynchronousFileChannel(AsynchronousFileChannel delegate) {
    this.delegate = requireNonNull(delegate);
  }

  @Override
  public AsynchronousFileChannel delegate() {
    return delegate;
  }

  @Override
  public long size() throws IOException {
    return delegate.size();
  }

  @Override
  public AsynchronousFileChannel truncate(long size) throws IOException {
    return delegate.truncate(size);
  }

  @Override
  public void force(boolean metaData) throws IOException {
    delegate.force(metaData);
  }

  @Override
  public <A> void lock(
      long position,
      long size,
      boolean shared,
      A attachment,
      CompletionHandler<FileLock, ? super A> handler) {
    delegate.lock(position, size, shared, attachment, handler);
  }

  @Override
  public Future<FileLock> lock(long position, long size, boolean shared) {
    return delegate.lock(position, size, shared);
  }

  @Override
  public FileLock tryLock(long position, long size, boolean shared) throws IOException {
    return delegate.tryLock(position, size, shared);
  }

  @Override
  public <A> void read(
      ByteBuffer dst, long position, A attachment, CompletionHandler<Integer, ? super A> handler) {
    delegate.read(dst, position, attachment, handler);
  }

  @Override
  public Future<Integer> read(ByteBuffer dst, long position) {
    return delegate.read(dst, position);
  }

  @Override
  public <A> void write(
      ByteBuffer src, long position, A attachment, CompletionHandler<Integer, ? super A> handler) {
    delegate.write(src, position, attachment, handler);
  }

  @Override
  public Future<Integer> write(ByteBuffer src, long position) {
    return delegate.write(src, position);
  }

  @Override
  public void close() throws IOException {
    delegate.close();
  }

  @Override
  public boolean isOpen() {
    return delegate.isOpen();
  }

  @Override
  public String toString() {
    return Utils.forwardingObjectToString(this, delegate);
  }
}
