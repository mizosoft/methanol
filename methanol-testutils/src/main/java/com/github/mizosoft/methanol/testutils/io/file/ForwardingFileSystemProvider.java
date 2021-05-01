/*
 * Copyright (c) 2021 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.testutils.io.file;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

class ForwardingFileSystemProvider extends FileSystemProvider
    implements ForwardingObject<FileSystemProvider> {
  private final FileSystemProvider delegate;

  ForwardingFileSystemProvider(FileSystemProvider delegate) {
    this.delegate = delegate;
  }

  @Override
  public FileSystemProvider delegate() {
    return delegate;
  }

  @Override
  public String getScheme() {
    return delegate.getScheme();
  }

  @Override
  public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
    return delegate.newFileSystem(uri, env);
  }

  @Override
  public FileSystem getFileSystem(URI uri) {
    return delegate.getFileSystem(uri);
  }

  @Override
  public Path getPath(URI uri) {
    return delegate.getPath(uri);
  }

  @Override
  public FileSystem newFileSystem(Path path, Map<String, ?> env) throws IOException {
    return delegate.newFileSystem(ForwardingObject.rootDelegate(path), env);
  }

  @Override
  public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
    return delegate.newInputStream(ForwardingObject.rootDelegate(path), options);
  }

  @Override
  public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
    return delegate.newOutputStream(ForwardingObject.rootDelegate(path), options);
  }

  @Override
  public FileChannel newFileChannel(
      Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
    return delegate.newFileChannel(ForwardingObject.rootDelegate(path), options, attrs);
  }

  @Override
  public AsynchronousFileChannel newAsynchronousFileChannel(
      Path path,
      Set<? extends OpenOption> options,
      ExecutorService executor,
      FileAttribute<?>... attrs)
      throws IOException {
    return delegate.newAsynchronousFileChannel(
        ForwardingObject.rootDelegate(path), options, executor, attrs);
  }

  @Override
  public SeekableByteChannel newByteChannel(
      Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
    return delegate.newByteChannel(ForwardingObject.rootDelegate(path), options, attrs);
  }

  @Override
  public DirectoryStream<Path> newDirectoryStream(Path dir, Filter<? super Path> filter)
      throws IOException {
    return delegate.newDirectoryStream(ForwardingObject.rootDelegate(dir), filter);
  }

  @Override
  public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
    delegate.createDirectory(ForwardingObject.rootDelegate(dir), attrs);
  }

  @Override
  public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs)
      throws IOException {
    delegate.createSymbolicLink(
        ForwardingObject.rootDelegate(link), ForwardingObject.rootDelegate(target), attrs);
  }

  @Override
  public void createLink(Path link, Path existing) throws IOException {
    delegate.createLink(
        ForwardingObject.rootDelegate(link), ForwardingObject.rootDelegate(existing));
  }

  @Override
  public void delete(Path path) throws IOException {
    delegate.delete(ForwardingObject.rootDelegate(path));
  }

  @Override
  public boolean deleteIfExists(Path path) throws IOException {
    return delegate.deleteIfExists(ForwardingObject.rootDelegate(path));
  }

  @Override
  public Path readSymbolicLink(Path link) throws IOException {
    return delegate.readSymbolicLink(ForwardingObject.rootDelegate(link));
  }

  @Override
  public void copy(Path source, Path target, CopyOption... options) throws IOException {
    delegate.copy(
        ForwardingObject.rootDelegate(source), ForwardingObject.rootDelegate(target), options);
  }

  @Override
  public void move(Path source, Path target, CopyOption... options) throws IOException {
    delegate.move(
        ForwardingObject.rootDelegate(source), ForwardingObject.rootDelegate(target), options);
  }

  @Override
  public boolean isSameFile(Path path, Path path2) throws IOException {
    return delegate.isSameFile(
        ForwardingObject.rootDelegate(path), ForwardingObject.rootDelegate(path2));
  }

  @Override
  public boolean isHidden(Path path) throws IOException {
    return delegate.isHidden(ForwardingObject.rootDelegate(path));
  }

  @Override
  public FileStore getFileStore(Path path) throws IOException {
    return delegate.getFileStore(ForwardingObject.rootDelegate(path));
  }

  @Override
  public void checkAccess(Path path, AccessMode... modes) throws IOException {
    delegate.checkAccess(ForwardingObject.rootDelegate(path), modes);
  }

  @Override
  public <V extends FileAttributeView> V getFileAttributeView(
      Path path, Class<V> type, LinkOption... options) {
    return delegate.getFileAttributeView(ForwardingObject.rootDelegate(path), type, options);
  }

  @Override
  public <A extends BasicFileAttributes> A readAttributes(
      Path path, Class<A> type, LinkOption... options) throws IOException {
    return delegate.readAttributes(ForwardingObject.rootDelegate(path), type, options);
  }

  @Override
  public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
      throws IOException {
    return delegate.readAttributes(ForwardingObject.rootDelegate(path), attributes, options);
  }

  @Override
  public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
      throws IOException {
    delegate.setAttribute(ForwardingObject.rootDelegate(path), attribute, value, options);
  }
}
