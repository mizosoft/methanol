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
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.util.Iterator;
import java.util.Map;
import java.util.Spliterators;
import java.util.stream.StreamSupport;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A {@code FileSystem} wrapper that ensures created {@code Paths} and {@code FileSystems} are all
 * associated with a wrapped {@code FileSystemProvider}.
 */
abstract class FileSystemWrapper extends ForwardingFileSystem {
  /*
   * This is a bit tricky: anything that returns Path, FileSystem or FileSystemProvider must be
   * associated with our wrapped provider. This ensures all possible transformations to
   * Path/FileSystem (e.g. Path::resolve) lead to the same provider so that methods in Files always
   * relay to us. This applies recursively as well.
   */

  private final FileSystemProviderWrapper provider;

  FileSystemWrapper(FileSystem delegate) {
    super(delegate);
    provider = wrap(delegate.provider());
  }

  FileSystemWrapper(FileSystem delegate, FileSystemProviderWrapper provider) {
    super(delegate);
    this.provider = provider;
  }

  @Override
  public FileSystemProviderWrapper provider() {
    return provider;
  }

  @Override
  public Iterable<Path> getRootDirectories() {
    return () ->
        StreamSupport.stream(super.getRootDirectories().spliterator(), false)
            .<Path>map(provider::wrap)
            .iterator();
  }

  @Override
  public Path getPath(String first, String... more) {
    return provider.wrap(super.getPath(first, more));
  }

  abstract FileSystemProviderWrapper wrap(FileSystemProvider provider);

  abstract static class FileSystemProviderWrapper extends ForwardingFileSystemProvider {
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
      return wrap(super.newFileSystem(path, env));
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, Filter<? super Path> filter)
        throws IOException {
      // Make sure filter receives a file associated with this provider
      Filter<? super Path> wrappedFilter = file -> filter.accept(wrap(file));
      return new ForwardingDirectoryStream<>(super.newDirectoryStream(dir, wrappedFilter)) {
        @Override
        public Iterator<Path> iterator() {
          return StreamSupport.stream(this.spliterator(), false)
              .<Path>map(FileSystemProviderWrapper.this::wrap)
              .iterator();
        }
      };
    }

    @Override
    public Path readSymbolicLink(Path link) throws IOException {
      return wrap(super.readSymbolicLink(link));
    }

    PathWrapper wrap(Path path) {
      return new PathWrapper(path, wrap(path.getFileSystem()));
    }

    abstract FileSystemWrapper wrap(FileSystem fileSystem);
  }

  static class PathWrapper extends ForwardingPath {
    private final FileSystemWrapper fileSystem;

    PathWrapper(Path delegate, FileSystemWrapper fileSystem) {
      super(delegate);
      this.fileSystem = fileSystem;
    }

    @Override
    public FileSystem getFileSystem() {
      return fileSystem;
    }

    @Override
    public Path getRoot() {
      return wrap(super.getRoot());
    }

    @Override
    public Path getFileName() {
      return wrap(super.getFileName());
    }

    @Override
    public Path getParent() {
      return wrap(super.getParent());
    }

    @Override
    public Path getName(int index) {
      return wrap(super.getName(index));
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
      return wrap(super.subpath(beginIndex, endIndex));
    }

    @Override
    public Path normalize() {
      return wrap(super.normalize());
    }

    @Override
    public Path resolve(Path other) {
      return wrap(super.resolve(other));
    }

    @Override
    public Path resolve(String other) {
      return wrap(super.resolve(other));
    }

    @Override
    public Path resolveSibling(Path other) {
      return wrap(super.resolveSibling(other));
    }

    @Override
    public Path resolveSibling(String other) {
      return wrap(super.resolveSibling(other));
    }

    @Override
    public Path relativize(Path other) {
      return wrap(super.relativize(other));
    }

    @Override
    public Path toAbsolutePath() {
      return wrap(super.toAbsolutePath());
    }

    @Override
    public Path toRealPath(LinkOption... options) throws IOException {
      return wrap(super.toRealPath(options));
    }

    @Override
    public Iterator<Path> iterator() {
      return StreamSupport.stream(Spliterators.spliteratorUnknownSize(super.iterator(), 0), false)
          .map(this::wrap)
          .iterator();
    }

    private Path wrap(@Nullable Path delegate) {
      // Sometimes a resulted Path can be null (e.g. Path::getRoot)
      return delegate != null ? fileSystem.provider().wrap(delegate) : null;
    }
  }
}
