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

import static com.github.mizosoft.methanol.testing.TestUtils.EMPTY_BUFFER;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.reactivestreams.FlowAdapters.toFlowPublisher;

import com.github.mizosoft.methanol.internal.cache.CacheWritingPublisher;
import com.github.mizosoft.methanol.internal.cache.CacheWritingPublisher.Listener;
import com.github.mizosoft.methanol.internal.cache.Store;
import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.cache.Store.EntryWriter;
import com.github.mizosoft.methanol.testing.FailingPublisher;
import com.github.mizosoft.methanol.testing.Logging;
import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.TestUtils;
import com.github.mizosoft.methanol.testing.junit.RedisClusterStoreContext;
import com.github.mizosoft.methanol.testing.junit.RedisStandaloneStoreContext;
import com.github.mizosoft.methanol.testing.junit.StoreConfig;
import com.github.mizosoft.methanol.testing.junit.StoreConfig.StoreType;
import com.github.mizosoft.methanol.testing.junit.StoreContext;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.reactivestreams.example.unicast.AsyncIterablePublisher;
import org.reactivestreams.tck.flow.FlowPublisherVerification;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Factory;

public class CacheWritingPublisherTck extends FlowPublisherVerification<List<ByteBuffer>> {
  static {
    Logging.disable(CacheWritingPublisher.class);
  }

  private static final AtomicInteger entryId = new AtomicInteger();

  private final StoreConfig storeConfig;

  private Executor executor;
  private StoreContext storeContext;
  private Store store;

  @Factory(dataProvider = "provider")
  public CacheWritingPublisherTck(StoreType storeType) {
    super(TckUtils.testEnvironment());
    storeConfig = StoreConfig.createDefault(storeType);
  }

  @BeforeMethod
  public void setUpExecutor() throws IOException {
    executor = Executors.newCachedThreadPool();
    storeContext = StoreContext.from(storeConfig);
    store = storeContext.createAndRegisterStore();
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
    try {
      return new CacheWritingPublisher(
          toFlowPublisher(new AsyncIterablePublisher<>(() -> elementGenerator(elements), executor)),
          store.edit("test-entry-" + entryId.getAndIncrement()).orElseThrow(),
          EMPTY_BUFFER,
          executor,
          Listener.disabled(),
          true);
    } catch (IOException | InterruptedException e) {
      throw new CompletionException(e);
    }
  }

  @Override
  public Publisher<List<ByteBuffer>> createFailedFlowPublisher() {
    return new CacheWritingPublisher(
        new FailingPublisher<>(TestException::new),
        DisabledEditor.INSTANCE,
        EMPTY_BUFFER,
        executor);
  }

  private static Iterator<List<ByteBuffer>> elementGenerator(long elements) {
    return new Iterator<>() {
      private final List<ByteBuffer> items =
          Stream.of("Lorem ipsum dolor sit amet".split("\\s"))
              .map(UTF_8::encode)
              .collect(Collectors.toUnmodifiableList());

      private int index;
      private int generated;

      @Override
      public boolean hasNext() {
        return generated < elements;
      }

      @Override
      public List<ByteBuffer> next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        generated++;
        return List.of(item(index++), item(index++));
      }

      private ByteBuffer item(int index) {
        return items.get(index % items.size());
      }
    };
  }

  @DataProvider
  public static Object[][] provider() {
    var parameters =
        new ArrayList<>(List.of(new Object[] {StoreType.DISK}, new Object[] {StoreType.MEMORY}));
    if (RedisStandaloneStoreContext.isAvailable()) {
      parameters.add(new Object[] {StoreType.REDIS_STANDALONE});
    }
    if (RedisClusterStoreContext.isAvailable()) {
      parameters.add(new Object[] {StoreType.REDIS_CLUSTER});
    }
    return parameters.toArray(Object[][]::new);
  }

  private enum DisabledEditor implements Editor {
    INSTANCE;

    @Override
    public String key() {
      return "null-key";
    }

    @Override
    public EntryWriter writer() {
      return src -> {
        int remaining = src.remaining();
        src.position(src.position() + remaining);
        return remaining;
      };
    }

    @Override
    public void commit(ByteBuffer metadata) {}

    @Override
    public void close() {}
  }
}
