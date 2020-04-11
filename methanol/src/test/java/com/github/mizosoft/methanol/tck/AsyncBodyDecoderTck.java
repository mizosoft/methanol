/*
 * Copyright (c) 2019, 2020 Moataz Abdelnasser
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

import com.github.mizosoft.methanol.dec.AsyncBodyDecoder;
import com.github.mizosoft.methanol.dec.AsyncDecoder;
import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.tck.AsyncBodyDecoderTck.BufferListHandle;
import com.github.mizosoft.methanol.testutils.TestUtils;
import java.io.IOException;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow.Processor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.reactivestreams.tck.flow.IdentityFlowProcessorVerification;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test
public class AsyncBodyDecoderTck extends IdentityFlowProcessorVerification<BufferListHandle> {

  private static final int BUFFERS_PER_LIST = 5;
  private static final int BUFFER_SIZE = 64;

  private ExecutorService publisherExecutorService; // used by test publisher

  public AsyncBodyDecoderTck() {
    super(TckUtils.testEnvironment());
  }

  // Used by AsyncBodyDecoder, overridden by AsyncBodyDecoderWithExecutorTck for async version
  Executor decoderExecutor() {
    return null;
  }

  @BeforeClass
  public void setUpPublisherExecutor() {
    publisherExecutorService = Executors.newFixedThreadPool(8);
  }

  @AfterClass
  public void shutdownPublisherExecutor() {
    TestUtils.shutdown(publisherExecutorService);
  }

  @BeforeClass
  public static void setUpBufferSize() {
    // Make sure AsyncBodyDecoder allocates same buffer sizes
    System.setProperty("com.github.mizosoft.methanol.dec.bufferSize",
        Integer.toString(BUFFER_SIZE));
  }

  @Override
  protected Publisher<BufferListHandle> createFailedFlowPublisher() {
    var processor = new AsyncBodyDecoderProcessorView(BadDecoder.INSTANCE, decoderExecutor());
    processor.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    processor.onNext(new BufferListHandle(List.of(US_ASCII.encode("Test buffer"))));
    processor.onComplete();
    return processor;
  }

  @Override
  protected Processor<BufferListHandle, BufferListHandle> createIdentityFlowProcessor(
      int bufferSize) {
    return new AsyncBodyDecoderProcessorView(IdentityDecoder.INSTANCE, decoderExecutor());
  }

  @Override
  public ExecutorService publisherExecutorService() {
    return requireNonNull(publisherExecutorService);
  }

  @Override
  public BufferListHandle createElement(int element) {
    var batch = ByteBuffer.allocate(BUFFER_SIZE);
    var hint = US_ASCII.encode(Integer.toHexString(element));
    while (batch.hasRemaining()) {
      Utils.copyRemaining(hint.rewind(), batch);
    }
    batch.flip();
    // Just duplicate buffers to get a list
    return new BufferListHandle(
        Stream.generate(batch::duplicate)
            .limit(BUFFERS_PER_LIST)
            .collect(Collectors.toUnmodifiableList()));
  }

  @Override
  public long maxSupportedSubscribers() {
    return 1; // Only bound to one subscriber
  }

  /**
   * Skipped test. A {@code BodyDecoder} is bound to it's downstream, and it's a part of a
   * subscriber chain to the HTTP client. Where it is the responsibility of the client itself to
   * drop references to the tip of the chain, allowing the rest of the chain to be GCed.
   */
  @Override
  public void
  required_spec313_cancelMustMakeThePublisherEventuallyDropAllReferencesToTheSubscriber() {
    throw new SkipException("Out of implementation's scope");
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
    public void close() {
    }
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
    public void close() {
    }
  }

  // The tck uses Object::equals which won't work on ByteBuffers as upstream buffers
  // become empty after consumption. So this class is used instead of List<ByteBuffer>
  // to rewind buffers before comparison.
  static final class BufferListHandle {

    final List<ByteBuffer> buffers;

    BufferListHandle(List<ByteBuffer> buffers) {
      this.buffers = buffers;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof BufferListHandle)) {
        return false;
      }
      var thisList = buffers;
      var thatList = ((BufferListHandle) obj).buffers;
      thisList.forEach(ByteBuffer::rewind);
      thatList.forEach(ByteBuffer::rewind);
      return thisList.equals(thatList);
    }
  }

  /**
   * Adapts a {@code AsyncBodyDecoder} into a processor.
   */
  private static final class AsyncBodyDecoderProcessorView
      implements Processor<BufferListHandle, BufferListHandle> {

    private final AsyncDecoder decoder;
    private final @Nullable Executor executor;
    private final AtomicReference<AsyncBodyDecoder<Void>> bodyDecoderRef;
    private final Queue<Consumer<AsyncBodyDecoder<Void>>> signals;

    private AsyncBodyDecoderProcessorView(
        AsyncDecoder decoder,
        @Nullable Executor executor) {
      this.decoder = decoder;
      this.executor = executor;
      bodyDecoderRef = new AtomicReference<>();
      signals = new LinkedList<>();
    }

    @Override
    public void subscribe(Subscriber<? super BufferListHandle> subscriber) {
      requireNonNull(subscriber);
      var mappingSubscriber = new Subscriber<List<ByteBuffer>>() {
        @Override public void onSubscribe(Subscription subscription) {
          subscriber.onSubscribe(subscription);
        }
        @Override public void onNext(List<ByteBuffer> item) {
          subscriber.onNext(new BufferListHandle(item));
        }
        @Override public void onError(Throwable throwable) {
          subscriber.onError(throwable);
        }
        @Override public void onComplete() {
          subscriber.onComplete();
        }
      };
      var downstream = BodySubscribers.fromSubscriber(mappingSubscriber);
      var bodyDecoder = executor != null
          ? new AsyncBodyDecoder<>(decoder, downstream, executor)
          : new AsyncBodyDecoder<>(decoder, downstream);
      if (bodyDecoderRef.compareAndSet(null, bodyDecoder)) {
        drainSignals();
      } else {
        Throwable error = new IllegalStateException("Already subscribed");
        try {
          subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
        } catch (Throwable t) {
          error.addSuppressed(t);
        } finally {
          subscriber.onError(error);
        }
      }
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      putSignal(dec -> dec.onSubscribe(subscription));
    }

    @Override
    public void onNext(BufferListHandle item) {
      putSignal(dec -> dec.onNext(item.buffers));
    }

    @Override
    public void onError(Throwable throwable) {
      putSignal(dec -> dec.onError(throwable));
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
      var dec = bodyDecoderRef.get();
      if (dec != null) {
        Consumer<AsyncBodyDecoder<Void>> signal;
        while ((signal = signals.poll()) != null) {
          signal.accept(dec);
        }
      }
    }
  }
}
