/*
 * Copyright (c) 2024 Moataz Hussein
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

import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.BodyDecoder;
import com.github.mizosoft.methanol.decoder.AsyncDecoder.ByteSink;
import com.github.mizosoft.methanol.decoder.AsyncDecoder.ByteSource;
import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.flow.AbstractQueueSubscription;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.flow.Prefetcher;
import com.github.mizosoft.methanol.internal.flow.Upstream;
import java.io.IOException;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Subscription;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An implementation of {@link BodyDecoder} that uses an {@link AsyncDecoder} for decompression. The
 * implementation disallows left-over bytes after the final source is acknowledged, completing the
 * downstream with an {@code IOException} on such case. The decoder is closed on either normal or
 * exceptional completion or on cancellation from downstream.
 */
@SuppressWarnings("ReferenceEquality") // ByteBuffer sentinels.
public final class AsyncBodyDecoder<T> implements BodyDecoder<T> {
  private final AsyncDecoder decoder;
  private final BodySubscriber<T> downstream;
  private final Executor executor;
  private final boolean isDefaultExecutor;

  private final Upstream upstream = new Upstream();
  private final Prefetcher prefetcher = new Prefetcher();
  private final QueueByteSource source = new QueueByteSource();
  private final StackByteSink sink;

  private @MonotonicNonNull SubscriptionImpl downstreamSubscription;

  /**
   * Whether this subscriber has been completed. That's good to know as there's a case where we
   * complete ourselves (if the decoder throws), and upstream might not detect cancellation right
   * away.
   */
  private boolean completed;

  /** Creates an {@code AsyncBodyDecoder} in sync mode. */
  public AsyncBodyDecoder(AsyncDecoder decoder, BodySubscriber<T> downstream) {
    this(decoder, downstream, FlowSupport.SYNC_EXECUTOR, false, Utils.BUFFER_SIZE);
  }

  /** Creates an {@code AsyncBodyDecoder} that supplies downstream items in the given executor. */
  public AsyncBodyDecoder(AsyncDecoder decoder, BodySubscriber<T> downstream, Executor executor) {
    this(decoder, downstream, executor, true, Utils.BUFFER_SIZE);
  }

  /** Creates an {@code AsyncBodyDecoder} that supplies downstream items in the given executor. */
  public AsyncBodyDecoder(
      AsyncDecoder decoder, BodySubscriber<T> downstream, Executor executor, int bufferSize) {
    this(decoder, downstream, executor, true, bufferSize);
  }

  private AsyncBodyDecoder(
      AsyncDecoder decoder,
      BodySubscriber<T> downstream,
      Executor executor,
      boolean isDefaultExecutor,
      int bufferSize) {
    this.decoder = requireNonNull(decoder);
    this.downstream = requireNonNull(downstream);
    this.executor = requireNonNull(executor);
    this.isDefaultExecutor = isDefaultExecutor;
    this.sink = new StackByteSink(bufferSize);
    requireArgument(bufferSize > 0, "Expected a positive buffer size: %d", bufferSize);
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
    return isDefaultExecutor ? Optional.of(executor) : Optional.empty();
  }

  @Override
  public BodySubscriber<T> downstream() {
    return downstream;
  }

  @Override
  public void onSubscribe(Subscription upstreamSubscription) {
    requireNonNull(upstreamSubscription);
    if (upstream.setOrCancel(upstreamSubscription)) {
      var subscription = new SubscriptionImpl();
      downstreamSubscription = subscription;
      prefetcher.initialize(upstream);
      subscription.fireOrKeepAlive(); // Call downstream's onSubscribe().
    }
  }

  @Override
  public void onNext(List<ByteBuffer> buffers) {
    requireNonNull(buffers);
    if (completed) {
      return;
    }

    source.push(buffers);
    try {
      decoder.decode(source, sink);
    } catch (Throwable t) {
      upstream.cancel();
      onError(t);
      return;
    }

    // If sink::slice results in an empty list, we'd still want to push it to the queue so that poll() knows we received
    // a corresponding item & updates the prefetch policy correctly.
    subscription().submit(sink.slice(false));
  }

  @Override
  public void onError(Throwable throwable) {
    requireNonNull(throwable);
    if (completed) {
      return;
    }

    completed = true;
    upstream.clear();
    subscription().fireOrKeepAliveOnError(throwable);
  }

