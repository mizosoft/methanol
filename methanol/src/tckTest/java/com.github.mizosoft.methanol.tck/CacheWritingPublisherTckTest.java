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

package com.github.mizosoft.methanol.tck;

import com.github.mizosoft.methanol.internal.cache.CacheWritingPublisher;
import com.github.mizosoft.methanol.internal.cache.CacheWritingPublisher.Listener;
import com.github.mizosoft.methanol.internal.cache.Store;
import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.testing.ExecutorContext;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.FailingPublisher;
import com.github.mizosoft.methanol.testing.IterablePublisher;
import com.github.mizosoft.methanol.testing.Logging;
import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.TestUtils;
import com.github.mizosoft.methanol.testing.store.RedisClusterStoreContext;
import com.github.mizosoft.methanol.testing.store.RedisStandaloneStoreContext;
import com.github.mizosoft.methanol.testing.store.StoreConfig;
import com.github.mizosoft.methanol.testing.store.StoreConfig.StoreType;
import com.github.mizosoft.methanol.testing.store.StoreContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.reactivestreams.tck.flow.FlowPublisherVerification;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

@Slow
@Test
public class CacheWritingPublisherTckTest extends FlowPublisherVerification<List<ByteBuffer>> {
  static {
    Logging.disable(CacheWritingPublisher.class);
  }

  private static final AtomicInteger entryId = new AtomicInteger();

  private final StoreConfig storeConfig;
  private final ExecutorType executorType;

  private ExecutorContext executorContext;
  private StoreContext storeContext;
  private Store store;

  @Factory(dataProvider = "provider")
  public CacheWritingPublisherTckTest(ExecutorType executorType, StoreType storeType) {
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
    storeContext.close();
  }

  @Override
  public Publisher<List<ByteBuffer>> createFlowPublisher(long elements) {
    return new CacheWritingPublisher(
        new IterablePublisher<>(
            () -> elementGenerator(elements),
            executorContext.createExecutor(ExecutorType.CACHED_POOL)),
        edit("e" + entryId.getAndIncrement()),
        TestUtils.EMPTY_BUFFER,
        executorContext.createExecutor(executorType),
        Listener.disabled());
  }

  @Override
  public Publisher<List<ByteBuffer>> createFailedFlowPublisher() {
    return new CacheWritingPublisher(
        new FailingPublisher<>(TestException::new),
        edit("e" + entryId.getAndIncrement()),
        TestUtils.EMPTY_BUFFER,
        executorContext.createExecutor(executorType));
  }

  private Editor edit(String key) {
    try {
      return store.edit(key).orElseThrow();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Iterator<List<ByteBuffer>> elementGenerator(long elements) {
    return Stream.generate(
            () ->
                Stream.generate(TckUtils::generateData)
                    .limit(TestUtils.BUFFERS_PER_LIST)
                    .collect(Collectors.toUnmodifiableList()))
        .limit(elements)
        .iterator();
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
}
