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

package com.github.mizosoft.methanol.internal.extensions;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.flow.Prefetcher;
import com.github.mizosoft.methanol.internal.flow.Upstream;
import java.io.IOException;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.spi.AbstractInterruptibleChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Subscription;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A subscriber that exposes the flow of bytes as a {@link ReadableByteChannel}. The channel is
 * {@link InterruptibleChannel} and either closing it asynchronously or interrupting the reader
 * thread causes blocks on {@code read()} to throw the appropriate exception and the upstream to be
 * cancelled. Any errors received from upstream are immediately thrown when reading if detected,
 * even if some bytes were available.
 */
public class ByteChannelSubscriber implements BodySubscriber<ReadableByteChannel> {

  // Constant communicating upstream completion (EOF) to read()
  private static final ByteBuffer TOMBSTONE = ByteBuffer.allocate(0);
  private static final List<ByteBuffer> TOMBSTONE_LIST = List.of(TOMBSTONE);

  private final Upstream upstream;
  private final Prefetcher prefetcher;
  private final BlockingQueue<List<ByteBuffer>> upstreamBuffers;
  private volatile @Nullable Throwable pendingError;

  /** Creates a new completed {@code ByteChannelSubscriber} instance. */
  public ByteChannelSubscriber() {
    upstream = new Upstream();
    prefetcher = new Prefetcher();
    upstreamBuffers = new ArrayBlockingQueue<>(FlowSupport.prefetch() + 1); // Consider TOMBSTONE_LIST
  }

  @Override
  public CompletionStage<ReadableByteChannel> getBody() {
    return CompletableFuture.completedFuture(new ChannelView());
  }

  @Override
  public void onSubscribe(Subscription subscription) {
    requireNonNull(subscription);
    if (upstream.setOrCancel(subscription)) {
      // non-atomic update is safe because window decrements are only possible
      // after successful takes/polls which can only happen after requests
      prefetcher.update(upstream);
    }
  }

  @Override
  public void onNext(List<ByteBuffer> item) {
    requireNonNull(item);
    // Should at least have space for `TOMBSTONE` after submitting item
    if (upstreamBuffers.remainingCapacity() > 1) {
      upstreamBuffers.offer(item);
    } else {
      // Upstream is trying to overflow us and somebody should know that
      upstream.cancel();
      signalCompletion(new IllegalStateException("missing back-pressure: queue is overflowed"));
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

  private void complete(@Nullable Throwable error) {
    upstream.clear();
    signalCompletion(error);
  }

  private void signalCompletion(@Nullable Throwable error) {
    if (error != null) {
      pendingError = error; // Must set error before signalling
      upstreamBuffers.clear();
    }
    // Offer TOMBSTONE so that any blocking thread knows it's the
    // EOF. Should accept the addition as no window more than
    // upstreamBuffers.capacity - 1 is accepted from upstream.
    try {
      upstreamBuffers.add(TOMBSTONE_LIST);
    } catch (IllegalStateException e) {
      throw new AssertionError("no space for TOMBSTONE_LIST", e);
    }
  }

  @SuppressWarnings("ReferenceEquality") // ByteBuffer sentinel values
  private final class ChannelView extends AbstractInterruptibleChannel
      implements ReadableByteChannel {

    private final List<ByteBuffer> cached;

    ChannelView() {
      cached = new ArrayList<>();
    }

    /**
     * Returns immediately either the currently available buffer with remaining bytes, TOMBSTONE if
     * completed or null if there are currently no buffers from upstream.
     */
    private @Nullable ByteBuffer pollNext() {
      ByteBuffer next;
      while ((next = nextAvailable()) == null) {
        List<ByteBuffer> buffers = upstreamBuffers.poll(); // Do not block
        if (buffers == null) {
          return null;
        }
        cached.addAll(buffers);
        prefetcher.update(upstream);
      }
      return next;
    }

    /**
     * Returns either the currently available buffer (blocking if necessary) TOMBSTONE if completed
     * or null if interrupted while blocking.
     */
    private @Nullable ByteBuffer takeNext() {
      ByteBuffer next;
      while ((next = nextAvailable()) == null) {
        try {
          List<ByteBuffer> buffers = upstreamBuffers.take();
          cached.addAll(buffers);
          prefetcher.update(upstream);
        } catch (InterruptedException e) {
          // We are interruptible so handle this gracefully.
          Thread.currentThread().interrupt(); // Assert interruption status
          return null; // readBytes() takes care of null returns
        }
      }
      return next;
    }

    /**
     * Returns the next buffer with available bytes in cached buffers, TOMBSTONE if complete or null
     * if ran out of cached buffers.
     */
    private @Nullable ByteBuffer nextAvailable() {
      while (cached.size() > 0) {
        ByteBuffer peek = cached.get(0);
        if (peek.hasRemaining() || peek == TOMBSTONE) {
          return peek;
        }
        cached.remove(0);
      }
      return null;
    }

    private void checkOpen() throws IOException {
      if (!isOpen()) {
        throw new ClosedChannelException();
      }
    }

    private void throwIfPending() throws IOException {
      Throwable error = pendingError;
      if (error != null) {
        throw new IOException("upstream error", error);
      }
    }

    private int readBytes(ByteBuffer dst) throws IOException {
      int read = 0;
      try {
        begin(); // Set blocker
        while (dst.hasRemaining()) {
          ByteBuffer next = read > 0 ? pollNext() : takeNext(); // Only block once
          throwIfPending(); // Might be an error signal
          if (next == TOMBSTONE) { // Normal completion
            if (read == 0) {
              // Only expose EOF if no bytes were copied
              read = -1;
            }
            break;
          } else if (next != null) {
            read += Utils.copyRemaining(next, dst);
          } else {
            // Either no buffers are available or interrupted if this is the
            // first read. Either ways end() takes care of the invariants
            // given whether any bytes were read or not.
            break;
          }
        }
      } finally {
        end(read > 0); // Unset blocker and check for close/interrupt status
      }
      return read;
    }

    @Override
    public synchronized int read(ByteBuffer dst) throws IOException {
      checkOpen();
      throwIfPending();
      return readBytes(dst);
    }

    @Override
    protected void implCloseChannel() {
      upstream.cancel();
      upstreamBuffers.clear();
      signalCompletion(null);
    }
  }
}
