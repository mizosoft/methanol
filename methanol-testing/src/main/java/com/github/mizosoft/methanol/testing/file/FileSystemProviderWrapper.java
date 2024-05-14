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

package com.github.mizosoft.methanol.testing.file;

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
import java.nio.file.ProviderMismatchException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * A {@code ForwardingFileSystemProvider} that adds behavior to an existing {@code
 * FileSystemProvider}, and ensures all created objects refer to this provider.
 */
abstract class FileSystemProviderWrapper extends ForwardingFileSystemProvider {
  private final Class<? extends FileSystemProviderWrapper> fileSystemProviderClass;

  FileSystemProviderWrapper(FileSystemProvider delegate) {
    super(delegate);
    this.fileSystemProviderClass = getClass();
  }

  @Override
  public FileSystemWrapper newFileSystem(URI uri, Map<String, ?> env) throws IOException {
    return wrap(super.newFileSystem(uri, env));
  }

  @Override
  public FileSystemWrapper getFileSystem(URI uri) {
    return wrap(super.getFileSystem(uri));
  }

  @Override
  public Path getPath(URI uri) {
    return wrap(super.getPath(uri));
  }

  @Override
  public FileSystemWrapper newFileSystem(Path path, Map<String, ?> env) throws IOException {
    return wrap(super.newFileSystem(delegate(path), env));
  }

  @Override
  public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
    return super.newInputStream(delegate(path), options);
  }

  @Override
  public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
    return super.newOutputStream(delegate(path), options);
  }

  @Override
  public FileChannel newFileChannel(
      Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
    return super.newFileChannel(delegate(path), options, attrs);
  }

  @Override
  public AsynchronousFileChannel newAsynchronousFileChannel(
      Path path,
      Set<? extends OpenOption> options,
      ExecutorService executor,
      FileAttribute<?>... attrs)
      throws IOException {
    return super.newAsynchronousFileChannel(delegate(path), options, executor, attrs);
  }

  @Override
  public SeekableByteChannel newByteChannel(
      Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
    return super.newByteChannel(delegate(path), options, attrs);
  }

  @Override
  public DirectoryStream<Path> newDirectoryStream(Path dir, Filter<? super Path> filter)
      throws IOException {
    Filter<? super Path> wrappedFilter = path -> filter.accept(wrap(path));
    return new ForwardingDirectoryStream<>(super.newDirectoryStream(delegate(dir), wrappedFilter)) {
      @Override
      public Iterator<Path> iterator() {
        return Iterators.map(super.iterator(), FileSystemProviderWrapper.this::wrap);
      }
    };
  }

  @Override
  public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
    super.createDirectory(delegate(dir), attrs);
  }

  @Override
  public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs)
      throws IOException {
    super.createSymbolicLink(delegate(link), delegate(target), attrs);
  }

  @Override
  public void createLink(Path link, Path existing) throws IOException {
    super.createLink(delegate(link), delegate(existing));
  }

  @Override
  public void delete(Path path) throws IOException {
    super.delete(delegate(path));
  }

  @Override
  public boolean deleteIfExists(Path path) throws IOException {
    return super.deleteIfExists(delegate(path));
  }

  @Override
  public Path readSymbolicLink(Path link) throws IOException {
    return wrap(super.readSymbolicLink(delegate(link)));
  }

  @Override
  public void copy(Path source, Path target, CopyOption... options) throws IOException {
    super.copy(delegate(source), delegate(target), options);
  }

  @Override
  public void move(Path source, Path target, CopyOption... options) throws IOException {
    super.move(delegate(source), delegate(target), options);
  }

  @Override
  public boolean isSameFile(Path path, Path path2) throws IOException {
    return super.isSameFile(delegate(path), delegate(path2));
  }

  @Override
  public boolean isHidden(Path path) throws IOException {
    return super.isHidden(delegate(path));
  }

  @Override
  public FileStore getFileStore(Path path) throws IOException {
    return super.getFileStore(delegate(path));
  }

  @Override
  public void checkAccess(Path path, AccessMode... modes) throws IOException {
    super.checkAccess(delegate(path), modes);
  }

  @Override
  public <V extends FileAttributeView> V getFileAttributeView(
      Path path, Class<V> type, LinkOption... options) {
    return super.getFileAttributeView(delegate(path), type, options);
  }

  @Override
  public <A extends BasicFileAttributes> A readAttributes(
      Path path, Class<A> type, LinkOption... options) throws IOException {
    return super.readAttributes(delegate(path), type, options);
  }

  @Override
  public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
      throws IOException {
    return super.readAttributes(delegate(path), attributes, options);
  }

  @Override
  public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
      throws IOException {
    super.setAttribute(delegate(path), attribute, value, options);
  }

  PathWrapper wrap(Path path) {
    return new PathWrapper(path, wrap(path.getFileSystem()));
  }

  abstract FileSystemWrapper wrap(FileSystem fileSystem);

  boolean matchesProvider(Path path) {
    return path instanceof PathWrapper
        && (fileSystemProviderClass.isAssignableFrom(path.getFileSystem().provider().getClass())
            || path.getFileSystem()
                .provider()
                .getClass()
                .isAssignableFrom(fileSystemProviderClass));
  }

  Path delegate(Path path) {
    if (!matchesProvider(path)) {
      throw new ProviderMismatchException();
    }
    return ((PathWrapper) path).delegate();
  }
}
