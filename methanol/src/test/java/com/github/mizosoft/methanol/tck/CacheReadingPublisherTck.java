package com.github.mizosoft.methanol.tck;

import static java.nio.file.StandardOpenOption.READ;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.cache.CacheReadingPublisher;
import com.github.mizosoft.methanol.internal.cache.MemoryStore;
import com.github.mizosoft.methanol.internal.cache.Store;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.tck.TckUtils.ExecutorFactory;
import com.github.mizosoft.methanol.testutils.BodyCollector;
import com.github.mizosoft.methanol.testutils.TestUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.reactivestreams.tck.flow.FlowPublisherVerification;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Factory;

public class CacheReadingPublisherTck extends FlowPublisherVerification<List<ByteBuffer>> {
  private static final int BUFFER_SIZE = 4 * 1024;
  private static final int MAX_BATCH_SIZE = 3;

  private final ViewerFactory viewerFactory;
  private final ExecutorFactory executorFactory;

  private Executor executor;
  private Viewer viewer;

  private enum ViewerFactory {
    ON_MEMORY {
      @Override
      Viewer create(List<ByteBuffer> data) {
        var store = new MemoryStore(Long.MAX_VALUE);
        try (var editor = requireNonNull(store.edit("e1"))) {
          // Set metadata to prevent the entry from being discarded if data is empty
          editor.metadata(ByteBuffer.wrap(new byte[] {1}));
          editor.writeAsync(0, BodyCollector.collect(data)).join();
          editor.commit();
        } catch (IOException ioe) {
          throw new AssertionError(ioe);
        }
        return new ForwardingViewer(requireNonNull(store.view("e1")));
      }
    },
    ON_DISK {
      @Override
      Viewer create(List<ByteBuffer> data) {
        try {
          var file = Files.createTempFile(CacheReadingPublisherTck.class.getName(), "");
          Files.write(file, TestUtils.toByteArray(BodyCollector.collect(data)));
          return new FileViewer(file);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
    };

    abstract Viewer create(List<ByteBuffer> data);
  }

  @Factory(dataProvider = "provider")
  public CacheReadingPublisherTck(ViewerFactory viewerFactory, ExecutorFactory executorFactory) {
    super(TckUtils.testEnvironment());
    this.viewerFactory = viewerFactory;
    this.executorFactory = executorFactory;
  }

  @AfterMethod
  public void tearDown() throws IOException {
    TestUtils.shutdown(executor);
    if (viewer instanceof FileViewer) {
      Files.deleteIfExists(((FileViewer) viewer).file);
    }
  }

  @Override
  public Publisher<List<ByteBuffer>> createFlowPublisher(long elements) {
    viewer = createViewer(elements);
    executor = executorFactory.create();

    // Limit published items to `elements`
    var publisher = new CacheReadingPublisher(viewer, executor);
    return subscriber ->
        publisher.subscribe(
            subscriber != null ? new LimitingSubscriber<>(subscriber, elements) : null);
  }

  private Viewer createViewer(long elements) {
    var dataBuffer = ByteBuffer.allocate(BUFFER_SIZE);
    ThreadLocalRandom.current()
        .ints(BUFFER_SIZE, 0x20, 0x7f) // ASCII VCHARS
        .forEach(i -> dataBuffer.put((byte) i));
    var data =
        elements > 0
            ? Stream.generate(dataBuffer.flip()::duplicate)
                .limit(MAX_BATCH_SIZE * elements) // Produce `elements` items at minimum
                .collect(Collectors.toUnmodifiableList())
            : List.<ByteBuffer>of();
    return viewerFactory.create(data);
  }

  @Override
  public Publisher<List<ByteBuffer>> createFailedFlowPublisher() {
    return null; // Skip as the publisher can only fail if a read is requested
  }

  @Override
  public long maxElementsFromPublisher() {
    return TckUtils.MAX_PRECOMPUTED_ELEMENTS;
  }

  @DataProvider
  public static Object[][] provider() {
    return new Object[][] {
      {ViewerFactory.ON_MEMORY, ExecutorFactory.SYNC},
      {ViewerFactory.ON_MEMORY, ExecutorFactory.FIXED_POOL},
      {ViewerFactory.ON_DISK, ExecutorFactory.SYNC},
      {ViewerFactory.ON_DISK, ExecutorFactory.FIXED_POOL}
    };
  }

  /**
   * Limits the number of published elements to the count requested by the TCK. This is used to wrap
   * TCK subscribers as the number of {@code List<ByteBuffer>} published by the cache can't be
   * feasibly controlled.
   */
  private static final class LimitingSubscriber<T> implements Subscriber<T> {
    private final Subscriber<T> downstream;
    private final long elements;
    private @MonotonicNonNull Subscription upstream;
    private long received;

    LimitingSubscriber(Subscriber<T> downstream, long elements) {
      this.downstream = downstream;
      this.elements = elements;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      this.upstream = subscription;
      downstream.onSubscribe(subscription);
    }

    @Override
    public void onNext(T item) {
      received++;
      if (received >= elements) {
        upstream.cancel();
      }
      if (received <= elements) {
        downstream.onNext(item);
      }
      if (received == elements) {
        onComplete();
      }
    }

    @Override
    public void onError(Throwable throwable) {
      downstream.onError(throwable);
    }

    @Override
    public void onComplete() {
      downstream.onComplete();
    }
  }

  private abstract static class TestViewer implements Viewer {
    TestViewer() {}

    @Override
    public String key() {
      throw new AssertionError();
    }

    @Override
    public ByteBuffer metadata() {
      throw new AssertionError();
    }

    @Override
    public long dataSize() {
      throw new AssertionError();
    }

    @Override
    public long entrySize() {
      throw new AssertionError();
    }

    @Override
    public Store.@Nullable Editor edit() {
      throw new AssertionError();
    }
  }

  private static final class ForwardingViewer extends TestViewer {
    private final Viewer viewer;

    ForwardingViewer(Viewer viewer) {
      this.viewer = viewer;
    }

    @Override
    public CompletableFuture<Integer> readAsync(long position, ByteBuffer dst) {
      return viewer.readAsync(position, dst);
    }

    @Override
    public void close() {
      viewer.close();
    }
  }

  // TODO replace this with DiskStore when implemented
  private static final class FileViewer extends TestViewer {
    final Path file;
    private final AsynchronousFileChannel channel;

    FileViewer(Path file) throws IOException {
      this.file = file;
      channel = AsynchronousFileChannel.open(file, READ);
    }

    @Override
    public CompletableFuture<Integer> readAsync(long position, ByteBuffer dst) {
      var future = new CompletableFuture<Integer>();
      channel.read(dst, position, dst, new ReadCompletionHandler(channel, future, position));
      return future;
    }

    @Override
    public void close() {
      try {
        channel.close();
      } catch (IOException ignored) {
      }
    }

    /** A completion handler that ensures the buffer is filled before completing the future. */
    private static final class ReadCompletionHandler
        implements CompletionHandler<Integer, ByteBuffer> {
      private final AsynchronousFileChannel channel;
      private final CompletableFuture<Integer> future;
      private final long position;
      private final int totalRead;

      ReadCompletionHandler(
          AsynchronousFileChannel channel, CompletableFuture<Integer> future, long position) {
        this(channel, future, position, 0);
      }

      private ReadCompletionHandler(
          AsynchronousFileChannel channel,
          CompletableFuture<Integer> future,
          long position,
          int totalRead) {
        this.channel = channel;
        this.future = future;
        this.position = position;
        this.totalRead = totalRead;
      }

      @Override
      public void completed(Integer read, ByteBuffer dst) {
        if (read >= 0 && dst.hasRemaining()) {
          long nextPosition = position + read;
          channel.read(
              dst,
              nextPosition,
              dst,
              new ReadCompletionHandler(channel, future, nextPosition, totalRead + read));
        } else {
          future.complete(totalRead + Math.max(read, 0));
        }
      }

      @Override
      public void failed(Throwable exc, ByteBuffer dst) {
        future.completeExceptionally(exc);
      }
    }
  }
}
