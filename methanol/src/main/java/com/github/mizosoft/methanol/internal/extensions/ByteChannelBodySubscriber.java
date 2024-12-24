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

package com.github.mizosoft.methanol.internal.extensions;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.flow.Prefetcher;
import com.github.mizosoft.methanol.internal.flow.Upstream;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.spi.AbstractInterruptibleChannel;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A subscriber that exposes the flow of bytes as a {@link ReadableByteChannel}. The channel is
 * {@link InterruptibleChannel} and either closing it asynchronously or interrupting the reader
 * thread causes blocks on {@code read()} to throw the appropriate exception and the upstream to be
 * cancelled. Any errors received from upstream are immediately thrown when reading if detected,
 * even if some bytes were available.
 */
public final class ByteChannelBodySubscriber implements BodySubscriber<ReadableByteChannel> {
  private static final ByteBuffer TOMBSTONE = ByteBuffer.allocate(0);
  private static final List<ByteBuffer> TOMBSTONE_LIST = List.of(TOMBSTONE);

  private static final VarHandle PENDING_EXCEPTION;

  static {
    try {
      PENDING_EXCEPTION =
          MethodHandles.lookup().findVarHandle(Channel.class, "pendingException", Throwable.class);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final Upstream upstream = new Upstream();
  private final Prefetcher prefetcher = new Prefetcher();
  private final Channel channel = new Channel(FlowSupport.prefetch());

  public ByteChannelBodySubscriber() {}

  @Override
  public CompletionStage<ReadableByteChannel> getBody() {
    return CompletableFuture.completedFuture(channel);
  }

  @Override
  public void onSubscribe(Subscription subscription) {
    requireNonNull(subscription);
    if (upstream.setOrCancel(subscription)) {
      // Prefetcher::initialize is synchronized with Prefetcher::update by the fact that onSubscribe
      // happens-before channel::receive, and channel::receive happens-before prefetcher::update.
      prefetcher.initialize(upstream);
    }
  }

  @Override
  public void onNext(List<ByteBuffer> item) {
    requireNonNull(item);
    if (upstream.isCancelled()) {
      return;
    }

    if (channel.receive(item) == ReceiveResult.OVERFLOWED) {
      upstream.cancel();
      channel.receiveCompletion(new IllegalStateException("Getting more items than requested"));
    }
  }

  @Override
  public void onError(Throwable throwable) {
    requireNonNull(throwable);
    upstream.clear();
    channel.receiveCompletion(throwable);
  }

  @Override
  public void onComplete() {
    upstream.clear();
    channel.receiveCompletion(null);
  }

  enum ReceiveResult {
    CLOSED,
    OVERFLOWED,
    RECEIVED
  }

  private static final class ConsumedPendingException extends Exception {
    @SuppressWarnings("StaticAssignmentOfThrowable") // Not thrown, just used for CAS control.
    static final ConsumedPendingException INSTANCE = new ConsumedPendingException();

    private ConsumedPendingException() {
      super("", null, false, false);
    }
  }

  @SuppressWarnings("ReferenceEquality") // ByteBuffer sentinel values.
  private final class Channel extends AbstractInterruptibleChannel implements ReadableByteChannel {
    private final Lock readLock = new ReentrantLock();
    private final Deque<ByteBuffer> polledBuffers = new ArrayDeque<>();
    private final BlockingQueue<List<ByteBuffer>> readQueue;

    @SuppressWarnings("unused") // VarHandle indirection.
    private volatile @MonotonicNonNull Throwable pendingException;

    Channel(int capacity) {
      readQueue = new ArrayBlockingQueue<>(capacity + 1); // Make room for TOMBSTONE_LIST.
    }

    ReceiveResult receive(List<ByteBuffer> buffers) {
      if (!isOpen()) {
        return ReceiveResult.CLOSED;
      }
      if (readQueue.remainingCapacity() <= 1) { // Must have room for TOMBSTONE_LIST.
        return ReceiveResult.OVERFLOWED;
      }
      readQueue.add(buffers);
      return ReceiveResult.RECEIVED;
    }

    void receiveCompletion(@Nullable Throwable exception) {
      if (exception != null) {
        if (PENDING_EXCEPTION.compareAndSet(this, null, exception)) {
          readQueue.clear();
        } else {
          FlowSupport.onDroppedException(exception);
        }
      }
      readQueue.add(TOMBSTONE_LIST);
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
      requireOpen();
      throwIfPending();
      readLock.lock();
      try {
        return readBytes(dst);
      } finally {
        readLock.unlock();
      }
    }

    private void requireOpen() throws IOException {
      if (!isOpen()) {
        throw new ClosedChannelException();
      }
    }

    private void throwIfPending() throws IOException {
      var exception = pendingException;
      if (exception != null && exception != ConsumedPendingException.INSTANCE) {
        throw new IOException("Upstream error", exception);
      }
    }

    @GuardedBy("readLock")
    private int readBytes(ByteBuffer dst) throws IOException {
      int read = 0;
      try {
        begin();
        while (dst.hasRemaining() && isOpen()) {
          ByteBuffer next;
          if (read <= 0) {
            next = takeNext();
          } else if ((next = pollNext()) == null) {
            break;
          }
          throwIfPending();
          if (next == TOMBSTONE) {
            // Only expose EOF if no bytes were read.
            if (read == 0) {
              read = -1;
            }
            break;
          } else {
            read += Utils.copyRemaining(next, dst);
          }
        }
      } finally {
        end(read > 0);
      }
      return read;
    }

    @GuardedBy("readLock")
    private @Nullable ByteBuffer pollNext() {
      ByteBuffer next;
      while ((next = nextPolled()) == null) {
        var buffers = readQueue.poll();
        if (buffers == null) {
          return null;
        }
        polledBuffers.addAll(buffers);
        updatePrefetcher();
      }
      return next;
    }

    @GuardedBy("readLock")
    private ByteBuffer takeNext() throws ClosedByInterruptException {
      ByteBuffer next;
      while ((next = nextPolled()) == null) {
        try {
          var buffers = readQueue.take();
          polledBuffers.addAll(buffers);
          updatePrefetcher();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt(); // Assert interruption status.
          throw new ClosedByInterruptException();
        }
      }
      return next;
    }

    @GuardedBy("readLock")
    private @Nullable ByteBuffer nextPolled() {
      ByteBuffer next;
      while ((next = polledBuffers.peek()) != null) {
        if (next.hasRemaining() || next == TOMBSTONE) {
          return next;
        }
        polledBuffers.poll(); // Consume.
      }
      return null;
    }

    @GuardedBy("readLock")
    void updatePrefetcher() {
      prefetcher.update(upstream);
    }

    @Override
    protected void implCloseChannel() {
      upstream.cancel();
      readQueue.clear();
      var exception =
          (Throwable) PENDING_EXCEPTION.getAndSet(this, ConsumedPendingException.INSTANCE);
      if (exception != null && exception != ConsumedPendingException.INSTANCE) {
        FlowSupport.onDroppedException(exception);
      }
      readQueue.add(TOMBSTONE_LIST);
    }
  }
}
