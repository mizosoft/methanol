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

package com.github.mizosoft.methanol.internal.dec;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.BodyDecoder;
import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.dec.AsyncDecoder.ByteSink;
import com.github.mizosoft.methanol.internal.dec.AsyncDecoder.ByteSource;
import com.github.mizosoft.methanol.internal.flow.Demand;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.flow.SchedulableSubscription;
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
import java.util.concurrent.atomic.AtomicReference;
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
// TODO consider making this API public (if native brotli decoder is to be added)
public class AsyncBodyDecoder<T> implements BodyDecoder<T> {

  private static final String BUFFER_SIZE_PROP = "com.github.mizosoft.methanol.dec.bufferSize";
  private static final int DEFAULT_BUFFER_SIZE = 8 * 1024; // 8Kb
  private static final int BUFFER_SIZE = getBufferSize();

  private static final List<ByteBuffer> COMPLETE = List.of(ByteBuffer.allocate(0));

  private final AsyncDecoder decoder;
  private final BodySubscriber<T> downstream;
  private final Executor executor;
  private final boolean userExecutor;
  private final UpstreamReference upstream;
  private final QueueByteSource source;
  private final StackByteSink sink;
  private final ConcurrentLinkedQueue<List<ByteBuffer>> decodedBuffers;
  private final int prefetch;
  private final int prefetchThreshold;
  private volatile @MonotonicNonNull SubscriptionImpl downstreamSubscription;
  private int upstreamWindow;

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
    upstream = new UpstreamReference();
    source = new QueueByteSource();
    sink = new StackByteSink();
    decodedBuffers = new ConcurrentLinkedQueue<>();
    prefetch = FlowSupport.prefetch();
    prefetchThreshold = FlowSupport.prefetchThreshold();
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
  public void onSubscribe(Subscription subscription) {
    requireNonNull(subscription);
    if (upstream.setOrCancel(subscription)) {
      SubscriptionImpl s = new SubscriptionImpl();
      downstreamSubscription = s;
      s.signal(); // Apply downstream's onSubscribe
      upstreamWindow = prefetch;
      subscription.request(prefetch);
    }
  }

  @Override
  public void onNext(List<ByteBuffer> buffers) {
    requireNonNull(buffers);
    source.push(buffers);
    try {
      decoder.decode(source, sink);
    } catch (Throwable t) {
      upstream.cancel();
      decoder.close();
      signalDownstream(t); // Notify downstream about the error
      return;
    }
    decrementWindow();
    if (sink.flush(decodedBuffers, false)) {
      signalDownstream(null); // Notify downstream there is new data
    }
  }

  @Override
  public void onError(Throwable throwable) {
    requireNonNull(throwable);
    complete(throwable);
  }

  @Override
  public void onComplete() {
    complete(null);
  }

  private void decrementWindow() {
    // Decrement current window and bring it back to
    // prefetch if became less than prefetchThreshold
    int update = upstreamWindow - 1;
    if (update <= prefetchThreshold) {
      upstreamWindow = prefetch;
      upstream.request(prefetch - update);
    } else {
      upstreamWindow = update;
    }
  }

  private void signalDownstream(@Nullable Throwable error) {
    SubscriptionImpl s = downstreamSubscription;
    if (s != null) {
      if (error != null) {
        s.signalError(error);
      } else {
        s.signal();
      }
    }
  }

  // Shared completion method for onComplete and onError
  private void complete(@Nullable Throwable error) {
    upstream.clear();
    try (decoder) {
      if (error == null) { // Normal completion
        // Acknowledge final source
        source.onComplete();
        decoder.decode(source, sink);
        if (source.hasRemaining()) {
          throw new IOException("input source not exhausted by the decoder");
        }
        // Flush any incomplete buffer and put completion signal
        sink.flush(decodedBuffers, true);
        decodedBuffers.offer(COMPLETE);
      }
    } catch (Throwable t) {
      error = t;
    }
    signalDownstream(error);
  }

  private static final class UpstreamReference {

    private final AtomicReference<@Nullable Subscription> upstream;

    private UpstreamReference() {
      this.upstream = new AtomicReference<>();
    }

    boolean setOrCancel(Subscription subscription) {
      if (!upstream.compareAndSet(null, subscription)) {
        subscription.cancel();
        return false;
      }
      return true;
    }

    void request(long n) {
      Subscription s = upstream.get();
      if (s != null) {
        s.request(n);
      }
    }

