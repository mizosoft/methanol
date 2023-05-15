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

import static com.github.mizosoft.methanol.MutableRequest.GET;
import static com.github.mizosoft.methanol.testing.RecordingHttpClient.defaultResponseFor;
import static com.github.mizosoft.methanol.testing.TestUtils.headers;
import static com.google.common.base.Charsets.UTF_8;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.from;

import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorParameterizedTest;
import com.github.mizosoft.methanol.testing.HttpClientStub;
import com.github.mizosoft.methanol.testing.RecordingHttpClient;
import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.TestSubscriber;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ExecutorExtension.class)
class HttpResponsePublisherTest {
  @ExecutorParameterizedTest
  void successfulResponseWithoutPushPromises(Executor executor) {
    var request = GET("https://localhost");
    var client = new RecordingHttpClient();
    var publisher =
        new HttpResponsePublisher<>(client, request, BodyHandlers.replacing("A"), null, executor);
    var subscriber = new TestSubscriber<HttpResponse<?>>().autoRequest(0);
    publisher.subscribe(subscriber);

    // The request isn't sent until the first demand from downstream.
    subscriber.awaitSubscription();
    assertThat(client.sendCount()).isZero();

    subscriber.requestItems(1);

    var call = client.awaitCall();
    call.complete();
    assertThat(client.sendCount()).isOne();

    // As push promises can be received anytime amid receiving the main response body, downstream
    // waits for body's completion and not just for the response completion (the former may happen
    // after the latter for InputStream bodies and the like).
    assertThat(subscriber.completionCount()).isZero();

    complete(call.bodyHandler());
    subscriber.awaitCompletion();
    assertThat(subscriber.pollNext()).returns(request, from(HttpResponse::request));
  }

  @ExecutorParameterizedTest
  void failedResponseWithoutPushPromises(Executor executor) {
    var request = GET("https://localhost");
    var client = new RecordingHttpClient();
    var publisher =
        new HttpResponsePublisher<>(client, request, BodyHandlers.replacing("A"), null, executor);
    var subscriber = new TestSubscriber<>().autoRequest(0);
    publisher.subscribe(subscriber);
    subscriber.requestItems(1);
    client.awaitCall().completeExceptionally(new TestException());
    assertThat(subscriber.awaitError()).isInstanceOf(TestException.class);
  }

  @ExecutorParameterizedTest
  void successfulResponseWithPushPromises(Executor executor) {
    var request = GET("https://localhost/push");
    var client = new RecordingHttpClient();
    var publisher =
        new HttpResponsePublisher<>(
            client,
            request,
            BodyHandlers.ofString(),
            pushPromiseRequest ->
                pushPromiseRequest.uri().getPath().contains("accept")
                    ? BodyHandlers.ofString()
                    : null,
            executor);
    var subscriber = new TestSubscriber<HttpResponse<String>>().autoRequest(0);
    publisher.subscribe(subscriber);

    // Request one to send the request.
    subscriber.requestItems(1);

    var call = client.<String>awaitCall();
    var pushPromiseTracker = new PushPromiseTracker<String>(request);
    var pushPromiseHandler = call.pushPromiseHandler().orElseThrow(AssertionError::new);

    pushPromiseTracker.apply(pushPromiseHandler, GET("https://localhost/accept1"));
    assertThat(pushPromiseTracker.acceptedCount()).isOne();

    pushPromiseTracker.apply(pushPromiseHandler, GET("https://localhost/reject"));
    assertThat(pushPromiseTracker.acceptedCount()).isOne();

    pushPromiseTracker.apply(pushPromiseHandler, GET("https://localhost/accept2"));
    assertThat(pushPromiseTracker.acceptedCount()).isEqualTo(2);

    pushPromiseTracker.completeNextPushPromise(UTF_8.encode("push1"));
    assertThat(subscriber.pollNext())
        .returns(GET("https://localhost/accept1"), from(HttpResponse::request));
    assertThat(subscriber.completionCount()).isZero();

    call.complete(UTF_8.encode("main"));
    pushPromiseTracker.completeNextPushPromise(UTF_8.encode("push2"));
    subscriber.requestItems(2);
    assertThat(subscriber.pollNext(2))
        .map(HttpResponse::request)
        .containsExactlyInAnyOrder(request, GET("https://localhost/accept2"));

    subscriber.awaitCompletion();
    assertThat(subscriber.nextCount()).isEqualTo(3);
    assertThat(client.sendCount()).isEqualTo(1);
  }

  @ExecutorParameterizedTest
  void throwFromPushPromiseMapper(Executor executor) {
    // A push promise mapper that accepts at most one push promise then always throws.
    var faultyPushPromiseMapper =
        new Function<HttpRequest, BodyHandler<String>>() {
          final AtomicBoolean firstCall = new AtomicBoolean();

          @Override
          public BodyHandler<String> apply(HttpRequest request) {
            if (!firstCall.compareAndSet(false, true)) {
              throw new TestException();
            }
            return BodyHandlers.replacing("B");
          }
        };
    var request = GET("https://localhost");
    var client = new RecordingHttpClient();
    var publisher =
        new HttpResponsePublisher<>(
            client, request, BodyHandlers.replacing("A"), faultyPushPromiseMapper, executor);
    var subscriber = new TestSubscriber<HttpResponse<String>>().autoRequest(0);
    publisher.subscribe(subscriber);
    subscriber.requestItems(1);

    var call = client.<String>awaitCall();
    var pushPromiseTracker = new PushPromiseTracker<String>(request);
    var pushPromiseHandler = call.pushPromiseHandler().orElseThrow(AssertionError::new);
    pushPromiseTracker.apply(pushPromiseHandler, GET("https://localhost/push1")); // Accepted
    pushPromiseTracker.apply(pushPromiseHandler, GET("https://localhost/push2")); // Throws.
    pushPromiseTracker.apply(
        pushPromiseHandler, GET("https://localhost/push2")); // Ignored due to previous failure.
    assertThat(pushPromiseTracker.acceptedCount()).isOne();
    assertThat(subscriber.awaitError()).isInstanceOf(TestException.class);
  }

