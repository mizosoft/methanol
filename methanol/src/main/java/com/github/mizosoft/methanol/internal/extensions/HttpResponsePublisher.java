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

package com.github.mizosoft.methanol.internal.extensions;

import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.flow.AbstractSubscription;
import com.github.mizosoft.methanol.internal.flow.ForwardingBodySubscriber;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.function.Function;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Publishes {@code HttpResponse<T>} resulting from sending the given request and possibly accepting
 * incoming push promises, if any.
 */
public final class HttpResponsePublisher<T> implements Publisher<HttpResponse<T>> {

  private final HttpClient client;
  private final HttpRequest request;
  private final BodyHandler<T> handler;
  private final @Nullable Function<HttpRequest, @Nullable BodyHandler<T>> pushPromiseAcceptor;
  private final Function<BodyHandler<T>, BodyHandler<T>> handlerDecorator;
  private final Executor executor;

  /**
   * Creates a new {@code HttpResponsePublisher}. If {@code pushPromiseAcceptor} is {@code null},
   * all push promises will be rejected.
   */
  public HttpResponsePublisher(
      HttpClient client,
      HttpRequest request,
      BodyHandler<T> handler,
      @Nullable Function<HttpRequest, @Nullable BodyHandler<T>> pushPromiseAcceptor,
      Function<BodyHandler<T>, BodyHandler<T>> handlerDecorator,
      Executor executor) {
    this.client = client;
    this.request = request;
    this.handler = handler;
    this.pushPromiseAcceptor = pushPromiseAcceptor;
    this.handlerDecorator = handlerDecorator;
    this.executor = executor;
  }

  @Override
  public void subscribe(Subscriber<? super HttpResponse<T>> subscriber) {
    requireNonNull(subscriber);
    new SubscriptionImpl<>(subscriber, this).signal(true);
  }

