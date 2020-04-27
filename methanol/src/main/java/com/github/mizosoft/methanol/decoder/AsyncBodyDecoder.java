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

package com.github.mizosoft.methanol.decoder;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.BodyDecoder;
import com.github.mizosoft.methanol.decoder.AsyncDecoder.ByteSink;
import com.github.mizosoft.methanol.decoder.AsyncDecoder.ByteSource;
import com.github.mizosoft.methanol.internal.flow.AbstractSubscription;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.flow.Prefetcher;
import com.github.mizosoft.methanol.internal.flow.Upstream;
import java.io.IOException;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An implementation of {@link BodyDecoder} that uses an {@link AsyncDecoder} for decompression. The
 * implementation disallows left-over bytes after the final source is acknowledged, completing the
 * downstream with an {@code IOException} on such case. The decoder is closed on either normal or
 * exceptional completion or on cancellation from downstream.
 *
 * @param <T> the body type
 */
@SuppressWarnings("ReferenceEquality") // ByteBuffer sentinel values
public final class AsyncBodyDecoder<T> implements BodyDecoder<T> {

  private static final String BUFFER_SIZE_PROP =
      "com.github.mizosoft.methanol.decoder.AsyncBodyDecoder.bufferSize";
  private static final int DEFAULT_BUFFER_SIZE = 8 * 1024; // 8Kb
  private static final int BUFFER_SIZE = getBufferSize();

  private static final List<ByteBuffer> COMPLETE = List.of(ByteBuffer.allocate(0));

  private final AsyncDecoder decoder;
  private final BodySubscriber<T> downstream;
  private final Executor executor;
  private final boolean userExecutor;
  private final Upstream upstream;
  private final Prefetcher prefetcher;
  private final QueueByteSource source;
  private final StackByteSink sink;
  private final ConcurrentLinkedQueue<List<ByteBuffer>> decodedBuffers;
  private volatile @MonotonicNonNull SubscriptionImpl downstreamSubscription;

  /**
   * Creates an {@code AsyncBodyDecoder} in sync mode.
   *
   * @param decoder the decoder
   * @param downstream the downstream subscriber
   */
  public AsyncBodyDecoder(AsyncDecoder decoder, BodySubscriber<T> downstream) {
    this(decoder, downstream, FlowSupport.SYNC_EXECUTOR, false);
  }

  /**
   * Creates an {@code AsyncBodyDecoder} that supplies downstream items in the given executor.
   *
   * @param decoder the decoder
   * @param downstream the downstream subscriber
   * @param executor the executor
   */
  public AsyncBodyDecoder(AsyncDecoder decoder, BodySubscriber<T> downstream, Executor executor) {
    this(decoder, downstream, executor, true);
  }

  private AsyncBodyDecoder(
      AsyncDecoder decoder, BodySubscriber<T> downstream, Executor executor, boolean userExecutor) {
    this.decoder = requireNonNull(decoder, "decoder");
    this.downstream = requireNonNull(downstream, "downstream");
    this.executor = requireNonNull(executor, "executor");
    this.userExecutor = userExecutor;
    upstream = new Upstream();
    prefetcher = new Prefetcher();
    source = new QueueByteSource();
    sink = new StackByteSink();
    decodedBuffers = new ConcurrentLinkedQueue<>();
  }

  /** Returns the underlying {@code AsyncDecoder}. */
  public AsyncDecoder asyncDecoder() {
    return decoder;
  }

  @Override
  public String encoding() {
    return decoder.encoding();
  }

  @Override
  public Optional<Executor> executor() {
    return userExecutor ? Optional.of(executor) : Optional.empty();
  }

  @Override
  public BodySubscriber<T> downstream() {
    return downstream;
  }

  @Override
  public void onSubscribe(Subscription upstreamSubscription) {
    requireNonNull(upstreamSubscription);
    if (upstream.setOrCancel(upstreamSubscription)) {
      SubscriptionImpl subscription = new SubscriptionImpl();
      downstreamSubscription = subscription;
      subscription.signal(true); // Apply downstream's onSubscribe
      prefetcher.initialize(upstream);
    }
  }

  @Override
  public void onNext(List<ByteBuffer> buffers) {
    requireNonNull(buffers);
    source.push(buffers);
    try {
      decoder.decode(source, sink);
    } catch (Throwable t) {
      upstream.cancel(); // flow is interrupted
      onError(t);
      return;
    }
    prefetcher.update(upstream);
    SubscriptionImpl subscription = downstreamSubscription;
    if (sink.flush(decodedBuffers, false) && subscription != null) {
      subscription.signal(false); // Notify downstream there is new data
    }
  }

  @Override
  public void onError(Throwable throwable) {
    requireNonNull(throwable);
    upstream.clear();
    SubscriptionImpl subscription = downstreamSubscription;
    if (subscription != null) {
      subscription.signalError(throwable);
    }
  }

