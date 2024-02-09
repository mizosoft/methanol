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

package com.github.mizosoft.methanol.tck;

import static com.github.mizosoft.methanol.testing.TestUtils.localhostSslContext;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.MutableRequest;
import com.github.mizosoft.methanol.internal.extensions.HttpResponsePublisher;
import com.github.mizosoft.methanol.tck.HttpResponsePublisherTckTest.ResponseHandle;
import com.github.mizosoft.methanol.testing.ExecutorContext;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.concurrent.Flow.Publisher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.PushPromise;
import okhttp3.Headers;
import org.reactivestreams.tck.flow.FlowPublisherVerification;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Factory;

@Slow
public class HttpResponsePublisherTckTest extends FlowPublisherVerification<ResponseHandle> {
  private final ExecutorType executorType;

  private ExecutorContext executorContext;
  private HttpClient client;
  private MockWebServer server;

  @Factory(dataProvider = "provider")
  public HttpResponsePublisherTckTest(ExecutorType executorType) {
    super(TckUtils.newTestEnvironment(HttpResponsePublisherTckTest.class));
    this.executorType = executorType;
  }

  @BeforeMethod
  public void setMeUp() throws IOException {
    executorContext = new ExecutorContext();

    var sslContext = localhostSslContext();
    client = HttpClient.newBuilder().sslContext(sslContext).version(Version.HTTP_2).build();
    server = new MockWebServer();
    server.useHttps(sslContext.getSocketFactory(), false);
    server.start();
  }

  @AfterMethod
  public void tearMeDown() throws Exception {
    executorContext.close();
    server.shutdown();
  }

  @Override
  public Publisher<ResponseHandle> createFlowPublisher(long elements) {
    if (elements == 0) {
      throw new SkipException("can publish at least one response before completion");
    }

    server.enqueue(buildMockResponseWithPushPromises((int) elements - 1));
    return TckUtils.map(
        new HttpResponsePublisher<>(
            client,
            MutableRequest.GET(server.url("/").uri()),
            BodyHandlers.ofString(),
            __ -> BodyHandlers.ofString(),
            executorContext.createExecutor(executorType)),
        ResponseHandle::new);
  }

  @Override
  public Publisher<ResponseHandle> createFailedFlowPublisher() {
    // subscription.request(at least 1) must be called to try a request and fail.
    throw new SkipException("must be able to try at least one request before failing");
  }

  @Override
  public long maxElementsFromPublisher() {
    return TckUtils.MAX_PRECOMPUTED_ELEMENTS;
  }

  private static MockResponse buildMockResponseWithPushPromises(int pushPromiseCount) {
    var response = new MockResponse().setBody("A");
    for (int i = 0; i < pushPromiseCount; i++) {
      var pushedResponse = new MockResponse().setBody("B-" + i);
      var pushPromise =
          new PushPromise("GET", "/push" + i, Headers.of(":scheme", "https"), pushedResponse);
      response.withPush(pushPromise);
    }
    return response;
  }

  @DataProvider
  public static Object[][] provider() {
    return new Object[][] {{ExecutorType.CACHED_POOL}, {ExecutorType.SAME_THREAD}};
  }

  /**
   * Implements equivalence for an {@code HttpResponse} based on the request and the response body.
   */
  static final class ResponseHandle {
    private final HttpResponse<String> response;

    ResponseHandle(HttpResponse<String> response) {
      this.response = requireNonNull(response);
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof ResponseHandle)) {
        return false;
      }
      var other = ((ResponseHandle) obj).response;
      return response.request().equals(other.request()) && response.body().equals(other.body());
    }

    @Override
    public String toString() {
      return "ResponseHandle{" + response.toString() + "}";
    }
  }
}
