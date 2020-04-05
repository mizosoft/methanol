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

package com.github.mizosoft.methanol;

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
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.atomic.AtomicBoolean;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A {@code BodyPublisher} that exposes a sink for writing the body's content. Sink can be acquired
 * either as a {@link WritableByteChannel} or an {@link OutputStream}. After writing is finished,
 * the publisher must be closed to complete the request (either by calling {@link #close()} or
 * closing one of the returned sinks, or using a try-with-resources construct). Additionally, {@link
 * #closeExceptionally(Throwable)} can be used to fail the request in case an error is encountered
 * while writing.
 *
 * <p>Only the chunked {@code Transfer-Encoding} is supported ({@link #contentLength()} always
 * returns {@code -1}). If the content length is known prior to writing, {@link
 * BodyPublishers#fromPublisher(Publisher, long)} can be used to wrap this publisher.
 */
public final class WritableBodyPublisher implements BodyPublisher, Flushable, AutoCloseable {

  private static final int DEFAULT_SINK_BUFFER_SIZE = 8 * 1024; // 8Kb
  private static final String SINK_BUFFER_SIZE_PROP =
      "com.github.mizosoft.methanol.WritableBodyPublisher.sinkBufferSize";
  private static final int SINK_BUFFER_SIZE = getSinkBufferSize();

  private static final ByteBuffer CLOSED = ByteBuffer.allocate(0);

  private final AtomicBoolean subscribed;
  private final ConcurrentLinkedQueue<ByteBuffer> pipe;
  private volatile @Nullable SubscriptionImpl downstreamSubscription;
  private volatile @MonotonicNonNull Throwable closeError; // cache error in case not yet subscribed
  private volatile boolean closed;

  private final Object writeLock;
  private @MonotonicNonNull WritableByteChannel sinkChannel;
  private @MonotonicNonNull OutputStream sinkOutputStream;
  private @Nullable ByteBuffer sinkBuffer;

  private WritableBodyPublisher() {
    subscribed = new AtomicBoolean();
    pipe = new ConcurrentLinkedQueue<>();
    writeLock = new Object();
  }

  /** Returns a {@code WritableByteChannel} for writing this body's content. */
  public WritableByteChannel byteChannel() {
    WritableByteChannel channel = sinkChannel;
    if (channel == null) {
      channel = new SinkChannel();
      sinkChannel = channel;
    }
    return channel;
  }

  /** Returns a {@code OutputStream} for writing this body's content. */
  public OutputStream outputStream() {
    OutputStream out = sinkOutputStream;
    if (out == null) {
      out = new SinkChannelAdapter(byteChannel());
      sinkOutputStream = out;
    }
    return out;
  }

  /**
   * Unless already closed, causes any subscribed (or yet to subscribe) client to fail with the
   * given error.
   */
  public void closeExceptionally(Throwable error) {
    requireNonNull(error);
    if (!closed) {
      closed = true;
      closeError = error;
      SubscriptionImpl subscription = downstreamSubscription;
      if (subscription != null) {
        subscription.signalError(error);
      }
    }
  }

  /**
   * Unless already closed, causes any subscribed (or yet to subscribe) client to be completed after
   * the written content has been consumed.
   */
  @Override
  public void close() {
    if (!closed) {
      closed = true;
      flushInternal();
      pipe.offer(CLOSED);
      signalDownstream(true);
    }
  }

  /**
   * Makes any buffered content available for consumption by the downstream.
   *
   * @throws IllegalStateException if closed
   */
  @Override
  public void flush() {
    if (closed) {
      throw new IllegalStateException("closed");
    }
    if (flushInternal()) {
      signalDownstream(false); // notify downstream if flushing produced any signals
    }
  }

  @Override
  public long contentLength() {
    return -1;
  }

  @Override
  public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
    requireNonNull(subscriber);
    SubscriptionImpl subscription = new SubscriptionImpl(subscriber);
    if (subscribed.compareAndSet(false, true)) {
      downstreamSubscription = subscription;
      // check if was closed due to error before subscribing
      Throwable e = closeError;
      if (e != null) {
        subscription.signalError(e);
      } else {
        subscription.signal(true); // apply onSubscribe
      }
    } else {
      Throwable error =
          new IllegalStateException("already subscribed, multiple subscribers not supported");
      try {
        subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
      } catch (Throwable t) {
        error.addSuppressed(t);
      } finally {
        subscriber.onError(error);
      }
    }
  }

  private void signalDownstream(boolean force) {
    SubscriptionImpl subscription = downstreamSubscription;
    if (subscription != null) {
      subscription.signal(force);
    }
  }

  private boolean flushInternal() {
    boolean signalsAvailable = false;
    synchronized (writeLock) {
      ByteBuffer sink = sinkBuffer;
      if (sink != null && sink.position() > 0) {
        sinkBuffer =
            sink.hasRemaining()
                ? sink.slice() // retain remaining free space for further writes
                : null;
        pipe.offer(sink.flip().asReadOnlyBuffer());
        signalsAvailable = true;
      }
    }
    return signalsAvailable;
  }

  private static int getSinkBufferSize() {
    int size = Integer.getInteger(SINK_BUFFER_SIZE_PROP, DEFAULT_SINK_BUFFER_SIZE);
    if (size <= 0) {
      return DEFAULT_SINK_BUFFER_SIZE;
    }
    return size;
  }

  /** Returns a new {@code WritableBodyPublisher}. */
  public static WritableBodyPublisher create() {
    return new WritableBodyPublisher();
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
      synchronized (writeLock) {
        ByteBuffer sink = sinkBuffer;
        do {
          if (sink == null) {
            sink = ByteBuffer.allocate(SINK_BUFFER_SIZE);
          }
          written += Utils.copyRemaining(src, sink);
          if (!sink.hasRemaining()) {
            pipe.offer(sink.flip().asReadOnlyBuffer());
            signalsAvailable = true;
            sink = null;
          }
        } while (src.hasRemaining() && isOpen());
        if (closed) {
          sinkBuffer = null;
          if (written <= 0) { // only report if no bytes were written
            throw new ClosedChannelException();
          }
        } else {
          sinkBuffer = sink;
        }
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

  // Adapts a channel to OutputStream. Most work is done by Channels#newOutputStream but overridden
  // to forward flush() and close() to this publisher.
  private final class SinkChannelAdapter extends OutputStream {

    private final OutputStream out;

    SinkChannelAdapter(WritableByteChannel channel) {
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
        throw new IOException("closed"); // throw a more appropriate exception for an OutputStream
      }
    }

    @Override
    public void close() {
      WritableBodyPublisher.this.close();
    }
  }

  private final class SubscriptionImpl extends AbstractSubscription<ByteBuffer> {

    private @Nullable ByteBuffer currentBatch;

    SubscriptionImpl(Subscriber<? super ByteBuffer> downstream) {
      super(downstream, FlowSupport.SYNC_EXECUTOR);
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // ByteBuffer sentinel
    protected long emit(Subscriber<? super ByteBuffer> downstream, long emit) {
      // List is polled prematurely to detect completion regardless of demand
      ByteBuffer batch = currentBatch;
      currentBatch = null;
      if (batch == null) {
        batch = pipe.poll();
      }
      long submitted = 0L;
      while(true) {
        if (batch == CLOSED) {
          cancelOnComplete(downstream);
          return 0;
        } else if (submitted >= emit || batch == null) { // exhausted either demand or batches
          currentBatch = batch; // might be non-null
          return submitted;
        } else if (submitOnNext(downstream, batch)) {
          submitted++;
          batch = pipe.poll(); // get next batch and continue
        } else {
          return 0;
        }
      }
    }

    @Override
    protected void abort(boolean flowInterrupted) {
      WritableBodyPublisher.this.downstreamSubscription = null; // loose reference "this"
      pipe.clear();
    }
  }
}
