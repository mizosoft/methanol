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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.reactivestreams.tck.flow.FlowPublisherVerification;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

@Test
public class CacheReadingPublisherTest extends FlowPublisherVerification<List<ByteBuffer>> {
  private static final int BUFFER_SIZE = 8 * 1024;
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
   * opened viewer are closed after each test.
   */
  private final List<Viewer> openedViewers = new ArrayList<>();

  @Factory(dataProvider = "provider")
  public CacheReadingPublisherTest(ExecutorType executorType, StoreType storeType) {
    super(TckUtils.testEnvironmentWithTimeout(1000));
    this.executorType = executorType;
    this.storeConfig = StoreConfig.createDefault(storeType);
  }

  @BeforeMethod
  public void setUpExecutor() throws IOException {
    executorContext = new ExecutorContext();
    storeContext = StoreContext.from(storeConfig);
    store = storeContext.createAndRegisterStore();
  }

  @AfterMethod
  public void tearDown() throws Exception {
    executorContext.close();
    for (var viewer : openedViewers) {
      viewer.close();
    }
    openedViewers.clear();
    storeContext.close();
  }

  @Override
  public Publisher<List<ByteBuffer>> createFlowPublisher(long elements) {
    var viewer = populateThenViewEntry(elements);
    openedViewers.add(viewer);

    // Limit published items to `elements`.
    var publisher = new CacheReadingPublisher(viewer, executorContext.createExecutor(executorType));
    return subscriber ->
        publisher.subscribe(
            subscriber != null ? new LimitingSubscriber<>(subscriber, elements) : null);
  }

  private Viewer populateThenViewEntry(long elements) {
    try {
      var entryName = "test-entry-" + entryId.getAndIncrement();
      try (var editor = store.edit(entryName).orElseThrow()) {
        for (var buffer : generateData(elements)) {
          editor.writer().write(buffer);
        }
        editor.commit(ByteBuffer.allocate(1));
      }
      return store.view(entryName).orElseThrow();
    } catch (IOException | InterruptedException e) {
      throw new CompletionException(e);
    }
  }

  private List<ByteBuffer> generateData(long elements) {
    var buffer = ByteBuffer.allocate(BUFFER_SIZE);
    ThreadLocalRandom.current()
        .ints(BUFFER_SIZE, 0x20, 0x7f) // ASCII VCHARS.
        .forEach(i -> buffer.put((byte) i));
    buffer.flip();
    return elements > 0
        ? Stream.generate(buffer::duplicate)
            .limit(MAX_BATCH_SIZE * elements) // Produce `elements` items at minimum.
            .collect(Collectors.toUnmodifiableList())
        : List.of();
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
  // the second subscriber creation fails while there are still ongoing reads for the first
  // subscriber.

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