  @Override
  public void onComplete() {
    upstream.clear();
    SubscriptionImpl subscription = downstreamSubscription;
    try (decoder) {
      // Acknowledge final decode round
      source.onComplete();
      decoder.decode(source, sink);
      if (source.hasRemaining()) {
        throw new IOException("unexhausted bytes after final source: " + source.remaining());
      }
      // Flush any incomplete buffer and put completion signal
      sink.flush(decodedBuffers, true);
      decodedBuffers.offer(COMPLETE);
      if (subscription != null) {
        subscription.signal(true);
      }
    } catch (Throwable t) {
      if (subscription != null) {
        subscription.signalError(t);
      }
    }
  }

  private static int getBufferSize() {
    int bufferSize = Integer.getInteger(BUFFER_SIZE_PROP, DEFAULT_BUFFER_SIZE);
    if (bufferSize <= 0) {
      bufferSize = DEFAULT_BUFFER_SIZE;
    }
    return bufferSize;
  }

  /**
   * A {@code ByteSource} that maintains an appendable queue of {@code ByteBuffers} where fully
   * consumed ones are polled.
   */
  private static final class QueueByteSource implements ByteSource {

    private static final ByteBuffer NO_INPUT = ByteBuffer.allocate(0);

    private final List<ByteBuffer> sourceBuffers;
    private boolean completed;

    QueueByteSource() {
      sourceBuffers = new ArrayList<>();
    }

    @Override
    public ByteBuffer currentSource() {
      while (sourceBuffers.size() > 0) {
        ByteBuffer peek = sourceBuffers.get(0);
        if (peek.hasRemaining()) {
          return peek;
        }
        sourceBuffers.remove(0);
      }
      return NO_INPUT;
    }

    @Override
    public long remaining() {
      long sum = 0;
      for (ByteBuffer b : sourceBuffers) {
        sum += b.remaining();
      }
      return sum;
    }

    @Override
    public boolean hasRemaining() {
      return currentSource() != NO_INPUT;
    }

    @Override
    public boolean finalSource() {
      return completed;
    }

    void push(List<ByteBuffer> buffers) {
      sourceBuffers.addAll(buffers);
    }

    void onComplete() {
      completed = true;
    }
  }

  /**
   * A {@code ByteSink} that maintains a stack of {@code ByteBuffers} each allocated and pushed when
   * current head becomes full.
   */
  private static final class StackByteSink implements ByteSink {

    private final List<ByteBuffer> sinkBuffers;

    StackByteSink() {
      sinkBuffers = new ArrayList<>();
    }

    @Override
    public ByteBuffer currentSink() {
      int size = sinkBuffers.size();
      ByteBuffer last = size > 0 ? sinkBuffers.get(size - 1) : null;
      if (last == null || !last.hasRemaining()) {
        last = ByteBuffer.allocate(BUFFER_SIZE);
        sinkBuffers.add(last);
      }
      return last;
    }

    boolean flush(ConcurrentLinkedQueue<List<ByteBuffer>> queue, boolean finished) {
      List<ByteBuffer> batch = slice(finished);
      if (!batch.isEmpty()) {
        queue.offer(batch);
        return true;
      }
      return false;
    }

    private List<ByteBuffer> slice(boolean finished) {
      if (sinkBuffers.isEmpty()) {
        return List.of();
      }
      // If last buffer is incomplete, it is only submitted
      // if finished is true provided it has some bytes.
      int size = sinkBuffers.size();
      int snapshotSize = size;
      ByteBuffer last = sinkBuffers.get(size - 1);
      if (last.hasRemaining() && (!finished || last.position() == 0)) {
        snapshotSize--; // Do not submit
      }
      List<ByteBuffer> slice = sinkBuffers.subList(0, snapshotSize);
      List<ByteBuffer> snapshot =
          slice.stream()
              .map(ByteBuffer::asReadOnlyBuffer)
              .collect(Collectors.toUnmodifiableList());
      snapshot.forEach(ByteBuffer::flip); // Flip for downstream to read
      slice.clear(); // Drop references
      return snapshot;
    }
  }

  /** The subscription supplied downstream. */
  private final class SubscriptionImpl extends AbstractSubscription<List<ByteBuffer>> {

    private @Nullable List<ByteBuffer> currentBatch;

    SubscriptionImpl() {
      super(downstream, executor);
    }

    @Override
    protected long emit(Subscriber<? super List<ByteBuffer>> downstream, long emit) {
      // List is polled prematurely to detect completion regardless of demand
      List<ByteBuffer> batch = currentBatch;
      currentBatch = null;
      if (batch == null) {
        batch = decodedBuffers.poll();
      }
      long submitted = 0L;
      while (true) {
        if (batch == COMPLETE) {
          cancelOnComplete(downstream);
          return 0;
        } else if (submitted >= emit || batch == null) { // exhausted either demand or batches
          currentBatch = batch; // might be non-null
          return submitted;
        } else if (submitOnNext(downstream, batch)) {
          submitted++;
          batch = decodedBuffers.poll(); // get next batch and continue
        } else {
          return 0;
        }
      }
    }

    @Override
    protected void abort(boolean flowInterrupted) {
      if (flowInterrupted) {
        upstream.cancel();
      } else {
        upstream.clear();
      }
      decoder.close();
      decodedBuffers.clear();
    }
  }
}
