/*
 * Copyright (c) 2022 Moataz Abdelnasser
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

package com.github.mizosoft.methanol;

import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.flow.AbstractSubscription;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.checkerframework.checker.lock.qual.GuardedBy;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A {@code BodyPublisher} that exposes a sink for writing the body's content. It is recommended to
 * use {@link MoreBodyPublishers#ofOutputStream} or {@link MoreBodyPublishers#ofWritableByteChannel}
 * instead of directly using on this class.
 *
 * <p>The sink can be acquired either as a {@link WritableByteChannel} or an {@link OutputStream}.
 * After writing is finished, the publisher must be closed to complete the request (either by
 * calling {@link #close()} or closing one of the returned sinks, or using a try-with-resources
 * construct). Additionally, {@link #closeExceptionally(Throwable)} can be used to fail the request
 * in case an error is encountered while writing.
 *
 * <p>Note that ({@link #contentLength()} always returns {@code -1}). If the content length is known
 * prior to writing, {@link BodyPublishers#fromPublisher(Publisher, long)} can be used to attach the
 * known length to this publisher.
 */
public final class WritableBodyPublisher implements BodyPublisher, Flushable, AutoCloseable {
  private static final int DEFAULT_BUFFER_SIZE = 8 * 1024; // 8Kb

  private static final ByteBuffer CLOSED_SENTINEL = ByteBuffer.allocate(0);

  private final Lock closeLock = new ReentrantLock();
  private final Lock writeLock = new ReentrantLock();
  private final ConcurrentLinkedQueue<ByteBuffer> pipe = new ConcurrentLinkedQueue<>();
  private final AtomicBoolean subscribed = new AtomicBoolean();
  private final int bufferSize;

  private volatile @Nullable SubscriptionImpl downstreamSubscription;

  /**
   * The error received in {@link #closeExceptionally} if a subscriber is yet to arrived while
   * calling that method. When a subscriber does arrive, it reads this field and completes the
   * subscriber exceptionally right away.
   */
  @GuardedBy("lock")
  private @MonotonicNonNull Throwable pendingCloseError;

  private volatile boolean closed;

  @GuardedBy("writeLock")
  private @Nullable ByteBuffer sinkBuffer;

  private @MonotonicNonNull WritableByteChannel lazyChannel;
  private @MonotonicNonNull OutputStream lazyOutputStream;

  private WritableBodyPublisher(int bufferSize) {
    requireArgument(bufferSize > 0, "non-positive buffer size");
    this.bufferSize = bufferSize;
  }

  /** Returns a {@code WritableByteChannel} for writing this body's content. */
  public WritableByteChannel byteChannel() {
    var channel = lazyChannel;
    if (channel == null) {
      channel = new SinkChannel();
      lazyChannel = channel;
    }
    return channel;
  }

  /** Returns a {@code OutputStream} for writing this body's content. */
  public OutputStream outputStream() {
    var outputStream = lazyOutputStream;
    if (outputStream == null) {
      outputStream = new SinkOutputStream(byteChannel());
      lazyOutputStream = outputStream;
    }
    return outputStream;
  }

  /**
   * Unless already closed, causes any subscribed (or yet to subscribe) client to fail with the
   * given error.
   */
  public void closeExceptionally(Throwable error) {
    requireNonNull(error);
    SubscriptionImpl subscription;
    closeLock.lock();
    try {
      if (closed) {
        FlowSupport.onDroppedError(error);
        return;
      }
      closed = true;
      subscription = downstreamSubscription;
      if (subscription == null) {
        // Record the error to consume it when a subscriber arrives
        pendingCloseError = error;
        return;
      }
    } finally {
      closeLock.unlock();
    }

    subscription.signalError(error);
  }

  /**
   * Unless already closed, causes any subscribed (or yet to subscribe) client to be completed after
   * the written content has been consumed.
   */
  @Override
  public void close() {
    closeLock.lock();
    try {
      if (closed) {
        return;
      }
      closed = true;
    } finally {
      closeLock.unlock();
    }

    flushInternal();
    pipe.offer(CLOSED_SENTINEL);
    signalDownstream(true);
  }

  /**
   * Returns {@code true} if this publisher is closed by either {@link #close} or {@link
   * #closeExceptionally}.
   */
  public boolean isClosed() {
    return closed;
  }

  /**
   * Makes any buffered content available for consumption by the downstream.
   *
   * @throws IllegalStateException if closed
   */
  @Override
  public void flush() {
    requireState(!closed, "closed");
    if (flushInternal()) { // Notify downstream if flushing produced any signals
      signalDownstream(false);
    }
  }

  @Override
  public long contentLength() {
    return -1;
  }

