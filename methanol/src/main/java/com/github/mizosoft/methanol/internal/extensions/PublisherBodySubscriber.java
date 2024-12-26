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

import com.github.mizosoft.methanol.internal.concurrent.SerialExecutor;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** A {@link BodySubscriber} that exposes the response body as a publisher. */
public final class PublisherBodySubscriber implements BodySubscriber<Publisher<List<ByteBuffer>>> {
  private static final Object COMPLETION_AWAITING_STATE = new Object();
  private static final Object DONE_STATE = new Object();

  private static final VarHandle SUBSCRIPTION;

  static {
    try {
      SUBSCRIPTION =
          MethodHandles.lookup()
              .findVarHandle(PublisherBodySubscriber.class, "subscription", Subscription.class);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final CompletableFuture<Publisher<List<ByteBuffer>>> publisherFuture =
      new CompletableFuture<>();
  private final SerialExecutor serializer = new SerialExecutor(FlowSupport.SYNC_EXECUTOR);

  @SuppressWarnings("FieldMayBeFinal") // VarHandle indirection.
  private Subscription subscription = FlowSupport.NOOP_SUBSCRIPTION;

  /**
   * An object that reflects this subscriber's state. {@code null} is the initial state. A {@code
   * Subscriber} means a downstream subscriber has been received, which is not yet completed or
   * errored. {@code COMPLETION_AWAITING_STATE} means this subscriber has completed before receiving
   * a downstream subscriber. An {@code Throwable} means this subscriber has been errored before
   * receiving downstream subscriber. {@code DONE_STATE} means the downstream subscriber has been
   * either completed or errored.
   */
  private @MonotonicNonNull Object state;

  public PublisherBodySubscriber() {}

  @Override
  public CompletionStage<Publisher<List<ByteBuffer>>> getBody() {
    return publisherFuture;
  }

  @Override
  public void onSubscribe(Subscription subscription) {
    requireNonNull(subscription);
    if (!publisherFuture.complete(subscriber -> subscribe(subscription, subscriber))) {
      subscription.cancel();
    }
  }

  private void subscribe(
      Subscription subscription, Subscriber<? super List<ByteBuffer>> subscriber) {
    requireNonNull(subscriber);
    if (!SUBSCRIPTION.compareAndSet(this, FlowSupport.NOOP_SUBSCRIPTION, subscription)) {
      FlowSupport.rejectMulticast(subscriber);
      return;
    }

    serializer.execute(
        () -> {
          Throwable onSubscribeException = null;
          try {
            subscriber.onSubscribe(subscription);
          } catch (Throwable t) {
            onSubscribeException = t;
          }

          if (state instanceof Throwable) {
            var upstreamException = (Throwable) state;
            state = DONE_STATE;
            if (onSubscribeException != null) {
              upstreamException.addSuppressed(onSubscribeException);
            }
            subscriber.onError(upstreamException);
          } else if (onSubscribeException != null
              && (state == COMPLETION_AWAITING_STATE || state == null)) {
            state = DONE_STATE;
            subscriber.onError(onSubscribeException);
          } else if (state == COMPLETION_AWAITING_STATE) {
            state = DONE_STATE;
            subscriber.onComplete();
          } else if (state == null) {
            state = subscriber;
          } else {
            // State couldn't have been a Subscriber as we reject multiple subscribers. And it could
            // not have been DONE_STATE before receiving a downstream subscriber.
            throw new AssertionError("Unexpected state: " + state);
          }
        });
  }

  @Override
  public void onNext(List<ByteBuffer> item) {
    requireNonNull(item);
    serializer.execute(
        () -> {
          if (state instanceof Subscriber<?>) {
            @SuppressWarnings("unchecked")
            var subscriber = ((Subscriber<? super List<ByteBuffer>>) state);
            try {
              subscriber.onNext(item);
            } catch (Throwable t) {
              state = DONE_STATE;
              subscription.cancel();
              subscriber.onError(t);
            }
          }
        });
  }

  @Override
  public void onError(Throwable throwable) {
    serializer.execute(
        () -> {
          if (state instanceof Subscriber<?>) {
            @SuppressWarnings("unchecked")
            var subscriber = ((Subscriber<? super List<ByteBuffer>>) state);
            state = DONE_STATE;
            subscriber.onError(throwable);
          } else if (state == null) {
            state = throwable;
          } else {
            FlowSupport.onDroppedException(throwable);
          }
        });
  }

  @Override
  public void onComplete() {
    serializer.execute(
        () -> {
          if (state instanceof Subscriber<?>) {
            @SuppressWarnings("unchecked")
            var subscriber = ((Subscriber<? super List<ByteBuffer>>) state);
            state = DONE_STATE;
            subscriber.onComplete();
          } else if (state == null) {
            state = COMPLETION_AWAITING_STATE;
          }
        });
  }

  public static BodyHandler<Publisher<List<ByteBuffer>>> bodyHandler() {
    return responseInfo -> new PublisherBodySubscriber();
  }
}
