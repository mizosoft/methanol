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

package com.github.mizosoft.methanol.internal.extensions;

import com.github.mizosoft.methanol.internal.flow.ForwardingSubscriber;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Subscriber;
import java.util.function.Function;

/**
 * Adapts a subscriber to a {@code BodySubscriber} where the body's completion need not be in
 * accordance with {@code onComplete} or {@code onError}.
 *
 * @param <T> the body type
 * @param <S> the subscriber's type
 */
public final class AsyncSubscriberAdapter<T, S extends Subscriber<? super List<ByteBuffer>>>
    extends ForwardingSubscriber<List<ByteBuffer>>
    implements BodySubscriber<T> {

  private final S downstream;
  private final Function<? super S, ? extends CompletionStage<T>> asyncFinisher;

  public AsyncSubscriberAdapter(
      S downstream, Function<? super S, ? extends CompletionStage<T>> asyncFinisher) {
    this.downstream = downstream;
    this.asyncFinisher = asyncFinisher;
  }

  @Override
  protected Subscriber<? super List<ByteBuffer>> delegate() {
    return downstream;
  }

  @Override
  public CompletionStage<T> getBody() {
    return asyncFinisher.apply(downstream);
  }
}
