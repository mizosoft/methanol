/*
 * Copyright (c) 2024 Moataz Abdelnasser
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
import com.github.mizosoft.methanol.internal.flow.AbstractQueueSubscription;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.google.errorprone.annotations.concurrent.GuardedBy;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A {@code BodyPublisher} that allows streaming the body's content through an {@code OutputStream}
 * or a {@code WritableByteChannel}. It is recommended to use {@link
 * MoreBodyPublishers#ofOutputStream} or {@link MoreBodyPublishers#ofWritableByteChannel} instead of
 * directly using this class.
 *
 * <p>After writing is finished, the publisher must be closed to complete the request (either by
 * calling {@link #close()} or closing the {@code OutputStream} or the {@code WritableByteChannel},
 * or using a try-with-resources construct). Additionally, {@link #closeExceptionally(Throwable)}
 * can be used to fail the request in case an error is encountered while writing.
 *
 * <p>Note that ({@link #contentLength()} always returns {@code -1}). If the content length is known
 * prior to writing, {@link BodyPublishers#fromPublisher(Publisher, long)} can be used to attach the
 * known length to this publisher.
 */
public final class WritableBodyPublisher implements BodyPublisher, Flushable, AutoCloseable {
  private static final int DEFAULT_BUFFER_SIZE = 8 * 1024; // 8Kb

  private static final ByteBuffer CLOSED_SENTINEL = ByteBuffer.allocate(0);

  /** A lock that serializes writing to {@code pipe}. */
  private final Lock writeLock = new ReentrantLock();

  private final ConcurrentLinkedQueue<ByteBuffer> pipe = new ConcurrentLinkedQueue<>();
  private final AtomicBoolean subscribed = new AtomicBoolean();
  private final int bufferSize;

  private final AtomicReference<State> state = new AtomicReference<>(Initial.INSTANCE);

  private interface State {}

  private enum Initial implements State {
    INSTANCE
  }

  private static final class Subscribed implements State {
    final SubscriptionImpl subscription;

    Subscribed(SubscriptionImpl subscription) {
      this.subscription = requireNonNull(subscription);
    }
  }

  private static final class Closed implements State {
    static final Closed NORMALLY = new Closed(null);

    final @Nullable Throwable exception;

    Closed(@Nullable Throwable exception) {
      this.exception = exception;
    }

    static Closed normally() {
      return NORMALLY;
    }

    static Closed exceptionally(Throwable exception) {
      return new Closed(requireNonNull(exception));
    }
  }

  @GuardedBy("writeLock")
  private @Nullable ByteBuffer sinkBuffer;

  private @MonotonicNonNull WritableByteChannel lazyChannel;
  private @MonotonicNonNull OutputStream lazyOutputStream;

  private volatile boolean submittedSentinel;

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
   * Unless already closed, causes the subscribed (or yet to subscribe) client to fail with the
   * given exception.
   */
  public void closeExceptionally(Throwable exception) {
    State prevState;
    while (true) {
      var currentState = state.get();
      if (currentState instanceof Closed) {
        FlowSupport.onDroppedException(exception);
        return;
      }

      prevState = currentState;

      // If subscribed, we can pass the exception to the subscription & skip storing it.
      if (currentState instanceof Subscribed
          && state.compareAndSet(currentState, Closed.normally())) {
        break;
      }

      // If not subscribed, we must store the exception so the future subscriber consumes it.
      if (currentState == Initial.INSTANCE
          && state.compareAndSet(currentState, Closed.exceptionally(exception))) {
        break;
      }
    }

    if (prevState instanceof Subscribed) {
      ((Subscribed) prevState).subscription.fireOrKeepAliveOnError(exception);
    }
  }

  /**
   * Unless already closed, causes the subscribed (or yet to subscribe) client to be completed after
   * the content written so far has been consumed.
   */
  @Override
  public void close() {
    State prevState;
    while (true) {
      var currentState = state.get();
      if (currentState instanceof Closed) {
        return;
      }

      prevState = currentState;
      if (state.compareAndSet(currentState, Closed.normally())) {
        break;
      }
    }

    submitSentinel();
    if (prevState instanceof Subscribed) {
      ((Subscribed) prevState).subscription.fireOrKeepAlive();
    }
  }

  /**
   * Returns {@code true} if this publisher is closed by either {@link #close} or {@link
   * #closeExceptionally}.
   */
  public boolean isClosed() {
    return state.get() instanceof Closed;
  }

