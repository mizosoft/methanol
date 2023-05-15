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
  FileSystemProviderWrapper(FileSystemProvider delegate) {
    super(delegate);
  }

  @Override
  public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
    return wrap(super.newFileSystem(uri, env));
  }

  @Override
  public FileSystem getFileSystem(URI uri) {
    return wrap(super.getFileSystem(uri));
  }

  @Override
  public Path getPath(URI uri) {
    return wrap(super.getPath(uri));
  }

  @Override
  public FileSystem newFileSystem(Path path, Map<String, ?> env) throws IOException {
    return wrap(super.newFileSystem(ForwardingObject.delegate(path), env));
  }

  @Override
  public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
    return super.newInputStream(ForwardingObject.delegate(path), options);
  }

  @Override
  public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
    return super.newOutputStream(ForwardingObject.delegate(path), options);
  }

  @Override
  public FileChannel newFileChannel(
      Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
    return super.newFileChannel(ForwardingObject.delegate(path), options, attrs);
  }

  @Override
  public AsynchronousFileChannel newAsynchronousFileChannel(
      Path path,
      Set<? extends OpenOption> options,
      ExecutorService executor,
      FileAttribute<?>... attrs)
      throws IOException {
    return super.newAsynchronousFileChannel(
        ForwardingObject.delegate(path), options, executor, attrs);
  }

  @Override
  public SeekableByteChannel newByteChannel(
      Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
    return super.newByteChannel(ForwardingObject.delegate(path), options, attrs);
  }

  @Override
  public DirectoryStream<Path> newDirectoryStream(Path dir, Filter<? super Path> filter)
      throws IOException {
    Filter<? super Path> wrappedFilter = path -> filter.accept(wrap(path));
    return new ForwardingDirectoryStream<>(
        super.newDirectoryStream(ForwardingObject.delegate(dir), wrappedFilter)) {
      @Override
      public Iterator<Path> iterator() {
        return Iterators.map(super.iterator(), FileSystemProviderWrapper.this::wrap);
      }
    };
  }

  @Override
  public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
    super.createDirectory(ForwardingObject.delegate(dir), attrs);
  }

  @Override
  public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs)
      throws IOException {
    super.createSymbolicLink(
        ForwardingObject.delegate(link), ForwardingObject.delegate(target), attrs);
  }

  @Override
  public void createLink(Path link, Path existing) throws IOException {
    super.createLink(ForwardingObject.delegate(link), ForwardingObject.delegate(existing));
  }

  @Override
  public void delete(Path path) throws IOException {
    super.delete(ForwardingObject.delegate(path));
  }

  @Override
  public boolean deleteIfExists(Path path) throws IOException {
    return super.deleteIfExists(ForwardingObject.delegate(path));
  }

  @Override
  public Path readSymbolicLink(Path link) throws IOException {
    return wrap(super.readSymbolicLink(ForwardingObject.delegate(link)));
  }

  @Override
  public void copy(Path source, Path target, CopyOption... options) throws IOException {
    super.copy(ForwardingObject.delegate(source), ForwardingObject.delegate(target), options);
  }

  @Override
  public void move(Path source, Path target, CopyOption... options) throws IOException {
    super.move(ForwardingObject.delegate(source), ForwardingObject.delegate(target), options);
  }

  @Override
  public boolean isSameFile(Path path, Path path2) throws IOException {
    return super.isSameFile(ForwardingObject.delegate(path), ForwardingObject.delegate(path2));
  }

  @Override
  public boolean isHidden(Path path) throws IOException {
    return super.isHidden(ForwardingObject.delegate(path));
  }

  @Override
  public FileStore getFileStore(Path path) throws IOException {
    return super.getFileStore(ForwardingObject.delegate(path));
  }

  @Override
  public void checkAccess(Path path, AccessMode... modes) throws IOException {
    super.checkAccess(ForwardingObject.delegate(path), modes);
  }

  @Override
  public <V extends FileAttributeView> V getFileAttributeView(
      Path path, Class<V> type, LinkOption... options) {
    return super.getFileAttributeView(ForwardingObject.delegate(path), type, options);
  }

  @Override
  public <A extends BasicFileAttributes> A readAttributes(
      Path path, Class<A> type, LinkOption... options) throws IOException {
    //    System.out.println(getClass() + ", " + path.getClass() + ", " + path);
    return super.readAttributes(ForwardingObject.delegate(path), type, options);
  }

  @Override
  public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
      throws IOException {
    return super.readAttributes(ForwardingObject.delegate(path), attributes, options);
  }

  @Override
  public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
      throws IOException {
    super.setAttribute(ForwardingObject.delegate(path), attribute, value, options);
  }

  PathWrapper wrap(Path path) {
    return newPathWrapper(path, wrap(path.getFileSystem()));
  }

  abstract FileSystemWrapper wrap(FileSystem fileSystem);

  /**
   * Creates a new {@link PathWrapper}, possibly with a provider-specific subclass. Even though
   * {@code PathWrapper} is concrete, having each provider implementation have its own {@code
   * PathWrapper} type (even if it just forwards to PathWrapper's constructor) makes Path::equal
   * more predictable and less awkward.
   */
  abstract PathWrapper newPathWrapper(Path path, FileSystemWrapper fileSystem);
}
