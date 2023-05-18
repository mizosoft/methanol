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

package com.github.mizosoft.methanol.internal.extensions;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.flow.AbstractQueueSubscription;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
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
import java.util.function.Function;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A publisher of {@link HttpResponse<T> responses} resulting from sending a given request and
 * optionally accepting incoming push promises, if any.
 */
public final class HttpResponsePublisher<T> implements Publisher<HttpResponse<T>> {
  private final HttpClient client;
  private final HttpRequest request;
  private final BodyHandler<T> bodyHandler;
  private final @Nullable Function<HttpRequest, @Nullable BodyHandler<T>> pushPromiseHandler;
  private final Executor executor;
  private final AtomicBoolean subscribed = new AtomicBoolean();

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
    /**
     * Initial value for {@link #ongoing} indicating that the request/response(s) exchange hasn't
     * been initiated yet. MUST be negative! So it is not confused with any next possible value for
     * {@code ongoing}, which is always non-negative after the initial request.
     */
    private static final int IDLE = -1;

    private static final VarHandle ONGOING;

    static {
      try {
        var lookup = MethodHandles.lookup();
        ONGOING = lookup.findVarHandle(SubscriptionImpl.class, "ongoing", int.class);
      } catch (IllegalAccessException | NoSuchFieldException e) {
        throw new ExceptionInInitializerError(e);
      }
    }

    private final HttpClient client;
    private final HttpRequest initialRequest;
    private final BodyHandler<V> handler;
    private final @Nullable PushPromiseHandler<V> pushPromiseHandler;

    /**
     * The number of currently ongoing requests (the original request plus push promises, if any),
     * or {@link #IDLE} if the request hasn't been sent yet.
     */
    @SuppressWarnings({"unused", "FieldMayBeFinal"}) // VarHandle indirection.
    private volatile int ongoing = IDLE;

    private volatile boolean isInitialResponseBodyReceived;

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
    protected long emit(Subscriber<? super HttpResponse<V>> downstream, long emit) {
      if (emit > 0 && ongoing == IDLE) {
        // No concurrent modification for 'ongoing' can be running at this point as nothing can be
        // received yet.
        ONGOING.getAndAdd(this, -IDLE + 1);
        try {
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
        // TODO make comment more accurate
        // The testing order here is significant. After isInitialResponseBodyReceived is true, no
        // increments to ongoing are possible as all push promises would've been received (if we see
        // a zero, then it's the final state, and it is guaranteed that everything is done).
        // However, had we checked (decremented & checked) if currentOngoing == 0 first, we might
        // observe the following
        // state transition (assuming currentOngoing is indeed 0):
        //   - Observe currentOngoing == 0 (first test succeeds).
        //   - The testing thread is suspended.
        //   - One or more push promises are received, and 'ongoing' is incremented.
        //   - The main response body completes (isInitialResponseBodyReceived becomes true).
        //   - The testing thread wakes up.
        //   - Observe isInitialResponseBodyReceived == true (second test succeeds).
        //   - Downstream completes without waiting for received push promise(s).
        boolean noMorePushPromises = isInitialResponseBodyReceived;
        int currentOngoing = (int) ONGOING.getAndAdd(this, -1) - 1;
        if (noMorePushPromises && currentOngoing == 0) {
          submitAndComplete(response);
        } else {
          submit(response);
        }
      }
    }

    private void onReceivedInitialResponseBody() {
      isInitialResponseBodyReceived = true;
      if (ongoing == 0) {
        complete();
      } else {
        fireOrKeepAlive();
      }
    }

    private BodySubscriber<V> notifyOnBodyCompletion(ResponseInfo info) {
      return new NotifyingBodySubscriber<>(
          handler.apply(info), this::onReceivedInitialResponseBody);
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
        if (isInitialResponseBodyReceived) {
          fireOrKeepAliveOnError(
              new IllegalStateException(
                  "receiving push promise after initial response body has been received: "
                      + pushPromiseRequest));
        } else if (!(isCancelled() || hasPendingErrors())) {
          applyPushPromise(pushPromiseRequest, acceptor);
        }
      }

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
          ONGOING.getAndAdd(SubscriptionImpl.this, 1);
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
