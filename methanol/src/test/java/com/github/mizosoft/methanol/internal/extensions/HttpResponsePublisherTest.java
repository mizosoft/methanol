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

import static com.github.mizosoft.methanol.MutableRequest.GET;
import static com.github.mizosoft.methanol.testutils.TestUtils.headers;
import static java.net.http.HttpResponse.BodyHandlers.replacing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorConfig;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorParameterizedTest;
import com.github.mizosoft.methanol.HttpClientStub;
import com.github.mizosoft.methanol.HttpResponseStub;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.testutils.TestException;
import com.github.mizosoft.methanol.testutils.TestSubscriber;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ExecutorExtension.class)
class HttpResponsePublisherTest {
  private static final long DELAY_MILLIS = 100L;
  private static final Executor delayer = CompletableFuture.delayedExecutor(
      DELAY_MILLIS, TimeUnit.MILLISECONDS, FlowSupport.SYNC_EXECUTOR);

  @ExecutorParameterizedTest
  @ExecutorConfig
  void sendNoPush(Executor executor) throws Exception {
    var request = GET("https://localhost");
    var handler = replacing(null);
    var client = new FakeHttpClient();
    var publisher = createPublisher(client, request, handler, null, executor);
    var subscriber = new TestSubscriber<HttpResponse<?>>();
    subscriber.request = 0L;
    publisher.subscribe(subscriber);
    subscriber.awaitSubscribe();
    // HTTP request shouldn't be sent till requested by subscriber
    // (not a very pragmatic method to check but it'd suffice :p)
    Thread.sleep(800);
    assertEquals(0, client.calls.get());

    subscriber.subscription.request(10L);
    client.awaitSend.join();
    assertSame(request, client.request);
    assertSame(handler, client.handler);

    var response = new HttpResponseStub<>();
    client.responseCf.complete(response);
    subscriber.awaitComplete();
    assertEquals(1, client.calls.get());
    assertEquals(1, subscriber.nexts);
    assertEquals(0, subscriber.errors);
    assertEquals(1, subscriber.completes);
    assertEquals(response, subscriber.items.poll());
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void sendNoPush_completeExceptionally(Executor executor) {
    var request = GET("https://localhost");
    var client = new FakeHttpClient();
    var publisher = createPublisher(client, request, replacing(null), null, executor);
    var subscriber = new TestSubscriber<HttpResponse<?>>();
    publisher.subscribe(subscriber);
    delayer.execute(() -> client.responseCf.completeExceptionally(new TestException()));
    subscriber.awaitError();
    assertEquals(0, subscriber.nexts);
    assertEquals(1, subscriber.errors);
    assertEquals(CompletionException.class, subscriber.lastError.getClass());
    assertEquals(TestException.class, subscriber.lastError.getCause().getClass());
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void sendWithPush(Executor executor) {
    var request = GET("https://localhost/push");
    var mainResponseHandler = replacing("");
    var pushResponseHandler = replacing("gibe that push");
    var client = new FakeHttpClient();
    var pushAcceptor = new PushAcceptor<>(path -> path.endsWith("accept"), pushResponseHandler);
    var publisher = createPublisher(client, request, mainResponseHandler, pushAcceptor, executor);
    var subscriber = new TestSubscriber<HttpResponse<?>>();
    publisher.subscribe(subscriber);

    client.awaitSend.join();
    assertSame(request, client.request);
    assertNotEquals(mainResponseHandler, client.handler); // wrapped for NotifyingBodySubscriber
    assertNotNull(client.pushPromiseHandler);

    @SuppressWarnings("unchecked")
    var ppHandler = (PushPromiseHandler<String>) client.pushPromiseHandler;
    var accept = GET("https://localhost/accept"); // will be accepted by pushReceiver
    var reject = GET("https://localhost/reject"); // will be rejected by pushReceiver
    var pusCompleter = new PushCompleter<String>();
    ppHandler.applyPushPromise(request, accept, pusCompleter);
    ppHandler.applyPushPromise(request, reject, pusCompleter);
    ppHandler.applyPushPromise(request, accept, pusCompleter);
    assertEquals(2, pusCompleter.acceptedCfs.size());
    pusCompleter.bodyHandlers.forEach(h -> assertSame(pushResponseHandler, h));

    pusCompleter.acceptedCfs.forEach(cf -> cf.complete(new HttpResponseStub<>()));
    // 2 completed pushes, main response not yet completed
    subscriber.awaitNext(2);
    assertEquals(0, subscriber.completes);

    client.responseCf.complete(new HttpResponseStub<>());
    // main response completed, but not the body! so must be expecting more push promises
    subscriber.awaitNext(1);
    assertEquals(0, subscriber.completes);

    // another push promise after initial response completion...
    pusCompleter.acceptedCfs.clear();
    ppHandler.applyPushPromise(request, accept, pusCompleter);
    assertEquals(1, pusCompleter.acceptedCfs.size());
    pusCompleter.acceptedCfs.get(0).complete(new HttpResponseStub<>());
    subscriber.awaitNext(1);
    assertEquals(0, subscriber.completes);

    // and finally complete main response body
    var mainResponseSubscriber = client.handler.apply(
        new ImmutableResponseInfo(200, headers(/* empty */), Version.HTTP_2));
    mainResponseSubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    mainResponseSubscriber.onComplete();
    subscriber.awaitComplete();
    assertEquals(1, subscriber.completes);
    assertEquals(0, subscriber.errors);
    assertEquals(1, client.calls.get());
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void throwFromPushReceiver(Executor executor) {
    // accepts one push then throws
    var pushAcceptor = new PushAcceptor<>(s -> true, replacing("gibe dat push")) {
      final AtomicInteger calls = new AtomicInteger();
      @Override
      public @Nullable BodyHandler<String> apply(HttpRequest request) {
        if (calls.incrementAndGet() > 1) {
          throw new TestException();
        }
        return super.apply(request);
      }
    };
    var request = GET("https://localhost");
    var client = new FakeHttpClient();
    var publisher = createPublisher(client, request, replacing(""), pushAcceptor, executor);
    var subscriber = new TestSubscriber<HttpResponse<String>>();
    publisher.subscribe(subscriber); // initiates sendAsync

    client.awaitSend.join();
    @SuppressWarnings("unchecked")
    var ppHandler = (PushPromiseHandler<String>) client.pushPromiseHandler;
    var pushCompleter = new PushCompleter<String>();
    ppHandler.applyPushPromise(request, GET("https://localhost/push1"), pushCompleter);
    ppHandler.applyPushPromise(request, GET("https://localhost/push2"), pushCompleter); // throws
    ppHandler.applyPushPromise(request, GET("https://localhost/push2"), pushCompleter); // ignored!
    assertEquals(1, pushCompleter.acceptedCfs.size());
    assertEquals(2, pushAcceptor.calls.get());

    // complete push and main
    var mainResponseSubscriber = client.handler.apply(
        new ImmutableResponseInfo(200, headers(/* empty */), Version.HTTP_2));
    mainResponseSubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    mainResponseSubscriber.onComplete();
    pushCompleter.acceptedCfs.forEach(cf -> cf.complete(new HttpResponseStub<>()));
    client.responseCf.complete(new HttpResponseStub<>());

    // should be completed exceptionally without receiving any responses
    subscriber.awaitError();
    assertEquals(0, subscriber.nexts);
    assertEquals(0, subscriber.completes);
    assertEquals(1, subscriber.errors);
    assertSame(TestException.class, subscriber.lastError.getClass());
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void throwFromSendAsync(Executor executor) {
    var request = GET("https://localhost");
    var client = new HttpClientStub() {
      @Override
      public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
          BodyHandler<T> responseBodyHandler) {
        throw new TestException();
      }
    };
    var publisher = createPublisher(client, request, replacing(null), null, executor);
    var subscriber = new TestSubscriber<HttpResponse<?>>();
    publisher.subscribe(subscriber);
    subscriber.awaitError();
    assertEquals(1, subscriber.errors);
    assertSame(TestException.class, subscriber.lastError.getClass());
  }

  /**
   * Check that the publisher refuses pushes after initial response body completion, which is not
   * allowed by the spec.
   */
  @ExecutorParameterizedTest
  @ExecutorConfig
  void handlesPushesAfterInitialCompletion(Executor executor) {
    var request = GET("https://localhost");
    var client = new FakeHttpClient();
    var publisher = createPublisher(
        client, request, replacing(""), req -> replacing("gibe that push"), executor); // accept all pushes
    var subscriber = new TestSubscriber<HttpResponse<String>>();
    publisher.subscribe(subscriber);

    client.awaitSend.join();

    // complete initial body
    var mainBodySubscriber = client.handler.apply(
        new ImmutableResponseInfo(200, headers(/* empty */), Version.HTTP_2));
    mainBodySubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    mainBodySubscriber.onComplete();

    @SuppressWarnings("unchecked")
    var ppHandler = (PushPromiseHandler<String>) client.pushPromiseHandler;
    var pushCompleter = new PushCompleter<String>();
    ppHandler.applyPushPromise(request, GET("https://localhost/push"), pushCompleter);
    ppHandler.applyPushPromise(request, GET("https://localhost/push"), pushCompleter);
    // non should be accepted
    assertEquals(0, pushCompleter.acceptedCfs.size());

    // complete main response normally
    client.responseCf.complete(new HttpResponseStub<>());

    // subscriber should be completed exceptionally with no responses
    subscriber.awaitComplete();
    assertEquals(0, subscriber.nexts);
    assertEquals(0, subscriber.completes);
    assertEquals(1, subscriber.errors);
    assertSame(IllegalStateException.class, subscriber.lastError.getClass());
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void completePushExceptionally(Executor executor) {
    var request = GET("https://localhost");
    var client = new FakeHttpClient();
    var publisher = createPublisher(
        client, request, replacing(""), req -> replacing("gibe that push"), executor); // accept all pushes
    var subscriber = new TestSubscriber<HttpResponse<String>>();
    publisher.subscribe(subscriber);

    client.awaitSend.join();

    // complete push exceptionally
    @SuppressWarnings("unchecked")
    var ppHandler = (PushPromiseHandler<String>) client.pushPromiseHandler;
    var pushCompleter = new PushCompleter<String>();
    ppHandler.applyPushPromise(request, GET("https://localhost/push"), pushCompleter);
    assertEquals(1, pushCompleter.acceptedCfs.size());
    delayer.execute(
        () -> pushCompleter.acceptedCfs.get(0).completeExceptionally(new TestException()));

    // complete initial body
    var mainBodySubscriber = client.handler.apply(
        new ImmutableResponseInfo(200, headers(/* empty */), Version.HTTP_2));
    mainBodySubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    mainBodySubscriber.onComplete();

    // complete initial response normally
    client.responseCf.complete(new HttpResponseStub<>());

    subscriber.awaitComplete();
    assertEquals(1, subscriber.errors);
    assertEquals(0, subscriber.completes);
    assertSame(TestException.class, subscriber.lastError.getClass());
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void request1NoPush(Executor executor) {
    var client = new FakeHttpClient();
    var publisher = createPublisher(
        client, GET("https://localhost"), replacing(null), null, executor);
    var subscriber = new TestSubscriber<HttpResponse<?>>();
    subscriber.request = 0L;
    publisher.subscribe(subscriber);
    subscriber.awaitSubscribe();
    subscriber.subscription.request(1L);

    // fulfill demand
    client.responseCf.complete(new HttpResponseStub<>());

    // should be completed even after 0 demand
    subscriber.awaitComplete();
    assertEquals(1, subscriber.nexts);
    assertEquals(1, subscriber.completes);
    assertEquals(0, subscriber.errors);
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void request3WithPush(Executor executor) {
    var request = GET("https://localhost");
    var client = new FakeHttpClient();
    var publisher = createPublisher(
        client, request, replacing(""), push -> replacing("gibe that push"), executor);
    var subscriber = new TestSubscriber<HttpResponse<?>>();
    subscriber.request = 0L;
    publisher.subscribe(subscriber);
    subscriber.awaitSubscribe();
    subscriber.subscription.request(3L);

    client.awaitSend.join();

    // apply 2 pushes
    @SuppressWarnings("unchecked")
    var ppHandler = (PushPromiseHandler<String>) client.pushPromiseHandler;
    var pushCompleter = new PushCompleter<String>();
    ppHandler.applyPushPromise(request, GET("https://localhost/push1"), pushCompleter);
    ppHandler.applyPushPromise(request, GET("https://localhost/push2"), pushCompleter);
    assertEquals(2, pushCompleter.acceptedCfs.size());

    // fulfill 2 demand slots with accepted pushes (delayed)
    pushCompleter.acceptedCfs.forEach(
        cf -> delayer.execute(() -> cf.complete(new HttpResponseStub<>())));

    // fulfill 1 demand slot with main response
    client.responseCf.complete(new HttpResponseStub<>());

    // complete main response body (delayed)
    var mainBodySubscriber = client.handler.apply(
        new ImmutableResponseInfo(200, headers(/* empty */), Version.HTTP_2));
    mainBodySubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    delayer.execute(mainBodySubscriber::onComplete);

    // should be completed even after 0 demand
    subscriber.awaitComplete();
    assertEquals(3, subscriber.nexts);
    assertEquals(1, subscriber.completes);
    assertEquals(0, subscriber.errors);
  }

  private <T> HttpResponsePublisher<T> createPublisher(
      HttpClient client,
      HttpRequest request,
      BodyHandler<T> handler,
      @Nullable Function<HttpRequest, BodyHandler<T>> func,
      Executor executor) {
    return new HttpResponsePublisher<>(
        client, request, handler, func, executor);
  }

  private static class PushAcceptor<T>
      implements Function<HttpRequest, @Nullable BodyHandler<T>> {

    private final Predicate<String> pathTester;
    private final BodyHandler<T> pushResponseHandler;

    PushAcceptor(
        Predicate<String> pathTester,
        BodyHandler<T> pushResponseHandler) {
      this.pathTester = pathTester;
      this.pushResponseHandler = pushResponseHandler;
    }

    @Override
    public @Nullable BodyHandler<T> apply(HttpRequest request) {
      return pathTester.test(request.uri().getPath()) ? pushResponseHandler : null;
    }
  }

  private static class PushCompleter<T>
      implements Function<BodyHandler<T>, CompletableFuture<HttpResponse<T>>> {

    final List<BodyHandler<T>> bodyHandlers = new ArrayList<>();
    final List<CompletableFuture<HttpResponse<T>>> acceptedCfs = new ArrayList<>();

    PushCompleter() {}

    @Override
    public CompletableFuture<HttpResponse<T>> apply(BodyHandler<T> bodyHandler) {
      bodyHandlers.add(bodyHandler);
      var cf = new CompletableFuture<HttpResponse<T>>();
      acceptedCfs.add(cf);
      return cf;
    }
  }

  private static class FakeHttpClient extends HttpClientStub {
    HttpRequest request;
    BodyHandler<?> handler;
    PushPromiseHandler<?> pushPromiseHandler;

    final AtomicInteger calls = new AtomicInteger();
    final CompletableFuture<HttpResponse<?>> responseCf = new CompletableFuture<>();
    final CompletableFuture<Void> awaitSend = new CompletableFuture<>();

    FakeHttpClient() {}

    @Override
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        BodyHandler<T> responseBodyHandler) {
      calls.getAndIncrement();
      this.request = request;
      this.handler = responseBodyHandler;
      awaitSend.complete(null);
      return responseCf.thenApply(unchecked -> (HttpResponse<T>) unchecked);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        BodyHandler<T> responseBodyHandler,
        PushPromiseHandler<T> pushPromiseHandler) {
      calls.getAndIncrement();
      this.request = request;
      this.handler = responseBodyHandler;
      this.pushPromiseHandler = pushPromiseHandler;
      awaitSend.complete(null);
      return responseCf.thenApply(unchecked -> (HttpResponse<T>) unchecked);
    }
  }
}
