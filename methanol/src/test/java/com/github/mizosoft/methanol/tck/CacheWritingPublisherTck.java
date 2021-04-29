package com.github.mizosoft.methanol.tck;

import static com.github.mizosoft.methanol.testing.StoreConfig.StoreType.DISK;
import static com.github.mizosoft.methanol.testing.StoreConfig.StoreType.MEMORY;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.cache.CacheWritingPublisher;
import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.testing.ResolvedStoreConfig;
import com.github.mizosoft.methanol.testing.StoreConfig.StoreType;
import com.github.mizosoft.methanol.testing.StoreContext;
import com.github.mizosoft.methanol.testutils.FailedPublisher;
import com.github.mizosoft.methanol.testutils.TestException;
import com.github.mizosoft.methanol.testutils.TestUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.reactivestreams.FlowAdapters;
import org.reactivestreams.example.unicast.AsyncIterablePublisher;
import org.reactivestreams.tck.flow.FlowPublisherVerification;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Factory;

public class CacheWritingPublisherTck extends FlowPublisherVerification<List<ByteBuffer>> {
  private final ResolvedStoreConfig storeConfig;

  private Executor executor;
  private StoreContext storeContext;

  @Factory(dataProvider = "provider")
  public CacheWritingPublisherTck(StoreType storeType) {
    super(TckUtils.testEnvironment());
    storeConfig = ResolvedStoreConfig.createDefault(storeType);
  }

  // Some tests go nuts if cancellation is not forwarded upstream
  @BeforeClass
  static void configureUpstreamCancellation() {
    System.setProperty(
        "com.github.mizosoft.methanol.internal.cache.CacheWritingPublisher.propagateCancellation",
        String.valueOf(true));
  }

  @BeforeMethod
  public void setUpExecutor() {
    executor = TckUtils.fixedThreadPool();
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
    Editor editor;
    try {
      storeContext = storeConfig.createContext();

      var store = storeContext.newStore();
      editor = requireNonNull(store.edit("e1"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    return new CacheWritingPublisher(
        FlowAdapters.toFlowPublisher(
            new AsyncIterablePublisher<>(() -> elementGenerator(elements), executor)),
        editor);
  }

  @Override
  public Publisher<List<ByteBuffer>> createFailedFlowPublisher() {
    return new CacheWritingPublisher(
        new FailedPublisher<>(TestException::new), DisabledEditor.INSTANCE);
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
    return new Object[][] {{DISK}, {MEMORY}};
  }

  private enum DisabledEditor implements Editor {
    INSTANCE;

    @Override
    public String key() {
      return "null-key";
    }

    @Override
    public void metadata(ByteBuffer metadata) {}

    @Override
    public CompletableFuture<Integer> writeAsync(long position, ByteBuffer src) {
      return CompletableFuture.completedFuture(src.remaining());
    }

    @Override
    public void commitOnClose() {}

    @Override
    public void close() {}
  }
}
