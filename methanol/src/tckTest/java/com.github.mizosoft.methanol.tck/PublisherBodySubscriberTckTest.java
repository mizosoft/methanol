package com.github.mizosoft.methanol.tck;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.extensions.PublisherBodySubscriber;
import com.github.mizosoft.methanol.testing.ExecutorContext;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.FailingPublisher;
import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.TestUtils;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Processor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscription;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.reactivestreams.tck.flow.IdentityFlowProcessorVerification;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Test
public class PublisherBodySubscriberTckTest
    extends IdentityFlowProcessorVerification<List<ByteBuffer>> {
  private ExecutorContext executorContext;

  public PublisherBodySubscriberTckTest() {
    super(TckUtils.newTestEnvironment());
  }

  @BeforeMethod
  public void setMeUp() {
    executorContext = new ExecutorContext();
  }

  @AfterMethod
  public void tearMeDown() throws Exception {
    executorContext.close();
  }

  @Override
  protected Publisher<List<ByteBuffer>> createFailedFlowPublisher() {
    var processor = new ProcessorView();
    new FailingPublisher<List<ByteBuffer>>(TestException::new).subscribe(processor);
    return processor;
  }

  @Override
  protected Processor<List<ByteBuffer>, List<ByteBuffer>> createIdentityFlowProcessor(
      int bufferSize) {
    return new ProcessorView();
  }

  @Override
  public ExecutorService publisherExecutorService() {
    return (ExecutorService) executorContext.createExecutor(ExecutorType.CACHED_POOL);
  }

  @Override
  public List<ByteBuffer> createElement(int i) {
    return Stream.generate(() -> UTF_8.encode(Integer.toString(i)))
        .limit(TestUtils.BUFFERS_PER_LIST)
        .collect(Collectors.toUnmodifiableList());
  }

  @Override
  public long maxSupportedSubscribers() {
    return 1;
  }

  @Override
  public void
      required_spec313_cancelMustMakeThePublisherEventuallyDropAllReferencesToTheSubscriber() {
    throw new SkipException("Subscription is implemented by upstream");
  }

  private static final class ProcessorView
      implements Processor<List<ByteBuffer>, List<ByteBuffer>> {
    private final PublisherBodySubscriber publisherBodySubscriber = new PublisherBodySubscriber();

    ProcessorView() {}

    @Override
    public void subscribe(Flow.Subscriber<? super List<ByteBuffer>> subscriber) {
      requireNonNull(subscriber);
      publisherBodySubscriber.getBody().thenAccept(publisher -> publisher.subscribe(subscriber));
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      publisherBodySubscriber.onSubscribe(subscription);
    }

    @Override
    public void onNext(List<ByteBuffer> item) {
      publisherBodySubscriber.onNext(item);
    }

    @Override
    public void onError(Throwable throwable) {
      publisherBodySubscriber.onError(throwable);
    }

    @Override
    public void onComplete() {
      publisherBodySubscriber.onComplete();
    }
  }
}
