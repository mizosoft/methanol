/*
 * Copyright (c) 2025 Moataz Hussein
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
import com.github.mizosoft.methanol.internal.flow.AbstractPollableSubscription;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.io.Flushable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
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
  private static final VarHandle STATE;

  static {
    try {
      STATE =
          MethodHandles.lookup().findVarHandle(WritableBodyPublisher.class, "state", State.class);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static final int SIGNALS_AVAILABLE_BIT = 1 << 31;

  private final AtomicBoolean subscribed = new AtomicBoolean();
  private final Stream stream;

  @SuppressWarnings("FieldMayBeFinal") // VarHandle indirection.
  private State state = Initial.INSTANCE;

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

  private @MonotonicNonNull WritableByteChannel lazyChannel;
  private @MonotonicNonNull OutputStream lazyOutputStream;

  private WritableBodyPublisher(int bufferSize, int memoryQuota, QueuedMemoryTracker tracker) {
    requireArgument(bufferSize > 0, "Non-positive bufferSize");
    requireArgument(memoryQuota > 0, "Non-positive maxBufferedBytes");
    requireArgument(memoryQuota >= bufferSize, "memoryQuota < bufferSize");
    this.stream = new Stream(bufferSize, memoryQuota, this::isClosed, requireNonNull(tracker));
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
      var currentState = state;
      if (currentState instanceof Closed) {
        FlowSupport.onDroppedException(exception);
        return;
      }

      prevState = currentState;

      // If subscribed, we can pass the exception to the subscription & skip storing it.
      if (currentState instanceof Subscribed
          && STATE.compareAndSet(this, currentState, Closed.normally())) {
        break;
      }

      // If not subscribed, we must store the exception so the future subscriber consumes it.
      if (currentState == Initial.INSTANCE
          && STATE.compareAndSet(this, currentState, Closed.exceptionally(exception))) {
        break;
      }
    }

    stream.close(true);
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
      var currentState = state;
      if (currentState instanceof Closed) {
        return;
      }

      prevState = currentState;
      if (STATE.compareAndSet(this, currentState, Closed.normally())) {
        break;
      }
    }

    stream.close(false);
    if (prevState instanceof Subscribed) {
      ((Subscribed) prevState).subscription.fireOrKeepAlive();
    }
  }

  /**
   * Returns {@code true} if this publisher is closed by either {@link #close} or {@link
   * #closeExceptionally}.
   */
  public boolean isClosed() {
    return state instanceof Closed;
  }

  /**
   * Makes any buffered content available for consumption by downstream.
   *
   * @throws IllegalStateException if closed
   * @throws UncheckedIOException if an interruption occurs while waiting
   */
  @Override
  public void flush() {
    requireState(!isClosed(), "Closed");
    try {
      fireOrKeepAliveOnNextIf(stream.flush());
    } catch (InterruptedIOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public long contentLength() {
    return -1;
  }

  @Override
  public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
    if (subscribed.compareAndSet(false, true)) {
      var subscription = new SubscriptionImpl(subscriber);
      if (STATE.compareAndSet(this, Initial.INSTANCE, new Subscribed(subscription))) {
        subscription.fireOrKeepAlive();
      } else {
        var currentState = state;
        if (currentState instanceof Closed) {
          var exception = ((Closed) currentState).exception;
          if (exception != null) {
            subscription.fireOrKeepAliveOnError(exception);
          } else {
            subscription.fireOrKeepAlive();
          }
        }
      }
    } else {
      FlowSupport.rejectMulticast(subscriber);
    }
  }

  private void fireOrKeepAliveOnNextIf(boolean condition) {
    State currentState;
    if (condition && (currentState = state) instanceof Subscribed) {
      ((Subscribed) currentState).subscription.fireOrKeepAliveOnNext();
    }
  }

  /** Returns a new {@code WritableBodyPublisher}. */
  public static WritableBodyPublisher create() {
    return create(Utils.BUFFER_SIZE, NoopQueuedMemoryTracker.INSTANCE);
  }

  /**
   * Returns a new {@code WritableBodyPublisher} with the given buffer size (number of bytes to
   * buffer before making them available to the HTTP client).
   */
  public static WritableBodyPublisher create(int bufferSize) {
    return create(bufferSize, NoopQueuedMemoryTracker.INSTANCE);
  }

  // For testing.
  interface QueuedMemoryTracker {
    void queued(int size);

    void dequeued(int size);
  }

  private enum NoopQueuedMemoryTracker implements QueuedMemoryTracker {
    INSTANCE;

    @Override
    public void queued(int size) {}

    @Override
    public void dequeued(int size) {}
  }

  static WritableBodyPublisher create(int bufferSize, QueuedMemoryTracker queuedMemoryTracker) {
    return new WritableBodyPublisher(
        bufferSize, queuedMemoryQuotaFor(bufferSize), queuedMemoryTracker);
  }

  static int queuedMemoryQuotaFor(int bufferSize) {
    return Math.multiplyExact(FlowSupport.prefetch(), bufferSize);
  }

  private final class SinkChannel implements WritableByteChannel {
    SinkChannel() {}

    @Override
    public int write(ByteBuffer src) throws IOException {
      int written = stream.write(src);
      fireOrKeepAliveOnNextIf((written & SIGNALS_AVAILABLE_BIT) != 0);
      return written & ~SIGNALS_AVAILABLE_BIT;
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
        throw new IOException("Closed", e);
      }
    }

    @Override
    public void close() {
      WritableBodyPublisher.this.close();
    }
  }

  private final class SubscriptionImpl extends AbstractPollableSubscription<ByteBuffer> {
    SubscriptionImpl(Subscriber<? super ByteBuffer> downstream) {
      super(downstream, FlowSupport.SYNC_EXECUTOR);
    }

    @Override
    protected @Nullable ByteBuffer poll() {
      return stream.poll();
    }

    @Override
    protected boolean isComplete() {
      return stream.isComplete();
    }

    @Override
    protected void abort(boolean flowInterrupted) {
      // Make sure we reflect unexpected cancellations (e.g. subscriber failures) on the stream.
      while (true) {
        var currentState = state;
        if (currentState instanceof Closed) {
          return;
        }

        if (STATE.compareAndSet(WritableBodyPublisher.this, currentState, Closed.normally())) {
          stream.close(flowInterrupted);
          return;
        }
      }
    }
  }

  private static final class Stream {
    private final Lock lock = new ReentrantLock();
    private final Condition hasQueuedMemoryQuota = lock.newCondition();

    /** A lock that serializes writing to {@code pipe}. */
    @GuardedBy("lock")
    private final Queue<ByteBuffer> queue = new ArrayDeque<>();

    private final QueuedMemoryTracker queuedMemoryTracker;

    /** Checks whether the publisher is closed. Used for checking asynchronous closure. */
    private final BooleanSupplier isClosed;

    private final int queuedMemoryQuota;
    private final int bufferSize;

    @GuardedBy("lock")
    private int queuedMemory;

    @GuardedBy("lock")
    private @Nullable ByteBuffer sinkBuffer;

    Stream(
        int bufferSize,
        int queuedMemoryQuota,
        BooleanSupplier isClosed,
        QueuedMemoryTracker queuedMemoryTracker) {
      this.bufferSize = bufferSize;
      this.queuedMemoryQuota = queuedMemoryQuota;
      this.isClosed = isClosed;
      this.queuedMemoryTracker = queuedMemoryTracker;
    }

    int write(ByteBuffer src) throws IOException {
      if (isClosed.getAsBoolean()) {
        throw new ClosedChannelException();
      }
      if (!src.hasRemaining()) {
        return 0;
      }

      int written = 0;
      boolean signalsAvailable = false;
      lock.lock();
      try {
        do {
          if (sinkBuffer == null) {
            sinkBuffer = ByteBuffer.allocate(bufferSize);
          }

          written += Utils.copyRemaining(src, sinkBuffer);
          if (!sinkBuffer.hasRemaining()) {
            // Block for queue space.
            var readableBuffer = sinkBuffer.flip().asReadOnlyBuffer();
            try {
              while (queuedMemory > queuedMemoryQuota - readableBuffer.remaining()) {
                hasQueuedMemoryQuota.await();
              }
            } catch (InterruptedException e) {
              throw Utils.toInterruptedIOException(e);
            }

            // We might've been closed while waiting for space.
            if (isClosed.getAsBoolean()) {
              signalsAvailable = false;
              break;
            }

            sinkBuffer = null;
            queue.add(readableBuffer);
            queuedMemory += readableBuffer.remaining();
            queuedMemoryTracker.queued(readableBuffer.remaining());
            signalsAvailable = true;
          }
        } while (src.hasRemaining() && !isClosed.getAsBoolean());

        if (isClosed.getAsBoolean()) { // Check if asynchronously closed.
          signalsAvailable = false;
          if (written <= 0) { // Only report if no bytes were written.
            throw new AsynchronousCloseException();
          }
        }
        return written | (signalsAvailable ? SIGNALS_AVAILABLE_BIT : 0);
      } finally {
        lock.unlock();
      }
    }

    boolean flush() throws InterruptedIOException {
      lock.lock();
      try {
        return flush(false);
      } finally {
        lock.unlock();
      }
    }

    @GuardedBy("lock")
    private boolean flush(boolean closing) throws InterruptedIOException {
      var buffer = sinkBuffer;
      if (buffer == null || buffer.position() == 0) {
        return false;
      }

      // Only wait for space if we're not closing. Otherwise, this will be the last buffer to push
      // so there is no need to thwart.
      var remainingSlice = buffer.slice();
      var readableBuffer = buffer.flip().asReadOnlyBuffer();
      if (!closing) {
        try {
          while (queuedMemory > queuedMemoryQuota - readableBuffer.remaining()) {
            hasQueuedMemoryQuota.await();
          }
        } catch (InterruptedException e) {
          throw Utils.toInterruptedIOException(e);
        }
      }

      if (closing) {
        sinkBuffer = null;
      } else if (buffer.position() < buffer.limit()) {
        sinkBuffer = remainingSlice; // Keep the remaining free space.
      }

      queue.add(readableBuffer);
      queuedMemory += readableBuffer.remaining();
      queuedMemoryTracker.queued(readableBuffer.remaining());
      return true;
    }

    void close(boolean flowInterrupted) {
      lock.lock();
      try {
        if (flowInterrupted) {
          sinkBuffer = null;
          queue.clear();
        } else {
          try {
            flush(true);
          } catch (InterruptedIOException e) {
            throw new AssertionError(e); // Can't happen since we'll not be waiting.
          }
        }

        // Wake up waiters if any.
        queuedMemory = 0;
        hasQueuedMemoryQuota.signalAll();
      } finally {
        lock.unlock();
      }
    }

    @Nullable ByteBuffer poll() {
      lock.lock();
      try {
        var buffer = queue.poll();
        if (buffer != null) {
          queuedMemory -= buffer.remaining();
          queuedMemoryTracker.dequeued(buffer.remaining());
          hasQueuedMemoryQuota.signalAll();
        }
        return buffer;
      } finally {
        lock.unlock();
      }
    }

    boolean isComplete() {
      // Here we utilize the fact that once the stream is closed (through the publisher), no more
      // writes are expected. So we're complete if the queue is empty after detecting closure.
      if (!isClosed.getAsBoolean()) {
        return false;
      }

      lock.lock();
      try {
        return queue.isEmpty();
      } finally {
        lock.unlock();
      }
    }
  }
}