  /**
   * Makes any buffered content available for consumption by the downstream.
   *
   * @throws IllegalStateException if closed
   */
  @Override
  public void flush() {
    requireState(!isClosed(), "closed");
    fireOrKeepAliveOnNextIf(flushBuffer());
  }

  @Override
  public long contentLength() {
    return -1;
  }

  @Override
  public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
    if (subscribed.compareAndSet(false, true)) {
      var subscription = new SubscriptionImpl(subscriber);
      if (state.compareAndSet(Initial.INSTANCE, new Subscribed(subscription))) {
        subscription.fireOrKeepAlive();
      } else {
        var currentState = state.get();
        if (currentState instanceof Closed) {
          var exception = ((Closed) currentState).exception;
          if (exception != null) {
            subscription.fireOrKeepAliveOnError(exception);
          } else {
            // As close() submits the sentinel value AFTER setting the state, we must double-check
            // the sentinel has been submitted before starting the subscription. Otherwise, the
            // subscription might miss the sentinel submitted by close().
            submitSentinel();
            subscription.fireOrKeepAlive();
          }
        }
      }
    } else {
      FlowSupport.rejectMulticast(subscriber);
    }
  }

  @SuppressWarnings("NullAway")
  private void fireOrKeepAliveOnNextIf(boolean condition) {
    State currentState;
    if (condition && (currentState = state.get()) instanceof Subscribed) {
      ((Subscribed) currentState).subscription.fireOrKeepAliveOnNext();
    }
  }

  private boolean flushBuffer() {
    writeLock.lock();
    try {
      return unguardedFlushBuffer();
    } finally {
      writeLock.unlock();
    }
  }

  @GuardedBy("writeLock")
  private boolean unguardedFlushBuffer() {
    var buffer = sinkBuffer;
    if (buffer != null && buffer.position() > 0) {
      sinkBuffer = null;
      pipe.offer(buffer.flip().asReadOnlyBuffer());
      return true;
    }
    return false;
  }

  private void submitSentinel() {
    if (!submittedSentinel) {
      writeLock.lock();
      try {
        if (!submittedSentinel) {
          submittedSentinel = true;
          flushBuffer();
          pipe.offer(CLOSED_SENTINEL);
        }
      } finally {
        writeLock.unlock();
      }
    }
  }

  /** Returns a new {@code WritableBodyPublisher}. */
  public static WritableBodyPublisher create() {
    return new WritableBodyPublisher(DEFAULT_BUFFER_SIZE);
  }

  /** Returns a new {@code WritableBodyPublisher} with the given buffer size. */
  public static WritableBodyPublisher create(int bufferSize) {
    return new WritableBodyPublisher(bufferSize);
  }

  private final class SinkChannel implements WritableByteChannel {
    SinkChannel() {}

    @Override
    public int write(ByteBuffer src) throws ClosedChannelException {
      requireNonNull(src);
      if (isClosed()) {
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

        if (isClosed()) { // Check if asynchronously closed.
          sinkBuffer = null;
          if (written <= 0) { // Only report if no bytes were written.
            throw new AsynchronousCloseException();
          }
          return written;
        } else {
          sinkBuffer = buffer;
        }
      } finally {
        writeLock.unlock();
      }

      fireOrKeepAliveOnNextIf(signalsAvailable);
      return written;
    }

    @Override
    public boolean isOpen() {
      return !isClosed();
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
        // Throw a more appropriate exception for an OutputStream.
        throw new IOException("closed", e);
      }
    }

    @Override
    public void close() throws IOException {
      out.close();
    }
  }

  private final class SubscriptionImpl extends AbstractQueueSubscription<ByteBuffer> {
    SubscriptionImpl(Subscriber<? super ByteBuffer> downstream) {
      super(downstream, FlowSupport.SYNC_EXECUTOR, pipe, CLOSED_SENTINEL);
    }

    @Override
    protected void abort(boolean flowInterrupted) {
      pipe.clear();

      // Make sure state also reflects unexpected cancellations. This also allows a possibly active
      // writer to detect asynchronous closure.
      if (flowInterrupted) {
        while (true) {
          var currentState = state.get();
          if (!(currentState instanceof Subscribed)
              || state.compareAndSet(currentState, Closed.normally())) {
            return;
          }
        }
      }
    }
  }
}
