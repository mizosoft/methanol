package com.github.mizosoft.methanol.tck;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.mizosoft.methanol.internal.cache.CacheWritingBodySubscriber;
import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.flow.ForwardingBodySubscriber;
import com.github.mizosoft.methanol.testutils.TestException;
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
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Test
public class CacheWritingBodySubscriberTck
    extends IdentityFlowProcessorVerification<List<ByteBuffer>> {
  private ExecutorService publisherExecutorService;

  public CacheWritingBodySubscriberTck() {
    super(TckUtils.testEnvironment());
  }

  @BeforeMethod
  public void setUpPublisherExecutor() {
    publisherExecutorService = TckUtils.fixedThreadPool();
  }

  @AfterMethod
  public void shutdownPublisherExecutor() {
    publisherExecutorService.shutdown();
  }

  @Override
  protected Publisher<List<ByteBuffer>> createFailedFlowPublisher() {
    var processor = new ProcessorView(new CacheWritingBodySubscriber(DisabledEditor.INSTANCE));
    processor.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    processor.onError(new TestException());
    return processor;
  }

  @Override
  protected Processor<List<ByteBuffer>, List<ByteBuffer>> createIdentityFlowProcessor(
      int bufferSize) {
    return new ProcessorView(new CacheWritingBodySubscriber(DisabledEditor.INSTANCE));
  }

  @Override
  public ExecutorService publisherExecutorService() {
    return publisherExecutorService;
  }

  @Override
  public List<ByteBuffer> createElement(int element) {
    var buffer = UTF_8.encode(Integer.toHexString(element));
    return Stream.generate(buffer::duplicate).limit(3).collect(Collectors.toUnmodifiableList());
  }

  @Override
  public long maxSupportedSubscribers() {
    return 1; // Only bound to one subscriber
  }

  // Following tests expect processor's subscription.cancel() to be forwarded to upstream.
  // However, CacheWritingBodySubscriber ignores such cancellation and instead drains the
  // body into cache.

  @Override
  public void required_exerciseWhiteboxHappyPath() {
    throw new SkipException("cancellation is not forwarded to upstream");
  }

  @Override
  public void required_spec208_mustBePreparedToReceiveOnNextSignalsAfterHavingCalledSubscriptionCancel() {
    throw new SkipException("cancellation is not forwarded to upstream");
  }

  private enum DisabledEditor implements Editor {
    INSTANCE;

    @Override public String key() {
      return "null-key";
    }
    @Override public void metadata(ByteBuffer metadata) {}
    @Override public CompletableFuture<Integer> writeAsync(long position, ByteBuffer src) {
      return CompletableFuture.completedFuture(src.remaining());
    }
    @Override public Viewer view() { return null; }
    @Override public void discard() {}
    @Override public void close() {}
  }

  private static final class ProcessorView
      extends ForwardingBodySubscriber<Publisher<List<ByteBuffer>>>
      implements Processor<List<ByteBuffer>, List<ByteBuffer>> {
    ProcessorView(CacheWritingBodySubscriber bodySubscriber) {
      super(bodySubscriber);
    }

    @Override
    public void subscribe(Subscriber<? super List<ByteBuffer>> subscriber) {
      getBody().thenAccept(publisher -> publisher.subscribe(subscriber));
    }
  }
}
