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

package com.github.mizosoft.methanol.tck;

import com.github.mizosoft.methanol.internal.cache.CacheReadingPublisher;
import com.github.mizosoft.methanol.internal.cache.Store;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.testing.ExecutorContext;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.store.RedisClusterStoreContext;
import com.github.mizosoft.methanol.testing.store.RedisStandaloneStoreContext;
import com.github.mizosoft.methanol.testing.store.StoreConfig;
import com.github.mizosoft.methanol.testing.store.StoreConfig.StoreType;
import com.github.mizosoft.methanol.testing.store.StoreContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicLong;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.reactivestreams.tck.flow.FlowPublisherVerification;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

@Slow
@Test
public class CacheReadingPublisherTckTest extends FlowPublisherVerification<List<ByteBuffer>> {
  /**
   * This field mirrors {@code MAX_BULK_READ_SIZE} in CacheReadingPublisher. The two are the same as
   * the current implementation passes downstream each list of buffers into which data is read, so
   * at most a received list can have {@code MAX_BULK_READ_SIZE}.
   */
  private static final int MAX_BATCH_SIZE = 4;

  private static final AtomicLong entryId = new AtomicLong();

  private final ExecutorType executorType;
  private final StoreConfig storeConfig;

  private ExecutorContext executorContext;
  private StoreContext storeContext;
  private Store store;

  /**
   * The list of viewers opened during a test method execution. CacheReadingPublisher closes the
   * viewer when the body is consumed or an error is signalled amid transmission. However, some
   * tests don't lead to either (e.g. trying to subscribe with a null subscriber). So we make sure
   * opened viewers are closed after each test.
   */
  private final List<Viewer> openedViewers = new ArrayList<>();

  @Factory(dataProvider = "provider")
  public CacheReadingPublisherTckTest(ExecutorType executorType, StoreType storeType) {
    super(TckUtils.newTestEnvironment());
    this.executorType = executorType;
    this.storeConfig = StoreConfig.createDefault(storeType);
  }

  @BeforeMethod
  public void setMeUp() throws IOException {
    executorContext = new ExecutorContext();
    storeContext = StoreContext.of(storeConfig);
    store = storeContext.createAndRegisterStore();
  }

  @AfterMethod
  public void tearMeDown() throws Exception {
    executorContext.close();
    openedViewers.forEach(Viewer::close);
    openedViewers.clear();
    storeContext.close();
  }

  @Override
  public Publisher<List<ByteBuffer>> createFlowPublisher(long elements) {
    var viewer = populateThenViewEntry(elements);
    openedViewers.add(viewer);

    var publisher =
        new CacheReadingPublisher(
            viewer,
            executorContext.createExecutor(executorType),
            CacheReadingPublisher.Listener.disabled(),
            TckUtils.BUFFER_SIZE);
    return subscriber ->
        publisher.subscribe(
            subscriber != null ? new LimitingSubscriber<>(subscriber, elements) : null);
  }

  private Viewer populateThenViewEntry(long elements) {
    try {
      var entryName = "e-" + entryId.getAndIncrement();
      try (var editor = store.edit(entryName).orElseThrow()) {
        long itemCount = elements * MAX_BATCH_SIZE; // Product `elements` items at minimum.
        var writer = editor.writer();
        for (int i = 0; i < itemCount; i++) {
          writer.write(TckUtils.generateData());
        }
        editor.commit(ByteBuffer.allocate(1));
      }
      return store.view(entryName).orElseThrow();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public Publisher<List<ByteBuffer>> createFailedFlowPublisher() {
    return null; // Skip as the publisher can't fail unless a read is requested.
  }

  @Override
  public long maxElementsFromPublisher() {
    return TckUtils.MAX_PRECOMPUTED_ELEMENTS;
  }

  @DataProvider
  public static Object[][] provider() {
    // Handcrafted cartesian product.
    var parameters =
        new ArrayList<>(
            List.of(
                new Object[] {ExecutorType.SAME_THREAD, StoreType.MEMORY},
                new Object[] {ExecutorType.CACHED_POOL, StoreType.MEMORY},
                new Object[] {ExecutorType.SAME_THREAD, StoreType.DISK},
                new Object[] {ExecutorType.CACHED_POOL, StoreType.DISK}));
    if (RedisStandaloneStoreContext.isAvailable()) {
      parameters.addAll(
          List.of(
              new Object[] {ExecutorType.SAME_THREAD, StoreType.REDIS_STANDALONE},
              new Object[] {ExecutorType.CACHED_POOL, StoreType.REDIS_STANDALONE}));
    }
    if (RedisClusterStoreContext.isAvailable()) {
      parameters.addAll(
          List.of(
              new Object[] {ExecutorType.SAME_THREAD, StoreType.REDIS_CLUSTER},
              new Object[] {ExecutorType.CACHED_POOL, StoreType.REDIS_CLUSTER}));
    }
    return parameters.toArray(Object[][]::new);
  }

  // The following tests don't apply to our unicast publisher. They're explicitly skipped as they
  // otherwise cause AbstractSubscription to spam the log with RejectedExecutionExceptions when
  // the second subscriber creation fails (leading to closing the store), while there are still
  // ongoing reads for the first subscriber that trigger a drain task when finished.

  @Override
  public void optional_spec111_maySupportMultiSubscribe() {
    throw new SkipException("not a multicast publisher");
  }

  @Override
  public void
      optional_spec111_multicast_mustProduceTheSameElementsInTheSameSequenceToAllOfItsSubscribersWhenRequestingOneByOne() {
    throw new SkipException("not a multicast publisher");
  }

  @Override
  public void
      optional_spec111_multicast_mustProduceTheSameElementsInTheSameSequenceToAllOfItsSubscribersWhenRequestingManyUpfront() {
    throw new SkipException("not a multicast publisher");
  }

  @Override
  public void
      optional_spec111_multicast_mustProduceTheSameElementsInTheSameSequenceToAllOfItsSubscribersWhenRequestingManyUpfrontAndCompleteAsExpected() {
    throw new SkipException("not a multicast publisher");
  }

  @Override
  public void optional_spec111_registeredSubscribersMustReceiveOnNextOrOnCompleteSignals() {
    throw new SkipException("not a multicast publisher");
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
    private boolean completed;

    LimitingSubscriber(Subscriber<T> downstream, long elements) {
      this.downstream = downstream;
      this.elements = elements;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      downstream.onSubscribe(subscription);
      this.upstream = subscription;
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
        completed = true;
        downstream.onComplete();
      }
    }

    @Override
    public void onError(Throwable throwable) {
      if (!completed) {
        downstream.onError(throwable);
      }
    }

    @Override
    public void onComplete() {
      if (!completed) {
        downstream.onComplete();
      }
    }
  }
}
