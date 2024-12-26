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

import static com.github.mizosoft.methanol.internal.Validate.requireArgument;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.flow.AbstractPollableSubscription;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import java.net.http.HttpRequest.BodyPublisher;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow.Subscriber;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class ByteBufferBodyPublisher implements BodyPublisher {
  private final ByteBuffer buffer;
  private final int contentLength;
  private final int downstreamBufferSize;

  public ByteBufferBodyPublisher(ByteBuffer buffer) {
    this(buffer, Utils.BUFFER_SIZE);
  }

  public ByteBufferBodyPublisher(ByteBuffer buffer, int downstreamBufferSize) {
    this.buffer = buffer.duplicate();
    this.contentLength = this.buffer.remaining();
    requireArgument(downstreamBufferSize > 0, "Non-positive buffer size: %d", downstreamBufferSize);
    this.downstreamBufferSize = downstreamBufferSize;
  }

  @Override
  public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
    new ByteBufferSubscription(subscriber, buffer.duplicate(), downstreamBufferSize)
        .fireOrKeepAlive();
  }

  @Override
  public long contentLength() {
    return contentLength;
  }

  private static final class ByteBufferSubscription
      extends AbstractPollableSubscription<ByteBuffer> {
    private final ByteBuffer buffer;
    private final int bufferSize;

    ByteBufferSubscription(
        Subscriber<? super ByteBuffer> downstream, ByteBuffer buffer, int bufferSize) {
      super(downstream, FlowSupport.SYNC_EXECUTOR);
      this.buffer = buffer;
      this.bufferSize = bufferSize;
    }

    @Override
    protected @Nullable ByteBuffer poll() {
      if (!buffer.hasRemaining()) {
        return null;
      }

      int length = Math.min(bufferSize, buffer.remaining());
      int originalLimit = buffer.limit();
      int newPosition = buffer.position() + length;
      var next = buffer.limit(newPosition).slice();
      buffer.limit(originalLimit).position(newPosition);
      return next.asReadOnlyBuffer();
    }

    @Override
    protected boolean isComplete() {
      return !buffer.hasRemaining();
    }
  }
}
