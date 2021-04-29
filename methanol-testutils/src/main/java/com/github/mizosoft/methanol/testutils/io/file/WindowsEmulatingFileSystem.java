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

import static java.util.Objects.requireNonNullElseGet;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.CopyOption;
import java.nio.file.FileSystem;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.spi.FileSystemProvider;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@code FileSystem} that wraps another to emulate Windows behaviour regarding deletion of files
 * with open handles. Tracked handles are only those acquired with {@code FileChannels} and {@code
 * AsynchronousFileChannels} (i.e. using methods like {@code Files::newOutputStream} won't run
 * emulation properly).
 *
 * <p>On Windows, deleting files while they're open isn't allowed by default. One has to open the
 * file with FILE_SHARE_DELETE for that to work. Luckily, NIO opens file channels as such. The
 * problem is when a file is requested for deletion while having open handles, Windows only
 * guarantees it gets physically deleted after all its open handles are closed. In the meantime, the
 * OS denies access to that file. Since Windows associates handles with file names, the deleted file
 * name hence can't be used. Things like creating a new file with the name of a file marked for
 * deletion, renaming another file to such name or opening any sort of channel to such file all
 * throw {@code AccessDeniedException}. This file system emulates that behavior so we're sure these
 * cases are covered when interacting with files on Windows.
 */
public final class WindowsEmulatingFileSystem extends CustomFileSystem {
  private WindowsEmulatingFileSystem(FileSystem delegate) {
    super(delegate);
  }

  private WindowsEmulatingFileSystem(
      FileSystem delegate, WindowsEmulatingFileSystemProvider provider) {
    super(delegate, provider);
  }

  @Override
  CustomFileSystemProvider wrap(FileSystemProvider provider) {
    return new WindowsEmulatingFileSystemProvider(provider);
  }

  public static FileSystem wrap(FileSystem fileSystem) {
    return new WindowsEmulatingFileSystem(fileSystem);
  }

  private static final class WindowsEmulatingFileSystemProvider extends CustomFileSystemProvider {
    private final Map<Path, OpenFileRecord> openFiles = new HashMap<>();

    private static final class OpenFileRecord {
      Path path; // Can change if the file is moved
      int openCount;
      boolean markedForDeletion;

      OpenFileRecord(Path path) {
        this.path = path;
      }
    }

    WindowsEmulatingFileSystemProvider(FileSystemProvider delegate) {
      super(delegate);
    }

    @Override
    public FileChannel newFileChannel(
        Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
        throws IOException {
      return openResource(
          path,
          onClose ->
              new ForwardingFileChannel(super.newFileChannel(path, options, attrs)) {
                @Override
                public void implCloseChannel() throws IOException {
                  // We don't need a guard as implCloseChannel is called atomically at most once
                  try (Closeable ignored = super::implCloseChannel) {
                    onClose.close();
                  }
                }
              });
    }

    @Override
    public AsynchronousFileChannel newAsynchronousFileChannel(
        Path path,
        Set<? extends OpenOption> options,
        ExecutorService executor,
        FileAttribute<?>... attrs)
        throws IOException {
      return openResource(
          path,
          onClose ->
              new ForwardingAsynchronousFileChannel(
                  super.newAsynchronousFileChannel(path, options, executor, attrs)) {
                private final AtomicBoolean closed = new AtomicBoolean();

                @Override
                public void close() throws IOException {
                  try (var ignored = delegate()) {
                    if (closed.compareAndSet(false, true)) {
                      onClose.close();
                    }
                  }
                }
              });
    }

    @FunctionalInterface
    private interface ResourceFactory<R> {
      R get(Closeable onClose) throws IOException;
    }

    private <T> T openResource(Path path, ResourceFactory<T> factory) throws IOException {
      synchronized (openFiles) {
        var record = openFiles.get(path);
        if (record != null && record.markedForDeletion) {
          throw new AccessDeniedException(record.path.toString());
        }

        var effectiveRecord = requireNonNullElseGet(record, () -> new OpenFileRecord(path));
        var resource = factory.get(() -> onClose(effectiveRecord));
        effectiveRecord.openCount++;
        openFiles.putIfAbsent(path, effectiveRecord);
        return resource;
      }
    }

    private void onClose(OpenFileRecord record) throws IOException {
      synchronized (openFiles) {
        if (!openFiles.containsValue(record)) {
          throw new IllegalStateException(
              "trying to close a resource for untracked path: " + record.path);
        }

        record.openCount--;
        if (record.openCount <= 0) {
          openFiles.remove(record.path);
          if (record.markedForDeletion) {
            // Perform actual deletion form the file system
            super.delete(ForwardingObject.rootDelegate(record.path));
          }
        }
      }
    }

    @Override
    public Path readSymbolicLink(Path link) throws IOException {
      return wrap(super.readSymbolicLink(link));
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
      // Files can be recycled to directories so deny access if target is an open file
      synchronized (openFiles) {
        var record = openFiles.get(dir);
        if (record != null && record.markedForDeletion) {
          throw new AccessDeniedException(dir.toString());
        }
      }

      // If there's a record then the path is a file and a FileAlreadyExistsException
      // should be thrown, but we'll let the delegate FileSystemProvider handle that.
      super.createDirectory(dir, attrs);
    }

    @Override
    public void delete(Path path) throws IOException {
      synchronized (openFiles) {
        var record = openFiles.get(path);
        if (record != null) {
          // Deny access if file is already marked for deletion
          if (record.markedForDeletion) {
            throw new AccessDeniedException(path.toString());
          }

          record.markedForDeletion = true;
        } else {
          super.delete(path);
        }
      }
    }

    @Override
    public boolean deleteIfExists(Path path) throws IOException {
      synchronized (openFiles) {
        var record = openFiles.get(path);
        if (record != null) {
          // Deny access if file is already marked for deletion
          if (record.markedForDeletion) {
            throw new AccessDeniedException(path.toString());
          }

          record.markedForDeletion = true;
          return true;
        } else {
          return super.deleteIfExists(path);
        }
      }
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
      synchronized (openFiles) {
        // MoveFileEx fails if target has open handles, even if MOVEFILE_REPLACE_EXISTING is used
        var targetRecord = openFiles.get(target);
        if (targetRecord != null) {
          throw new AccessDeniedException(source.toString(), targetRecord.toString(), "");
        }

        // MoveFileEx succeeds if source has open handles, but fails if its marked for deletion
        var sourceRecord = openFiles.get(source);
        if (sourceRecord != null && sourceRecord.markedForDeletion) {
          throw new AccessDeniedException(source.toString(), target.toString(), "");
        }

        super.move(source, target, options);

        // Now associate source record with the new path
        openFiles.remove(source);
        if (sourceRecord != null) {
          sourceRecord.path = target;
          openFiles.put(target, sourceRecord);
        }
      }
    }

    @Override
    CustomFileSystem wrap(FileSystem fileSystem) {
      return new WindowsEmulatingFileSystem(fileSystem, this);
    }
  }
}