  @ExecutorParameterizedTest
  void throwFromSendAsync(Executor executor) {
    var request = GET("https://localhost");
    var client =
        new HttpClientStub() {
          @Override
          public <T> CompletableFuture<HttpResponse<T>> sendAsync(
              HttpRequest request,
              BodyHandler<T> responseBodyHandler,
              PushPromiseHandler<T> pushPromiseHandler) {
            throw new TestException();
          }
        };
    var publisher =
        new HttpResponsePublisher<>(client, request, BodyHandlers.replacing("A"), null, executor);
    var subscriber = new TestSubscriber<>().autoRequest(0);
    publisher.subscribe(subscriber);
    subscriber.requestItems(1);
    assertThat(subscriber.awaitError()).isInstanceOf(TestException.class);
  }

  /** Check that the publisher refuses push promises after initial response body completion. */
  @ExecutorParameterizedTest
  void pushPromisesAfterInitialResponseBodyCompletion(Executor executor) {
    var request = GET("https://localhost");
    var client = new RecordingHttpClient();
    var publisher =
        new HttpResponsePublisher<>(
            client,
            request,
            BodyHandlers.replacing("A"),
            __ -> BodyHandlers.replacing("B"),
            executor);
    var subscriber = new TestSubscriber<HttpResponse<String>>().autoRequest(0);
    publisher.subscribe(subscriber);
    subscriber.requestItems(1);

    var call = client.<String>awaitCall();
    complete(call.bodyHandler());

    var pushPromiseHandler = call.requiredPushPromiseHandler();
    var pushPromiseTracker = new PushPromiseTracker<String>(request);
    pushPromiseTracker.apply(pushPromiseHandler, GET("https://localhost/push1"));
    pushPromiseTracker.apply(pushPromiseHandler, GET("https://localhost/push2"));
    assertThat(pushPromiseTracker.acceptedCount()).isZero();
    assertThat(subscriber.awaitError()).isInstanceOf(IllegalStateException.class);
  }

  @ExecutorParameterizedTest
  void completePushPromiseExceptionally(Executor executor) {
    var request = GET("https://localhost");
    var client = new RecordingHttpClient();
    var publisher =
        new HttpResponsePublisher<>(
            client,
            request,
            BodyHandlers.replacing("A"),
            __ -> BodyHandlers.replacing("B"),
            executor);
    var subscriber = new TestSubscriber<HttpResponse<String>>();
    publisher.subscribe(subscriber);

    var call = client.<String>awaitCall();
    var pushPromiseTracker = new PushPromiseTracker<String>(request);
    var pushPromiseHandler = call.requiredPushPromiseHandler();
    pushPromiseTracker.apply(pushPromiseHandler, GET("https://localhost/push"));
    pushPromiseTracker.completeNextPromiseExceptionally(new TestException());
    assertThat(subscriber.awaitError()).isInstanceOf(TestException.class);
  }

  private static <T> void complete(BodyHandler<T> bodyHandler) {
    var bodySubscriber =
        bodyHandler.apply(new ImmutableResponseInfo(HTTP_OK, headers(), Version.HTTP_2));
    bodySubscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    bodySubscriber.onComplete();
  }

  private static class PushPromiseTracker<T> {
    private final HttpRequest initiatingRequest;
    private final List<PushPromise<T>> acceptedPushPromises = new ArrayList<>();

    PushPromiseTracker(HttpRequest initiatingRequest) {
      this.initiatingRequest = initiatingRequest;
    }

    void apply(PushPromiseHandler<T> handler, HttpRequest pushPromiseRequest) {
      handler.applyPushPromise(
          initiatingRequest,
          pushPromiseRequest,
          bodyHandler -> {
            var pushPromise = new PushPromise<>(pushPromiseRequest, bodyHandler);
            acceptedPushPromises.add(pushPromise);
            return pushPromise.future;
          });
    }

    int acceptedCount() {
      return acceptedPushPromises.size();
    }

    void completeNextPushPromise(ByteBuffer responseBody) {
      acceptedPushPromises.stream()
          .filter(Predicate.not(PushPromise::isDone))
          .findFirst()
          .ifPresentOrElse(
              pushPromise -> pushPromise.complete(responseBody),
              () -> fail("no push promise to complete"));
    }

    void completeNextPromiseExceptionally(Throwable exception) {
      acceptedPushPromises.stream()
          .filter(Predicate.not(PushPromise::isDone))
          .findFirst()
          .orElseThrow(() -> new AssertionError("no push promises to complete"))
          .completeExceptionally(exception);
    }
  }

  private static final class PushPromise<T> {
    private final HttpRequest request;
    private final BodyHandler<T> bodyHandler;
    final CompletableFuture<HttpResponse<T>> future = new CompletableFuture<>();

    PushPromise(HttpRequest request, BodyHandler<T> bodyHandler) {
      this.request = requireNonNull(request);
      this.bodyHandler = requireNonNull(bodyHandler);
    }

    void complete(ByteBuffer responseBody) {
      future.complete(defaultResponseFor(request, responseBody, bodyHandler));
    }

    void completeExceptionally(Throwable exception) {
      future.completeExceptionally(exception);
    }

    boolean isDone() {
      return future.isDone();
    }
  }
}
