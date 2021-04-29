package com.github.mizosoft.methanol.tck;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.cache.CacheReadingPublisher;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.ResolvedStoreConfig;
import com.github.mizosoft.methanol.testing.StoreConfig.StoreType;
import com.github.mizosoft.methanol.testing.StoreContext;
import com.github.mizosoft.methanol.testutils.TestUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.reactivestreams.tck.flow.FlowPublisherVerification;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Factory;

public class CacheReadingPublisherTck extends FlowPublisherVerification<List<ByteBuffer>> {
  private static final int BUFFER_SIZE = 4 * 1024;
  private static final int MAX_BATCH_SIZE = 3;

  private static final String TEST_ENTRY_KEY = "test-entry";

  private final ExecutorType executorType;
  private final ResolvedStoreConfig storeConfig;

  private Executor executor;
  private StoreContext storeContext;

  @Factory(dataProvider = "provider")
  public CacheReadingPublisherTck(ExecutorType executorType, StoreType storeType) {
    super(TckUtils.testEnvironment());
    this.executorType = executorType;
    storeConfig = ResolvedStoreConfig.createDefault(storeType);
  }

  @BeforeMethod
  public void setUpExecutor() {
    executor = executorType.createExecutor();
  }

  @AfterMethod
  public void tearDown() throws Exception {
    TestUtils.shutdown(executor);
    if (storeContext != null) {
      storeContext.close();
    }
  }

  @Override
  public Publisher<List<ByteBuffer>> createFlowPublisher(long elements) {
    // Limit published items to `elements`
    var viewer = populateThenViewEntry(elements);
    var publisher = new CacheReadingPublisher(viewer, executor);
    return subscriber ->
        publisher.subscribe(
            subscriber != null ? new LimitingSubscriber<>(subscriber, elements) : null);
  }

  private Viewer populateThenViewEntry(long elements) {
    try {
      storeContext = storeConfig.createContext();

      var store = storeContext.newStore();
      try (var editor = requireNonNull(store.edit(TEST_ENTRY_KEY))) {
        // Set metadata to not discard the entry if `elements` is 0
        editor.metadata(ByteBuffer.allocate(1));

        int position = 0;
        for (var buffer : generateData(elements)) {
          position += editor.writeAsync(position, buffer).join();
        }
        editor.commitOnClose();
      }
      return requireNonNull(store.view(TEST_ENTRY_KEY));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private List<ByteBuffer> generateData(long elements) {
    var dataBuffer = ByteBuffer.allocate(BUFFER_SIZE);
    ThreadLocalRandom.current()
        .ints(BUFFER_SIZE, 0x20, 0x7f) // ASCII VCHARS
        .forEach(i -> dataBuffer.put((byte) i));
    dataBuffer.flip();
    return elements > 0
        ? Stream.generate(dataBuffer::duplicate)
            .limit(MAX_BATCH_SIZE * elements) // Produce `elements` items at minimum
            .collect(Collectors.toUnmodifiableList())
        : List.of();
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
    // Handcrafted cartesian product
    return new Object[][] {
      {ExecutorType.SAME_THREAD, StoreType.MEMORY},
      {ExecutorType.FIXED_POOL, StoreType.MEMORY},
      {ExecutorType.SAME_THREAD, StoreType.DISK},
      {ExecutorType.FIXED_POOL, StoreType.DISK}
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
}