  @Override
  public void onComplete() {
    if (completed) {
      return;
    }

    completed = true;
    upstream.clear();
    try (decoder) {
      // Acknowledge final decode round.
      source.complete();
      decoder.decode(source, sink);
      if (source.hasRemaining()) {
        throw new IOException("un-exhausted bytes after final source: " + source.remaining());
      }
    } catch (IOException e) {
      subscription().fireOrKeepAliveOnError(e);
      return;
    }

    var remaining = sink.slice(true);
    if (remaining.isEmpty()) {
      subscription().complete();
    } else {
      subscription().submitAndComplete(remaining);
    }
  }

  private SubscriptionImpl subscription() {
    var subscription = downstreamSubscription;
    requireState(subscription != null, "onSubscribe() expected");
    return subscription;
  }

  /** A {@code ByteSource} that maintains a queue of buffers consumed sequentially. */
  private static final class QueueByteSource implements ByteSource {
    private static final ByteBuffer NO_INPUT = ByteBuffer.allocate(0);

    private final Queue<ByteBuffer> sourceBuffers = new ArrayDeque<>();
    private boolean complete;

    QueueByteSource() {}

    @Override
    public ByteBuffer currentSource() {
      ByteBuffer head;
      while ((head = sourceBuffers.peek()) != null && !head.hasRemaining()) {
        sourceBuffers.remove();
      }
      return head != null ? head : NO_INPUT;
    }

    @Override
    public long remaining() {
      long sum = 0;
      for (var b : sourceBuffers) {
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
      return complete;
    }

    void push(List<ByteBuffer> buffers) {
      sourceBuffers.addAll(buffers);
    }

    void complete() {
      complete = true;
    }
  }

  /** A {@code ByteSink} that maintains a stack of buffers that are allocated when needed. */
  private static final class StackByteSink implements ByteSink {
    private final List<ByteBuffer> sinkBuffers = new ArrayList<>();

    private final int bufferSize;

    StackByteSink(int bufferSize) {
      this.bufferSize = bufferSize;
    }

    @Override
    public ByteBuffer currentSink() {
      int size = sinkBuffers.size();
      var last = size > 0 ? sinkBuffers.get(size - 1) : null;
      if (last == null || !last.hasRemaining()) {
        last = ByteBuffer.allocate(bufferSize);
        sinkBuffers.add(last);
      }
      return last;
    }

    List<ByteBuffer> slice(boolean finished) {
      if (sinkBuffers.isEmpty()) {
        return List.of();
      }

      // If the last buffer is incomplete, it is only submitted if finished is true provided it has
      // some bytes.
      int size = sinkBuffers.size();
      int sliceSize = size;
      var last = sinkBuffers.get(size - 1);
      if (last.hasRemaining() && (!finished || last.position() == 0)) {
        sliceSize--; // Do not submit.
      }
      var slice = sinkBuffers.subList(0, sliceSize);
      var snapshot =
          slice.stream().map(ByteBuffer::asReadOnlyBuffer).collect(Collectors.toUnmodifiableList());
      snapshot.forEach(ByteBuffer::flip); // Flip for downstream to read.
      slice.clear(); // Drop references.
      return snapshot;
    }
  }

  private final class SubscriptionImpl extends AbstractQueueSubscription<List<ByteBuffer>> {
    SubscriptionImpl() {
      super(downstream, executor);
    }

    @Override
    protected @Nullable List<ByteBuffer> poll() {
      List<ByteBuffer> next;
      while ((next = super.poll()) != null) {
        prefetcher.update(upstream);
        if (!next.isEmpty()) {
          return next;
        }
      }
      return null;
    }

    @Override
    protected void submit(List<ByteBuffer> item) {
      super.submit(item);
    }

    @Override
    protected void submitAndComplete(List<ByteBuffer> lastItem) {
      super.submitAndComplete(lastItem);
    }

    @Override
    protected void complete() {
      super.complete();
    }

    @Override
    protected void abort(boolean flowInterrupted) {
      super.abort(flowInterrupted);
      try (decoder) {
        upstream.cancel(flowInterrupted);
      }
    }
  }
}