  @Override
  public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
    requireNonNull(subscriber);
    var subscription = new SubscriptionImpl(subscriber);
    if (subscribed.compareAndSet(false, true)) {
      Throwable error;
      closeLock.lock();
      try {
        downstreamSubscription = subscription;
        error = pendingCloseError;
      } finally {
        closeLock.unlock();
      }

      if (error != null) {
        subscription.signalError(error);
      } else {
        subscription.signal(true);
      }
    } else {
      FlowSupport.refuse(
          subscriber,
          new IllegalStateException("already subscribed (multiple subscribers not supported)"));
    }
  }

  private void signalDownstream(boolean force) {
    var subscription = downstreamSubscription;
    if (subscription != null) {
      subscription.signal(force);
    }
  }

  private boolean flushInternal() {
    boolean signalsAvailable = false;
    writeLock.lock();
    try {
      var buffer = sinkBuffer;
      if (buffer != null && buffer.position() > 0) {
        sinkBuffer = null;
        pipe.offer(buffer.flip().asReadOnlyBuffer());
        signalsAvailable = true;
      }
    } finally {
      writeLock.unlock();
    }
    return signalsAvailable;
  }

  /** Returns a new {@code WritableBodyPublisher}. */
  public static WritableBodyPublisher create() {
    return new WritableBodyPublisher(DEFAULT_BUFFER_SIZE);
  }

  /* Returns a new {@code WritableBodyPublisher} with the given buffer size. */
  public static WritableBodyPublisher create(int bufferSize) {
    return new WritableBodyPublisher(bufferSize);
  }

  private final class SinkChannel implements WritableByteChannel {
    SinkChannel() {}

    @Override
    public int write(ByteBuffer src) throws ClosedChannelException {
      requireNonNull(src);
      if (closed) {
        throw new ClosedChannelException();
      }
      if (!src.hasRemaining()) {
        return 0;
      }

      int written = 0;
      boolean signalsAvailable = false;
      writeLock.lock();
      try {
        var buffer = sinkBuffer;
        do {
          if (buffer == null) {
            buffer = ByteBuffer.allocate(bufferSize);
          }
          written += Utils.copyRemaining(src, buffer);
          if (!buffer.hasRemaining()) {
            pipe.offer(buffer.flip().asReadOnlyBuffer());
            signalsAvailable = true;
            buffer = null;
          }
        } while (src.hasRemaining() && isOpen());

        if (closed) { // Check if asynchronously closed
          sinkBuffer = null;
          if (written <= 0) { // Only report if no bytes were written
            throw new AsynchronousCloseException();
          }
        } else {
          sinkBuffer = buffer;
        }
      } finally {
        writeLock.unlock();
      }

      if (signalsAvailable) {
        signalDownstream(false);
      }
      return written;
    }

    @Override
    public boolean isOpen() {
      return !closed;
    }

    @Override
    public void close() {
      WritableBodyPublisher.this.close();
    }
  }

  /**
   * WritableByteChannel adapter for an OutputStream. Most work is done by Channels::newOutputStream
   * but overridden to forward flush() and close() to this publisher.
   */
  private final class SinkOutputStream extends OutputStream {
    private final OutputStream out;

    SinkOutputStream(WritableByteChannel channel) {
      this.out = Channels.newOutputStream(channel);
    }

    @Override
    public void write(int b) throws IOException {
      out.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      out.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
      try {
        WritableBodyPublisher.this.flush();
      } catch (IllegalStateException e) {
        // Throw a more appropriate exception for an OutputStream
        throw new IOException("closed", e);
      }
    }

    @Override
    public void close() throws IOException {
      out.close();
    }
  }

  private final class SubscriptionImpl extends AbstractSubscription<ByteBuffer> {
    private @Nullable ByteBuffer latestBuffer;

    SubscriptionImpl(Subscriber<? super ByteBuffer> downstream) {
      super(downstream, FlowSupport.SYNC_EXECUTOR);
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // ByteBuffer sentinel
    protected long emit(Subscriber<? super ByteBuffer> downstream, long emit) {
      // The buffer is polled prematurely to detect completion regardless of demand
      ByteBuffer buffer = latestBuffer;
      latestBuffer = null;
      if (buffer == null) {
        buffer = pipe.poll();
      }
      long submitted = 0L;
      while (true) {
        if (buffer == CLOSED_SENTINEL) {
          cancelOnComplete(downstream);
          return 0;
        } else if (submitted >= emit || buffer == null) { // Exhausted either demand or buffers
          latestBuffer = buffer; // Save the last polled buffer for later rounds
          return submitted;
        } else if (submitOnNext(downstream, buffer)) {
          submitted++;
          buffer = pipe.poll();
        } else {
          return 0;
        }
      }
    }

    @Override
    protected void abort(boolean flowInterrupted) {
      pipe.clear();
      if (flowInterrupted) {
        // If this publisher is cancelled "abnormally" (amid writing), possibly ongoing writers
        // should know (by setting closed to true) so they can abort writing by throwing an
        // exception.
        closed = true;
      }
    }
  }
}
