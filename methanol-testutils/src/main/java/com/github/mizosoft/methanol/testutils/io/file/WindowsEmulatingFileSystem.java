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

import com.github.mizosoft.methanol.testutils.io.file.ResourceRecord.ResourceType;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.checkerframework.checker.nullness.qual.Nullable;

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
public final class WindowsEmulatingFileSystem extends FileSystemWrapper {
  private WindowsEmulatingFileSystem(FileSystem delegate) {
    super(delegate);
  }

  private WindowsEmulatingFileSystem(
      FileSystem delegate, WindowsEmulatingFileSystemProvider provider) {
    super(delegate, provider);
  }

  @Override
  FileSystemProviderWrapper wrap(FileSystemProvider provider) {
    return new WindowsEmulatingFileSystemProvider(provider);
  }

  public static FileSystem wrap(FileSystem fileSystem) {
    return new WindowsEmulatingFileSystem(fileSystem);
  }

  private static final class WindowsEmulatingFileSystemProvider extends FileSystemProviderWrapper {
    private final Map<Path, OpenFile> openFiles = new HashMap<>();

    private static final class OpenFile {
      // resourceRecords & markedForDeletion are guarded by openFiles

      final Path path;
      final List<OpenFileResource> resources = new ArrayList<>();

      boolean markedForDeletion;

      OpenFile(Path path) {
        this.path = path;
      }

      void addResource(ResourceRecord record, Closeable resourceObject) {
        resources.add(new OpenFileResource(record, resourceObject));
      }

      boolean removeResource(Closeable resourceObject) {
        var resource =
            resources.stream()
                .filter(
                    openResource ->
                        openResource.resourceObject == resourceObject) // Match by object identity
                .findFirst();
        return resource.map(resources::remove).orElse(false);
      }

      boolean hasNoResources() {
        return resources.isEmpty();
      }

      OpenFile withPath(Path path) {
        var result = new OpenFile(path);
        for (var resource : resources) {
          result.resources.add(resource.withPath(path));
        }
        return result;
      }

      void addResourceStackTraces(Throwable throwable) {
        resources.stream()
            .map(resource -> resource.record.toThrowableStackTrace())
            .forEach(throwable::addSuppressed);
      }
    }

    private static final class OpenFileResource {
      final ResourceRecord record;

      /**
       * The actual opened resource. Used as an identifier so when the resource is closed, the
       * correct resource entry is removed regardless of the file name. This allows closing to work
       * properly even if open files are renamed.
       */
      final Closeable resourceObject;

      OpenFileResource(ResourceRecord record, Closeable resourceObject) {
        this.resourceObject = resourceObject;
        this.record = record;
      }

