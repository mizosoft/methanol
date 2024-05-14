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

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.flow.AbstractQueueSubscription;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.net.http.HttpResponse.ResponseInfo;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A publisher of {@link HttpResponse<T> responses} resulting from sending a given request and
 * optionally accepting incoming push promises, if any.
 */
public final class HttpResponsePublisher<T> implements Publisher<HttpResponse<T>> {
  private final AtomicBoolean subscribed = new AtomicBoolean();

  private final HttpClient client;
  private final HttpRequest request;
  private final BodyHandler<T> bodyHandler;
  private final @Nullable Function<HttpRequest, @Nullable BodyHandler<T>> pushPromiseHandler;
  private final Executor executor;

  /**
   * Creates a new {@code HttpResponsePublisher}. If {@code pushPromiseMapper} is {@code null}, all
   * push promises are rejected.
   */
  public HttpResponsePublisher(
      HttpClient client,
      HttpRequest request,
      BodyHandler<T> bodyHandler,
      @Nullable Function<HttpRequest, @Nullable BodyHandler<T>> pushPromiseMapper,
      Executor executor) {
    this.client = requireNonNull(client);
    this.request = requireNonNull(request);
    this.bodyHandler = requireNonNull(bodyHandler);
    this.pushPromiseHandler = pushPromiseMapper;
    this.executor = requireNonNull(executor);
  }

  @Override
  public void subscribe(Subscriber<? super HttpResponse<T>> subscriber) {
    if (subscribed.compareAndSet(false, true)) {
      new SubscriptionImpl<>(subscriber, this).fireOrKeepAlive();
    } else {
      FlowSupport.rejectMulticast(subscriber);
    }
  }

  private static final class SubscriptionImpl<V>
      extends AbstractQueueSubscription<HttpResponse<V>> {
    private final Lock lock = new ReentrantLock();

    private final HttpClient client;
    private final HttpRequest initialRequest;
    private final BodyHandler<V> handler;
    private final @Nullable PushPromiseHandler<V> pushPromiseHandler;

    /** The number of currently ongoing requests (original request plus push promises, if any). */
    @GuardedBy("lock")
    private int ongoing;

    /**
     * Whether the main response body has been received. After which we won't be expecting any push
     * promises.
     */
    @GuardedBy("lock")
    private boolean isInitialResponseBodyComplete;

    /**
     * Whether we've sent the initial request, which is delayed till we receive positive demand for
     * the first time. This doesn't need any synchronization as there's no contention.
     */
    private boolean isInitialRequestSent;

    SubscriptionImpl(
        Subscriber<? super HttpResponse<V>> downstream, HttpResponsePublisher<V> publisher) {
      super(downstream, publisher.executor);
      this.client = publisher.client;
      this.initialRequest = publisher.request;
      this.handler = publisher.bodyHandler;
      this.pushPromiseHandler =
          publisher.pushPromiseHandler != null
              ? new SubscriptionPushPromiseHandler(publisher.pushPromiseHandler)
              : null;
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    protected long emit(Subscriber<? super HttpResponse<V>> downstream, long emit) {
      if (emit > 0 && !isInitialRequestSent) {
        isInitialRequestSent = true;
        try {
          incrementOngoing();
          client
              .sendAsync(initialRequest, this::notifyOnBodyCompletion, pushPromiseHandler)
              .whenComplete(this::onResponse);
        } catch (Throwable t) {
          cancelOnError(downstream, t, true);
          return 0;
        }
      }
      return super.emit(downstream, emit);
    }

    private void onResponse(HttpResponse<V> response, Throwable exception) {
      if (exception != null) {
        fireOrKeepAliveOnError(exception);
      } else {
        boolean isComplete;
        lock.lock();
        try {
          isComplete = ongoing == 1 && isInitialResponseBodyComplete;
        } finally {
          lock.unlock();
        }

        if (isComplete) {
          submitAndComplete(response);
        } else {
          submit(response);

          boolean isCompleteAfterSubmit;
          lock.lock();
          try {
            int ongoingAfterDecrement = --ongoing;
            isCompleteAfterSubmit = ongoingAfterDecrement == 0 && isInitialResponseBodyComplete;
          } finally {
            lock.unlock();
          }

          if (isCompleteAfterSubmit) {
            complete();
          }
        }
      }
    }

    private void onInitialResponseBodyCompletion() {
      boolean isComplete;
      lock.lock();
      try {
        isInitialResponseBodyComplete = true;
        isComplete = ongoing == 0;
      } finally {
        lock.unlock();
      }

      if (isComplete) {
        complete();
      } else {
        fireOrKeepAlive();
      }
    }

    private BodySubscriber<V> notifyOnBodyCompletion(ResponseInfo info) {
      return new NotifyingBodySubscriber<>(
          handler.apply(info), this::onInitialResponseBodyCompletion);
    }

    private void incrementOngoing() {
      lock.lock();
      try {
        ongoing++;
      } finally {
        lock.unlock();
      }
    }

    private class SubscriptionPushPromiseHandler implements PushPromiseHandler<V> {
      private final Function<HttpRequest, @Nullable BodyHandler<V>> acceptor;

      SubscriptionPushPromiseHandler(Function<HttpRequest, @Nullable BodyHandler<V>> acceptor) {
        this.acceptor = acceptor;
      }

      @Override
      public void applyPushPromise(
          HttpRequest initiatingRequest,
          HttpRequest pushPromiseRequest,
          Function<BodyHandler<V>, CompletableFuture<HttpResponse<V>>> acceptor) {
        boolean localIsInitialResponseBodyComplete;
        lock.lock();
        try {
          localIsInitialResponseBodyComplete = isInitialResponseBodyComplete;
        } finally {
          lock.unlock();
        }

        if (localIsInitialResponseBodyComplete) {
          fireOrKeepAliveOnError(
              new IllegalStateException(
                  "Receiving push promise after initial response body has been received: "
                      + pushPromiseRequest));
        } else if (!(isCancelled() || hasPendingErrors())) {
          applyPushPromise(pushPromiseRequest, acceptor);
        }
      }

      @SuppressWarnings("FutureReturnValueIgnored")
      private void applyPushPromise(
          HttpRequest pushPromiseRequest,
          Function<BodyHandler<V>, CompletableFuture<HttpResponse<V>>> acceptor) {
        BodyHandler<V> pushedResponseBodyHandler;
        try {
          pushedResponseBodyHandler = this.acceptor.apply(pushPromiseRequest);
        } catch (Throwable t) {
          fireOrKeepAliveOnError(t);
          return;
        }

        if (pushedResponseBodyHandler != null) {
          incrementOngoing();
          acceptor.apply(pushedResponseBodyHandler).whenComplete(SubscriptionImpl.this::onResponse);
        }
      }
    }
  }

  /** A {@link BodySubscriber} that invokes a callback after the body has been fully received. */
  private static final class NotifyingBodySubscriber<R> extends ForwardingBodySubscriber<R> {
    private final Runnable callback;

    NotifyingBodySubscriber(BodySubscriber<R> downstream, Runnable onComplete) {
      super(downstream);
      this.callback = onComplete;
    }

    @Override
    public void onComplete() {
      super.onComplete();
      callback.run();
    }
  }
}
