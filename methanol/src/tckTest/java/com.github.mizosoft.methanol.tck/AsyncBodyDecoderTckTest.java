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

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.decoder.AsyncBodyDecoder;
import com.github.mizosoft.methanol.decoder.AsyncDecoder;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.testing.ExecutorContext;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import java.io.IOException;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow.Processor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.reactivestreams.tck.flow.IdentityFlowProcessorVerification;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Factory;

@Slow
public class AsyncBodyDecoderTckTest
    extends IdentityFlowProcessorVerification<List<AsyncBodyDecoderTckTest.ByteBufferHandle>> {
  private static final int BUFFERS_PER_LIST = 4;

  private final ExecutorType executorType;

  private ExecutorContext executorContext;

  @Factory(dataProvider = "provider")
  public AsyncBodyDecoderTckTest(ExecutorType executorType) {
    super(TckUtils.testEnvironment());
    this.executorType = executorType;
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
  protected Publisher<List<ByteBufferHandle>> createFailedFlowPublisher() {
    var processor =
        new ProcessorView(BadDecoder.INSTANCE, executorContext.createExecutor(executorType));
    processor.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    processor.onNext(List.of(new ByteBufferHandle(US_ASCII.encode("abc"))));
    processor.onComplete();
    return processor;
  }

  @Override
  protected Processor<List<ByteBufferHandle>, List<ByteBufferHandle>> createIdentityFlowProcessor(
      int bufferSize) {
    return new ProcessorView(
        IdentityDecoder.INSTANCE, executorContext.createExecutor(executorType));
  }

  @Override
  public ExecutorService publisherExecutorService() {
    return ((ExecutorService) executorContext.createExecutor(ExecutorType.CACHED_POOL));
  }

  @Override
  public List<ByteBufferHandle> createElement(int element) {
    var data = TckUtils.generateData();
    return Stream.generate(data::duplicate)
        .map(ByteBufferHandle::new)
        .limit(BUFFERS_PER_LIST)
        .collect(Collectors.toUnmodifiableList());
  }

  @Override
  public long maxSupportedSubscribers() {
    return 1; // Only bound to one subscriber.
  }

  /**
   * A {@code BodyDecoder} is bound to its downstream, all constituting part of the subscriber chain
   * up to the HTTP client. It is the responsibility of the client itself to drop references to the
   * top of the chain, allowing the rest of the chain to be GCed.
   */
  @Override
  public void
      required_spec313_cancelMustMakeThePublisherEventuallyDropAllReferencesToTheSubscriber() {
    throw new SkipException("Out of implementation's scope");
  }

  @DataProvider
  public static Object[][] provider() {
    return new Object[][] {{ExecutorType.CACHED_POOL}, {ExecutorType.SAME_THREAD}};
  }

  private enum IdentityDecoder implements AsyncDecoder {
    INSTANCE;

    @Override
    public String encoding() {
      return "identity";
    }

    @Override
    public void decode(ByteSource source, ByteSink sink) {
      while (source.hasRemaining()) {
        sink.pushBytes(source.currentSource());
      }
    }

    @Override
    public void close() {}
  }

  private enum BadDecoder implements AsyncDecoder {
    INSTANCE;

    @Override
    public String encoding() {
      return "badzip";
    }

    @Override
    public void decode(ByteSource source, ByteSink sink) throws IOException {
      throw new IOException("Ops! I forgot my encoding :(");
    }

    @Override
    public void close() {}
  }

  /**
   * Implements equivalence regardless of buffer position. This allows equating buffers before and
   * after being consumed.
   */
  static final class ByteBufferHandle {
    final ByteBuffer buffer;

    ByteBufferHandle(ByteBuffer buffer) {
      this.buffer = requireNonNull(buffer);
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof ByteBufferHandle
          && buffer.rewind().equals(((ByteBufferHandle) obj).buffer.rewind());
    }
  }

  /** Adapts a {@code AsyncBodyDecoder} into a processor. */
  private static final class ProcessorView
      implements Processor<List<ByteBufferHandle>, List<ByteBufferHandle>> {
    private final AsyncDecoder decoder;
    private final Executor executor;
    private final AtomicReference<AsyncBodyDecoder<Void>> bodyDecoderRef = new AtomicReference<>();
    private final Queue<Consumer<AsyncBodyDecoder<Void>>> signals = new LinkedList<>();

    ProcessorView(AsyncDecoder decoder, Executor executor) {
      this.decoder = decoder;
      this.executor = executor;
    }

    @Override
    public void subscribe(Subscriber<? super List<ByteBufferHandle>> subscriber) {
      requireNonNull(subscriber);
      var downstream =
          BodySubscribers.fromSubscriber(
              TckUtils.map(
                  subscriber,
                  buffers ->
                      buffers.stream()
                          .map(ByteBufferHandle::new)
                          .collect(Collectors.toUnmodifiableList())));
      var bodyDecoder = new AsyncBodyDecoder<>(decoder, downstream, executor, TckUtils.BUFFER_SIZE);
      if (bodyDecoderRef.compareAndSet(null, bodyDecoder)) {
        drainSignals();
      } else {
        FlowSupport.rejectMulticast(subscriber);
      }
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      putSignal(bodyDecoder -> bodyDecoder.onSubscribe(subscription));
    }

    @Override
    public void onNext(List<ByteBufferHandle> item) {
      putSignal(
          bodyDecoder ->
              bodyDecoder.onNext(
                  item.stream()
                      .map(handle -> handle.buffer)
                      .collect(Collectors.toUnmodifiableList())));
    }

    @Override
    public void onError(Throwable throwable) {
      putSignal(bodyDecoder -> bodyDecoder.onError(throwable));
    }

    @Override
    public void onComplete() {
      putSignal(Subscriber::onComplete);
    }

    private void putSignal(Consumer<AsyncBodyDecoder<Void>> signal) {
      signals.offer(signal);
      drainSignals();
    }

    private synchronized void drainSignals() {
      var bodyDecoder = bodyDecoderRef.get();
      if (bodyDecoder != null) {
        Consumer<AsyncBodyDecoder<Void>> signal;
        while ((signal = signals.poll()) != null) {
          signal.accept(bodyDecoder);
        }
      }
    }
  }
}
