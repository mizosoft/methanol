/*
 * Copyright (c) 2023 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.tck;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.Utils;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.reactivestreams.tck.TestEnvironment;

public class TckUtils {
  private static final long TIMEOUT_MILLIS = 1000L;
  private static final long NO_SIGNAL_TIMEOUT_MILLIS = 200L;

  static final int BUFFER_SIZE = 1024;

  /**
   * An arbitrary max for the # of elements needed to be precomputed for creating the test
   * publisher. This avoids OMEs when createFlowPublisher() is called with a large # of elements
   * (currently happens with required_spec317_mustNotSignalOnErrorWhenPendingAboveLongMaxValue).
   */
  static final int MAX_PRECOMPUTED_ELEMENTS = 1 << 10;

  private static final List<ByteBuffer> dataItems =
      Stream.of("Lorem ipsum dolor sit amet".split("\\s"))
          .map(UTF_8::encode)
          .collect(Collectors.toUnmodifiableList());

  private static final AtomicInteger index = new AtomicInteger();

  private TckUtils() {}

  static TestEnvironment testEnvironment() {
    return new TestEnvironment(TIMEOUT_MILLIS, NO_SIGNAL_TIMEOUT_MILLIS, TIMEOUT_MILLIS);
  }

  static <T, R> Publisher<R> map(Publisher<T> publisher, Function<? super T, ? extends R> mapper) {
    return subscriber -> publisher.subscribe(subscriber != null ? map(subscriber, mapper) : null);
  }

  static <T, R> Subscriber<T> map(
      Subscriber<R> subscriber, Function<? super T, ? extends R> mapper) {
    return new Subscriber<>() {
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        subscriber.onSubscribe(subscription);
      }

      @Override
      public void onNext(T item) {
        requireNonNull(item);
        subscriber.onNext(mapper.apply(item));
      }

      @Override
      public void onError(Throwable throwable) {
        subscriber.onError(throwable);
      }

      @Override
      public void onComplete() {
        subscriber.onComplete();
      }
    };
  }

  static ByteBuffer generateData() {
    var data = ByteBuffer.allocate(TckUtils.BUFFER_SIZE);
    while (data.hasRemaining()) {
      Utils.copyRemaining(
          dataItems.get(index.getAndIncrement() % dataItems.size()).duplicate(), data);
    }
    return data.flip();
  }
}