    void cancel() {
      Subscription s = upstream.getAndSet(FlowSupport.NOOP_SUBSCRIPTION);
      if (s != null) {
        s.cancel();
      }
    }

    void clear() {
      upstream.set(FlowSupport.NOOP_SUBSCRIPTION);
    }
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

    private List<ByteBuffer> slice(boolean includeNonFull) {
      // If last buffer is incomplete, it is only submitted
      // if includeNonFull is true provided it has some bytes.
      int size = sinkBuffers.size();
      if (size > 0) {
        int snapshotSize = size;
        ByteBuffer last = sinkBuffers.get(size - 1);
        if (last.hasRemaining()) {
          if (!includeNonFull || last.position() == 0) {
            snapshotSize--; // Do not include
          }
        }
        List<ByteBuffer> slice = sinkBuffers.subList(0, snapshotSize);
        List<ByteBuffer> snapshot = List.copyOf(slice);
        slice.clear(); // Drop references
        snapshot.forEach(ByteBuffer::flip); // Flip for downstream to read
        return snapshot;
      }
      return List.of();
    }
  }

  /** The subscription supplied downstream. */
  private final class SubscriptionImpl extends SchedulableSubscription {

    private final Demand demand;
    private volatile boolean cancelled;
    private boolean subscribed;
    private volatile @MonotonicNonNull Throwable pendingError;
    private @Nullable List<ByteBuffer> currentBatch;

    SubscriptionImpl() {
      super(executor);
      demand = new Demand();
    }

    @Override
    public void request(long n) {
      if (!cancelled) {
        if (n > 0 && demand.increase(n)) {
          signal();
        } else if (n <= 0) {
          upstream.cancel();
          signalError(FlowSupport.illegalRequest());
        }
      }
    }

    @Override
    public void cancel() {
      if (!cancelled) { // Races are OK
        upstream.cancel();
        decoder.close();
        cancelOwn();
      }
    }

    void signalError(Throwable error) {
      pendingError = error;
      runOrSchedule();
    }

    /** Only cancels this subscription in case upstream is already cancelled. */
    private void cancelOwn() {
      cancelled = true;
      stop();
    }

    @Override
    protected void drain() {
      Subscriber<? super List<ByteBuffer>> s = downstream;
      for (long e = 0L, d = demand.current(); !cancelled; ) {
        // Apply onSubscribe if this is the first signal
        if (!subscribed) {
          subscribed = true;
          try {
            s.onSubscribe(this);
          } catch (Throwable t) {
            cancel();
            s.onError(t);
            break;
          }
        }
        Throwable error = pendingError;
        if (error != null) {
          cancelOwn();
          s.onError(error);
        } else {
          // The local accumulation of emitted items to `e` helps in decreasing contention
          // over demand and maintains some sort of a keep-alive policy on active demand
          // updates (similar to what SubmissionPublisher does but unbounded)
          long f = emitItems(s, d);
          e += f;
          d = demand.current(); // Get fresh demand
          if (e == d || f == 0L) { // Need to flush `e` and potentially exit
            d = demand.decreaseAndGet(e);
            if (d == 0L || f == 0L) {
              break; // Either cancelled/completed or no items/demand-slots so give up
            }
            e = 0L; // Reset and continue emitting
          }
        }
      }
    }

    private long emitItems(Subscriber<? super List<ByteBuffer>> s, long d) {
      List<ByteBuffer> batch = currentBatch;
      for (long x = 0L; !cancelled; x++) {
        // The list is polled prematurely (before investigating demand). This
        // is done so that completion is passed downstream regardless of demand.
        if (batch == null) {
          batch = decodedBuffers.poll();
        }
        if (batch == COMPLETE) {
          cancelOwn();
          s.onComplete();
        } else if (batch != null && x < d) {
          try {
            s.onNext(batch);
          } catch (Throwable t) {
            cancel();
            s.onError(t);
          }
          batch = null;
        } else {
          currentBatch = batch; // Save last polled batch, might be non-null
          return x;
        }
      }
      return 0; // Cancelled or completed so it doesn't matter what was emitted
    }
  }

  static int getBufferSize() {
    int bufferSize = Utils.getIntProperty(BUFFER_SIZE_PROP, DEFAULT_BUFFER_SIZE);
    if (bufferSize <= 0) {
      bufferSize = DEFAULT_BUFFER_SIZE;
    }
    return bufferSize;
  }
}
