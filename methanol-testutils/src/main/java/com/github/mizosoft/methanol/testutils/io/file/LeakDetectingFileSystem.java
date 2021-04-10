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

import java.io.Closeable;
import java.io.IOException;
import java.lang.StackWalker.Option;
import java.lang.StackWalker.StackFrame;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileSystem;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.spi.FileSystemProvider;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * A {@code FileSystem} that wraps another to detect unclosed resources when the file system is
 * closed. Tracked resources are {@code FileChannels}, {@code AsynchronousFileChannels} and {@code
 * DirectoryStreams}. An {@code IllegalStateException} is thrown when at least one of such resources
 * isn't closed prior to closing this file system.
 */
public final class LeakDetectingFileSystem extends CustomFileSystem {
  private final Map<Closeable, ResourceRecord> resources;
  private final AtomicBoolean closed = new AtomicBoolean();

  private enum ResourceType {
    FILE_CHANNEL,
    ASYNC_FILE_CHANNEL,
    DIRECTORY_STREAM
  }

  private static final class ResourceRecord {
    final Path path;
    final ResourceType type;
    final List<StackFrame> trace;

    ResourceRecord(Path path, ResourceType type) {
      this.path = path;
      this.type = type;

      var trace =
          StackWalker.getInstance(Option.RETAIN_CLASS_REFERENCE)
              .walk(stream -> stream.collect(Collectors.toList()));
      // Discard this constructor's frame
      if (!trace.isEmpty() && trace.get(0).getDeclaringClass() == ResourceRecord.class) {
        trace.remove(0);
      }
      this.trace = Collections.unmodifiableList(trace);
    }

    @Override
    public String toString() {
      return String.format(
          "<%s>: %s, trace: %n%s",
          path,
          type,
          trace.stream()
              .map(Objects::toString)
              .collect(Collectors.joining(System.lineSeparator(), "\tat ", "")));
    }
  }

  private LeakDetectingFileSystem(FileSystem delegate) {
    super(delegate);
    resources = ((LeakDetectingFileSystemProvider) super.provider()).resources;
  }

  private LeakDetectingFileSystem(FileSystem delegate, LeakDetectingFileSystemProvider provider) {
    super(delegate, provider);
    resources = provider.resources;
  }

  @Override
  public void close() throws IOException {
    Closeable delegateCloseable =
        () -> {
          try {
            delegate().close();
          } catch (UnsupportedOperationException ignored) {
            // FileSystems throw UOE if they don't support being closed
          }
        };
    try (delegateCloseable) {
      if (closed.compareAndSet(false, true)) {
        Set<ResourceRecord> leakedResources;
        synchronized (resources) {
          leakedResources = Set.copyOf(resources.values());
        }

        if (!leakedResources.isEmpty()) {
          var leaksDetected =
              new IllegalStateException(
                  "resource leaks detected; "
                      + "see suppressed exceptions for leaked resources & their creation sites");
          leakedResources.forEach(
              record -> {
                var tracedLeak = new Throwable("<" + record.path + ">: " + record.type);
                tracedLeak.setStackTrace(
                    record.trace.stream()
                        .map(StackFrame::toStackTraceElement)
                        .toArray(StackTraceElement[]::new));
                leaksDetected.addSuppressed(tracedLeak);
              });

          throw leaksDetected;
        }
      }
    }
  }

  @Override
  CustomFileSystemProvider wrap(FileSystemProvider provider) {
    return new LeakDetectingFileSystemProvider(provider);
  }

  public static LeakDetectingFileSystem wrap(FileSystem fileSystem) {
    return new LeakDetectingFileSystem(fileSystem);
  }

  /** A {@code FileSystemProvider} that tracks created resources. */
  private static final class LeakDetectingFileSystemProvider extends CustomFileSystemProvider {
    final Map<Closeable, ResourceRecord> resources =
        Collections.synchronizedMap(new LinkedHashMap<>());

    LeakDetectingFileSystemProvider(FileSystemProvider delegate) {
      super(delegate);
    }

    @Override
    public FileChannel newFileChannel(
        Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
        throws IOException {
      var channel =
          new ForwardingFileChannel(super.newFileChannel(path, options, attrs)) {
            @Override
            public void implCloseChannel() throws IOException {
              // We don't need a guard as implCloseChannel is called atomically at most once
              try (Closeable ignored = super::implCloseChannel) {
                if (resources.remove(this) == null) {
                  throw new IllegalStateException("closing an untracked channel");
                }
              }
            }
          };
      resources.put(channel, new ResourceRecord(path, ResourceType.FILE_CHANNEL));
      return channel;
    }

    @Override
    public AsynchronousFileChannel newAsynchronousFileChannel(
        Path path,
        Set<? extends OpenOption> options,
        ExecutorService executor,
        FileAttribute<?>... attrs)
        throws IOException {
      var channel =
          new ForwardingAsynchronousFileChannel(
              super.newAsynchronousFileChannel(path, options, executor, attrs)) {
            private final AtomicBoolean closed = new AtomicBoolean();

            @Override
            public void close() throws IOException {
              try (var ignored = delegate()) {
                if (closed.compareAndSet(false, true)) {
                  if (resources.remove(this) == null) {
                    throw new IllegalStateException("closing an untracked channel");
                  }
                }
              }
            }
          };
      resources.put(channel, new ResourceRecord(path, ResourceType.ASYNC_FILE_CHANNEL));
      return channel;
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, Filter<? super Path> filter)
        throws IOException {
      var stream =
          new ForwardingDirectoryStream<>(super.newDirectoryStream(dir, filter)) {
            private final AtomicBoolean closed = new AtomicBoolean();

            @Override
            public void close() throws IOException {
              try (var ignored = delegate()) {
                if (closed.compareAndSet(false, true)) {
                  if (resources.remove(this) == null) {
                    throw new IllegalStateException("closing an untracked directory stream");
                  }
                }
              }
            }
          };
      resources.put(stream, new ResourceRecord(dir, ResourceType.DIRECTORY_STREAM));
      return stream;
    }

    @Override
    public Path readSymbolicLink(Path link) throws IOException {
      return wrap(super.readSymbolicLink(link));
    }

    @Override
    CustomFileSystem wrap(FileSystem fileSystem) {
      return new LeakDetectingFileSystem(fileSystem, this);
    }
  }
}
