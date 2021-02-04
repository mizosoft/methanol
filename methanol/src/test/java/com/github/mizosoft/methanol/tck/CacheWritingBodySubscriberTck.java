package com.github.mizosoft.methanol.tck;

import static com.github.mizosoft.methanol.testing.StoreConfig.StoreType.DISK;
import static com.github.mizosoft.methanol.testing.StoreConfig.StoreType.MEMORY;
import static com.github.mizosoft.methanol.testutils.TestUtils.EMPTY_BUFFER;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.cache.CacheWritingBodySubscriber;
import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.flow.ForwardingBodySubscriber;
import com.github.mizosoft.methanol.testing.ResolvedStoreConfig;
import com.github.mizosoft.methanol.testing.StoreConfig.StoreType;
import com.github.mizosoft.methanol.testing.StoreContext;
import com.github.mizosoft.methanol.testutils.TestException;
import com.github.mizosoft.methanol.testutils.TestUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow.Processor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.reactivestreams.tck.flow.IdentityFlowProcessorVerification;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Factory;

public class CacheWritingBodySubscriberTck
    extends IdentityFlowProcessorVerification<List<ByteBuffer>> {
  private final ResolvedStoreConfig storeConfig;

  private ExecutorService publisherExecutorService;
  private StoreContext storeContext;

  @Factory(dataProvider = "provider")
  public CacheWritingBodySubscriberTck(StoreType storeType) {
    super(TckUtils.testEnvironment());
    storeConfig = ResolvedStoreConfig.createDefault(storeType);
  }

  // Some tests go nuts if cancellation is not forwarded upstream
  @BeforeClass
  static void configureUpstreamCancellation() {
    System.setProperty(
        "com.github.mizosoft.methanol.internal.cache.CacheWritingBodySubscriber.propagateCancellation",
        String.valueOf(true));
  }

  @BeforeMethod
  public void setUpPublisherExecutor() {
    publisherExecutorService = TckUtils.fixedThreadPool();
  }

  @AfterMethod
  public void tearDown() throws Exception {
    TestUtils.shutdown(publisherExecutorService);
    if (storeContext != null) {
      storeContext.close();
    }
  }

  @Override
  protected Publisher<List<ByteBuffer>> createFailedFlowPublisher() {
    var processor =
        new ProcessorView(new CacheWritingBodySubscriber(DisabledEditor.INSTANCE, EMPTY_BUFFER));
    processor.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    processor.onError(new TestException());
    return processor;
  }

  @Override
  protected Processor<List<ByteBuffer>, List<ByteBuffer>> createIdentityFlowProcessor(
      int bufferSize) {
    Editor editor;
    try {
      storeContext = storeConfig.createContext();

      var store = storeContext.newStore();
      editor = requireNonNull(store.edit("e1"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new ProcessorView(new CacheWritingBodySubscriber(editor, EMPTY_BUFFER));
  }

  @Override
  public ExecutorService publisherExecutorService() {
    return publisherExecutorService;
  }

  @Override
  public List<ByteBuffer> createElement(int element) {
    var buffer = UTF_8.encode("element_" + Integer.toHexString(element));
    return Stream.generate(buffer::duplicate).limit(3).collect(Collectors.toUnmodifiableList());
  }

  @Override
  public long maxSupportedSubscribers() {
    return 1; // Only bound to one subscriber
  }

  @DataProvider
  public static Object[][] provider() {
    return new Object[][] {{DISK}, {MEMORY}};
  }

  private static final class ProcessorView
      extends ForwardingBodySubscriber<Publisher<List<ByteBuffer>>>
      implements Processor<List<ByteBuffer>, List<ByteBuffer>> {
    ProcessorView(CacheWritingBodySubscriber bodySubscriber) {
      super(bodySubscriber);
    }

    @Override
    public void subscribe(Subscriber<? super List<ByteBuffer>> subscriber) {
      getBody().toCompletableFuture().join().subscribe(subscriber);
    }
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
