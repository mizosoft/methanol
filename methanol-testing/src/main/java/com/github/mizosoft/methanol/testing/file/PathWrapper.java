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

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import org.checkerframework.checker.nullness.qual.Nullable;

class PathWrapper extends ForwardingPath {
  private final FileSystemWrapper fileSystem;

  PathWrapper(Path delegate, FileSystemWrapper fileSystem) {
    super(delegate);
    this.fileSystem = requireNonNull(fileSystem);
  }

  @Override
  public FileSystemWrapper getFileSystem() {
    return fileSystem;
  }

  @Override
  public @Nullable Path getRoot() {
    return wrapNullable(super.getRoot());
  }

  @Override
  public @Nullable Path getFileName() {
    return wrapNullable(super.getFileName());
  }

  @Override
  public @Nullable Path getParent() {
    return wrapNullable(super.getParent());
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
    return wrap(super.resolve(fileSystem.provider().delegate(other)));
  }

  @Override
  public Path resolve(String other) {
    return wrap(super.resolve(other));
  }

  @Override
  public Path resolveSibling(Path other) {
    return wrap(super.resolveSibling(fileSystem.provider().delegate(other)));
  }

  @Override
  public Path resolveSibling(String other) {
    return wrap(super.resolveSibling(other));
  }

  @Override
  public Path relativize(Path other) {
    return wrap(super.relativize(fileSystem.provider().delegate(other)));
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
    return Iterators.map(super.iterator(), this::wrap);
  }

  @Override
  public Spliterator<Path> spliterator() {
    return Spliterators.spliteratorUnknownSize(iterator(), 0);
  }

  @Override
  public boolean startsWith(Path other) {
    return super.startsWith(fileSystem.provider().delegate(other));
  }

  @Override
  public boolean endsWith(Path other) {
    return super.endsWith(fileSystem.provider().delegate(other));
  }

  @Override
  public int compareTo(Path other) {
    return super.compareTo(fileSystem.provider().delegate(other));
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof Path) || fileSystem.provider().mismatchesProvider((Path) other)) {
      return false;
    }

    // Now we know the given path belongs to us, so we make sure the delegates are the same.
    return ((PathWrapper) other).delegate().equals(delegate());
  }

  @Override
  public int hashCode() {
    return delegate().hashCode();
  }

  private @Nullable Path wrapNullable(@Nullable Path path) {
    return path != null ? wrap(path) : null;
  }

  private Path wrap(Path path) {
    return fileSystem.provider().wrap(path);
  }
}