  private static final class SubscriptionImpl<V>
      extends AbstractSubscription<HttpResponse<V>>
      implements PushPromiseHandler<V> {

    private static final VarHandle ONGOING;

    static {
      try {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        ONGOING = lookup.findVarHandle(SubscriptionImpl.class, "ongoing", int.class);
      } catch (IllegalAccessException | NoSuchFieldException e) {
        throw new ExceptionInInitializerError(e);
      }
    }

    //    private static final int DEFAULT_HTTP_PORT = 80;
    //    private static final int DEFAULT_HTTPS_PORT = 443;

    private final HttpClient client;
    private final HttpRequest initialRequest;
    private final BodyHandler<V> handler;
    // not null if accepting push promises
    private final @Nullable Function<HttpRequest, @Nullable BodyHandler<V>> pushPromiseAcceptor;
    private final Function<BodyHandler<V>, BodyHandler<V>> handlerDecorator;
    private final ConcurrentLinkedQueue<HttpResponse<V>> receivedResponses;
    // track when to stop expecting more push promises
    private volatile boolean receivedInitialResponseBody;
    // number of currently ongoing requests
    private volatile int ongoing;
    private boolean initialRequestSent;

    SubscriptionImpl(
        Subscriber<? super HttpResponse<V>> downstream, HttpResponsePublisher<V> parent) {
      super(downstream, parent.executor);
      client = parent.client;
      initialRequest = parent.request;
      handler = parent.handler;
      pushPromiseAcceptor = parent.pushPromiseAcceptor;
      handlerDecorator = parent.handlerDecorator;
      receivedResponses = new ConcurrentLinkedQueue<>();
    }

    @Override
    public void applyPushPromise(
        HttpRequest initiatingRequest,
        HttpRequest pushPromiseRequest,
        Function<BodyHandler<V>, CompletableFuture<HttpResponse<V>>> completer) {
      if (receivedInitialResponseBody) {
        signalError(
            new IllegalStateException(
                "receiving push promise after initial response body has been received: "
                    + pushPromiseRequest));
      } else if (!(isCancelled() || hasPendingErrors())) {
        handlePushPromise(pushPromiseRequest, completer);
      }
    }

    private void handlePushPromise(
        HttpRequest incomingPushPromise,
        Function<BodyHandler<V>, CompletableFuture<HttpResponse<V>>> completer) {
      requireState(pushPromiseAcceptor != null, "unexpected push promise");
      BodyHandler<V> pushedResponseHandler;
      try {
        pushedResponseHandler = pushPromiseAcceptor.apply(incomingPushPromise);
      } catch (Throwable t) {
        signalError(t);
        return;
      }
      if (pushedResponseHandler != null) {
        ONGOING.getAndAdd(this, 1);
        completer.apply(handlerDecorator.apply(pushedResponseHandler))
            .whenComplete(this::onCompletion);
      }
    }

    @Override
    protected long emit(Subscriber<? super HttpResponse<V>> downstream, long emit) {
      // send initial request if this is the first emit() (with +ve demand)
      if (emit > 0 && !initialRequestSent) {
        initialRequestSent = true;
        ONGOING.getAndAdd(this, 1);
        try {
          CompletableFuture<HttpResponse<V>> responseFuture =
              pushPromiseAcceptor != null
                  ? client.sendAsync(initialRequest, this::notifyOnCompletion, this)
                  : client.sendAsync(initialRequest, handler);
          responseFuture.whenComplete(this::onCompletion);
        } catch (Throwable t) {
          cancelOnError(downstream, t, true);
        }
        return 0; // we know there are no signals now so return
      }

      long submitted = 0L;
      while(true) {
        HttpResponse<V> response;
        if (receivedAllResponses()) {
          cancelOnComplete(downstream);
          return 0;
        } else if (submitted >= emit
            || (response = receivedResponses.poll()) == null) { // exhausted demand or responses
          return submitted;
        } else if (submitOnNext(downstream, response)) {
          ONGOING.getAndAdd(this, -1);
          submitted++;
        } else {
          return 0;
        }
      }
    }

    private void onCompletion(HttpResponse<V> response, Throwable error) {
      if (isCancelled()) {
        return;
      }

      if (error != null) {
        signalError(error);
      } else {
        receivedResponses.offer(response);
        signal(false);
      }
    }

    private void initialResponseBodyReceived() {
      receivedInitialResponseBody = true;
      // Need to force signal as downstream might be waiting for completion (ongoing == 0).
      // This is possible if response completion precedes body completion. (e.g. InputStream body)
      signal(true);
    }

    private boolean receivedAllResponses() {
      return ongoing == 0
          && initialRequestSent
          && (pushPromiseAcceptor == null || receivedInitialResponseBody);
    }

    /*private boolean hasSameOrigin(HttpRequest pushPromise) {
      URI initialRequestUri = initialRequest.uri();
      URI pushPromiseUri = pushPromise.uri();
      return initialRequestUri.getHost().equalsIgnoreCase(pushPromiseUri.getHost())
          && getPortOrDefault(initialRequestUri) == getPortOrDefault(pushPromiseUri);
    }

    private int getPortOrDefault(URI uri) {
      int port = uri.getPort();
      if (port == -1) {
        port = "https".equalsIgnoreCase(uri.getScheme()) ? DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;
      }
      return port;
    }*/

    private BodySubscriber<V> notifyOnCompletion(ResponseInfo info) {
      return new NotifyingBodySubscriber<>(handler.apply(info), this::initialResponseBodyReceived);
    }
  }

  /**
   * Notifies parent after initial response body has been received, after then no push promise is
   * expected. Helpful to know when to complete downstream if accepting push promises.
   */
  private static final class NotifyingBodySubscriber<R> extends ForwardingBodySubscriber<R> {

    private final Runnable onCompletion;

    NotifyingBodySubscriber(BodySubscriber<R> downstream, Runnable onCompletion) {
      super(downstream);
      this.onCompletion = onCompletion;
    }

    @Override
    public void onComplete() {
      try {
        super.onComplete();
      } finally {
        onCompletion.run();
      }
    }
  }
}
