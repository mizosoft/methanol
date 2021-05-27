/*
 * Copyright (c) 2021 Moataz Abdelnasser
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

import com.github.mizosoft.methanol.HttpReadTimeoutException;
import com.github.mizosoft.methanol.internal.delay.Delayer;
import com.github.mizosoft.methanol.internal.flow.TimeoutSubscriber;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Subscriber;

public class TimeoutBodySubscriber<T> extends TimeoutSubscriber<List<ByteBuffer>>
    implements BodySubscriber<T> {
  private final BodySubscriber<T> downstream;

  public TimeoutBodySubscriber(Duration timeout, Delayer delayer, BodySubscriber<T> downstream) {
    super(timeout, delayer);
    this.downstream = downstream;
  }

  @Override
  protected Subscriber<? super List<ByteBuffer>> downstream() {
    return downstream;
  }

  @Override
  protected Throwable timeoutError(long index, Duration timeout) {
    return new HttpReadTimeoutException(
        String.format("read [%d] timed out after %d ms", index, timeout.toMillis()));
  }

  @Override
  public CompletionStage<T> getBody() {
    return downstream.getBody();
  }
}