      OpenFileResource withPath(Path target) {
        return new OpenFileResource(record.withPath(target), resourceObject);
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
          new ResourceRecord(path, ResourceType.FILE_CHANNEL, options),
          onClose ->
              new ForwardingFileChannel(super.newFileChannel(path, options, attrs)) {
                @Override
                public void implCloseChannel() throws IOException {
                  // We don't need a guard as implCloseChannel is called atomically at most once
                  try (Closeable ignored = super::implCloseChannel) {
                    onClose.accept(this);
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
          new ResourceRecord(path, ResourceType.ASYNC_FILE_CHANNEL, options),
          onClose ->
              new ForwardingAsynchronousFileChannel(
                  super.newAsynchronousFileChannel(path, options, executor, attrs)) {
                private final AtomicBoolean closed = new AtomicBoolean();

                @Override
                public void close() throws IOException {
                  try (var ignored = delegate()) {
                    if (closed.compareAndSet(false, true)) {
                      onClose.accept(this);
                    }
                  }
                }
              });
    }

    private interface IOConsumer<T> {
      void accept(T t) throws IOException;
    }

    @FunctionalInterface
    private interface ResourceFactory<R extends Closeable> {
      R create(IOConsumer<Closeable> onClose) throws IOException;
    }

    private <R extends Closeable> R openResource(
        ResourceRecord resourceRecord, ResourceFactory<R> factory) throws IOException {
      var path = resourceRecord.path;
      synchronized (openFiles) {
        var openFile = openFiles.get(path);
        if (openFile != null && openFile.markedForDeletion) {
          throw newAccessDeniedException(
              path, null, "opening a resource for an open file marked for deletion");
        }

        var effectiveOpenFile = openFiles.computeIfAbsent(path, OpenFile::new);
        try {
          var resourceObject = factory.create(this::onClose);
          effectiveOpenFile.addResource(resourceRecord, resourceObject);
          return resourceObject;
        } catch (IOException e) {
          // Drop the open file record if it's newly created
          if (effectiveOpenFile.hasNoResources()) {
            openFiles.remove(path);
          }

          throw e;
        }
      }
    }

    private void onClose(Closeable resourceObject) throws IOException {
      synchronized (openFiles) {
        for (var openFile : openFiles.values()) {
          if (openFile.removeResource(resourceObject)) {
            if (openFile.hasNoResources()) {
              openFiles.remove(openFile.path);
              if (openFile.markedForDeletion) {
                // Perform actual deletion form the file system
                super.delete(openFile.path);
              }
            }
            return;
          }
        }

        throw new IllegalStateException("untracked resource: " + resourceObject);
      }
    }

    @Override
    public Path readSymbolicLink(Path link) throws IOException {
      return wrap(super.readSymbolicLink(link));
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
      // Files can be recycled to directories so deny access if target is an open file
      // that's marked for deletion
      synchronized (openFiles) {
        var openFile = openFiles.get(dir);
        if (openFile != null && openFile.markedForDeletion) {
          throw newAccessDeniedException(
              dir, null, "recycling an open file marked for deletion to a directory");
        }
      }

      // If there's a record then the path is a file and a FileAlreadyExistsException
      // should be thrown, but we'll let the delegate FileSystemProvider handle that.
      super.createDirectory(dir, attrs);
    }

    @Override
    public void delete(Path path) throws IOException {
      synchronized (openFiles) {
        var openFile = openFiles.get(path);
        if (openFile != null) {
          // Deny access if file is already marked for deletion
          if (openFile.markedForDeletion) {
            throw newAccessDeniedException(
                path, null, "deleting an open file that's already marked for deletion");
          }

          openFile.markedForDeletion = true;
        } else {
          super.delete(path);
        }
      }
    }

    @Override
    public boolean deleteIfExists(Path path) throws IOException {
      synchronized (openFiles) {
        var openFile = openFiles.get(path);
        if (openFile != null) {
          // Deny access if file is already marked for deletion
          if (openFile.markedForDeletion) {
            throw newAccessDeniedException(
                path, null, "deleting an open file that's already marked for deletion");
          }

          openFile.markedForDeletion = true;
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
        var targetOpenFile = openFiles.get(target);
        if (targetOpenFile != null) {
          throw newAccessDeniedException(source, target, "target has open handles");
        }

        // MoveFileEx succeeds if source has open handles, but fails if its marked for deletion
        var sourceOpenFile = openFiles.get(source);
        if (sourceOpenFile != null && sourceOpenFile.markedForDeletion) {
          throw newAccessDeniedException(
              source, target, "source has open handles and marked for deletion");
        }

        super.move(source, target, options);

        // Now associate source open file with the new path
        openFiles.remove(source);
        if (sourceOpenFile != null) {
          openFiles.put(target, sourceOpenFile.withPath(target));
        }
      }
    }

    @Override
    FileSystemWrapper wrap(FileSystem fileSystem) {
      return new WindowsEmulatingFileSystem(fileSystem, this);
    }

    private AccessDeniedException newAccessDeniedException(
        Path source, @Nullable Path target, String message) {
      var accessDenied =
          new AccessDeniedException(
              source.toString(),
              target != null ? target.toString() : null,
              message + "; see suppressed exceptions for open resources & their creation sites");

      var sourceOpenFile = openFiles.get(source);
      if (sourceOpenFile != null) {
        sourceOpenFile.addResourceStackTraces(accessDenied);
      }

      var targetOpenFile = openFiles.get(target);
      if (targetOpenFile != null) {
        targetOpenFile.addResourceStackTraces(accessDenied);
      }

      return accessDenied;
    }
  }
}
