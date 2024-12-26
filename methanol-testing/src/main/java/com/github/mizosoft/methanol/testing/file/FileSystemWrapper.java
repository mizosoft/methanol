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

import java.nio.file.FileSystem;
import java.nio.file.Path;

/**
 * A {@code ForwardingFileSystem} that ensures created {@code Path} and {@code FileSystem} objects
 * are all associated with the same {@link FileSystemProviderWrapper}.
 */
abstract class FileSystemWrapper extends ForwardingFileSystem {
  /*
   * This is a bit tricky: anything that returns Path, FileSystem or FileSystemProvider must be
   * associated with our wrapped provider. This ensures all possible transformations to
   * Path/FileSystem (e.g. Path::resolve) lead to the same provider so that methods in Files always
   * relay to us. This applies recursively as well. This is taken care of by this class,
   * FileSystemProviderWrapper & PathWrapper.
   */

  private final FileSystemProviderWrapper provider;

  FileSystemWrapper(FileSystem delegate, FileSystemProviderWrapper provider) {
    super(delegate);
    this.provider = requireNonNull(provider);
  }

  @Override
  public FileSystemProviderWrapper provider() {
    return provider;
  }

  @Override
  public Iterable<Path> getRootDirectories() {
    return Iterators.map(super.getRootDirectories(), provider::wrap);
  }

  @Override
  public Path getPath(String first, String... more) {
    return provider.wrap(super.getPath(first, more));
  }
}
