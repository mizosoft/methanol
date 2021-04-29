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

package com.github.mizosoft.methanol.tck;

import static com.github.mizosoft.methanol.MutableRequest.GET;
import static com.github.mizosoft.methanol.testutils.TestUtils.localhostSslContext;

import com.github.mizosoft.methanol.internal.extensions.HttpResponsePublisher;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.tck.HttpResponsePublisherTck.ResponseHandle;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import okhttp3.Headers;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.PushPromise;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.reactivestreams.tck.flow.FlowPublisherVerification;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

public class HttpResponsePublisherTck extends FlowPublisherVerification<ResponseHandle> {

  private static final String BODY = "Who's Joe Mama?";

  private HttpClient client;
  private MockWebServer server;
  private HttpRequest request;

  public HttpResponsePublisherTck() {
    super(TckUtils.testEnvironmentWithTimeout(1_000)); // have a decent timeout due to actual IO
  }

  @BeforeClass
  public void setUpLifecycle() throws IOException {
    // HttpClient restricts HTTP/2 to HTTPS
    var sslContext = localhostSslContext();
    client = HttpClient.newBuilder().sslContext(sslContext).version(Version.HTTP_2).build();
    server = new MockWebServer();
    server.useHttps(sslContext.getSocketFactory(), false);
    server.start();
    request = GET(server.url("/").uri()).build();
  }

  @AfterClass
  public void tearDownLifecycle() throws IOException {
    server.shutdown();
  }

  // Overridden by ResponsePublisherWithExecutorTck for async version
  Executor executor() {
    return FlowSupport.SYNC_EXECUTOR;
  }

  @Override
  public Publisher<ResponseHandle> createFlowPublisher(long elements) {
    if (elements <= 0) {
      throw new SkipException("can publish at least one response before completion");
    }

    var mockResponse = mockResponseWithPush((int) elements - 1);
    server.setDispatcher(new Dispatcher() {
      @NotNull
      @Override
      public MockResponse dispatch(@NotNull RecordedRequest recordedRequest) {
        return mockResponse;
      }
    });
    var publisher =
        new HttpResponsePublisher<>(
            client,
            request,
            BodyHandlers.ofString(),
            req -> BodyHandlers.ofString(),
            executor());
    return subscriber -> publisher.subscribe(subscriber != null ? mapSubscriber(subscriber) : null);
  }

  @Override
  public Publisher<ResponseHandle> createFailedFlowPublisher() {
    // subscription.request(at least 1) must be called to try a request and fail
    throw new SkipException("should be able to try at least one request before failing");
  }

  @Override
  public long maxElementsFromPublisher() {
    // Items are buffered in memory before submission so a large # of elements
    // will cause OME when createFlowPublisher() is called (currently happens with
    // required_spec317_mustNotSignalOnErrorWhenPendingAboveLongMaxValue) so return
    // an small arbitrary num that allows other tests to pass
    return 1 << 15;
  }

  private Subscriber<HttpResponse<String>> mapSubscriber(
      Subscriber<? super ResponseHandle> subscriber) {
    return new Subscriber<>() {
      @Override public void onSubscribe(Subscription s) {
        subscriber.onSubscribe(s);
      }
      @Override public void onNext(HttpResponse<String> response) {
        subscriber.onNext(new ResponseHandle(response));
      }
      @Override public void onError(Throwable t) {
        subscriber.onError(t);
      }
      @Override public void onComplete() {
        subscriber.onComplete();
      }
    };
  }

  private MockResponse mockResponseWithPush(int pushedCount) {
    var response = new MockResponse().setBody(BODY);
    for (int i = 0; i < pushedCount; i++) {
      var pushedResponse = new MockResponse().setBody(BODY);
      var pushPromise = new PushPromise(
          "GET", "/push" + i, Headers.of(":scheme", "https"), pushedResponse);
      response.withPush(pushPromise);
    }
    return response;
  }

  /** Implements Object::equals. */
  static final class ResponseHandle {

    private final HttpResponse<String> response;

    ResponseHandle(HttpResponse<String> response) {
      this.response = response;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof ResponseHandle)) {
        return false;
      }
      HttpResponse<String> other = ((ResponseHandle) obj).response;
      return response.request().equals(other.request())
          && response.body().equals(other.body());
    }
  }
}
