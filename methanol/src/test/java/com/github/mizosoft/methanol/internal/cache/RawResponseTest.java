/*
 * Copyright (c) 2022 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.MutableRequest.GET;
import static com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType.FIXED_POOL;
import static java.nio.charset.StandardCharsets.UTF_16;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.mizosoft.methanol.TrackedResponse;
import com.github.mizosoft.methanol.ResponseBuilder;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorConfig;
import com.github.mizosoft.methanol.testutils.BuffListIterator;
import com.github.mizosoft.methanol.testutils.EmptyPublisher;
import com.github.mizosoft.methanol.testutils.FailingPublisher;
import com.github.mizosoft.methanol.testutils.TestException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient.Version;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.reactivestreams.FlowAdapters;
import org.reactivestreams.example.unicast.AsyncIterablePublisher;

@ExtendWith(ExecutorExtension.class)
class RawResponseTest {
  final TrackedResponse<?> responseTemplate =
      new ResponseBuilder<>()
          .uri(URI.create("https://example.com"))
          .request(GET("https://example.com"))
          .statusCode(200)
          .version(Version.HTTP_1_1)
          .timeRequestSent(Instant.ofEpochMilli(0))
          .timeResponseReceived(Instant.ofEpochMilli(1))
          .buildTrackedResponse();

  @Test
  @ExecutorConfig(FIXED_POOL)
  void handleAsync(Executor threadPool) {
    var response = ResponseBuilder.newBuilder(responseTemplate)
        .body(strPublisher("Indiana Jones", UTF_8, threadPool))
        .buildTrackedResponse();
    var rawResponse = NetworkResponse.from(response);
    assertEqualResponses(response, rawResponse.get());

    var handledResponse = rawResponse.handleAsync(BodyHandlers.ofString(), threadPool).join();
    assertEqualResponses(response, handledResponse);
    assertEquals("Indiana Jones", handledResponse.body());
  }

  @Test
  @ExecutorConfig(FIXED_POOL)
  void handleAsyncWithError(Executor threadPool) {
    var rawResponse = failingWith(TestException::new);
    var handledResponseFuture = rawResponse.handleAsync(BodyHandlers.ofString(), threadPool);
    var ex = assertThrows(CompletionException.class, handledResponseFuture::join);
    assertThrows(TestException.class, () -> { throw ex.getCause(); });
  }

  @Test
  @ExecutorConfig(FIXED_POOL)
  void handleSync(Executor threadPool) throws IOException, InterruptedException {
    var response = ResponseBuilder.newBuilder(responseTemplate)
        .header("Content-Type", "text/plain; charset=UTF-16")
        .body(strPublisher("Hans Solo", UTF_16, threadPool))
        .buildTrackedResponse();
    var rawResponse = NetworkResponse.from(response);
    assertEqualResponses(response, rawResponse.get());

    var handledResponse = rawResponse.handle(BodyHandlers.ofString());
    assertEqualResponses(response, handledResponse);
    assertEquals("Hans Solo", handledResponse.body());
  }

  @Test
  void with() {
    var response = ResponseBuilder.newBuilder(responseTemplate)
        .body((Publisher<List<ByteBuffer>>) EmptyPublisher.<List<ByteBuffer>>instance())
        .buildTrackedResponse();
    var rawResponse = NetworkResponse.from(response);
    var mutated =
        rawResponse.with(builder -> builder.statusCode(369).header("X-My-Header", "Hello!"));
    var expected = ResponseBuilder.newBuilder(response)
        .statusCode(369)
        .header("X-My-Header", "Hello!")
        .buildTrackedResponse();
    assertEqualResponses(expected, mutated.get());
  }

  @Test
  void handleSyncWithError() {
    var rawResponse = failingWith(TestException::new);
    assertThrows(TestException.class, () -> rawResponse.handle(BodyHandlers.ofString()));
  }

  /** Test for how async exceptions are rethrown. */
  @Test
  void handleSyncExceptionRethrowing() {
    assertRethrown(
        TestException2Arg.class, () -> new TestException2Arg("ops!", new TestException()));
    assertRethrown(TestExceptionStringArg.class, () -> new TestExceptionStringArg("ops!"));
    assertRethrown(
        TestExceptionThrowableArg.class, () -> new TestExceptionThrowableArg(new TestException()));
    assertRethrown(TestException.class, TestException::new);
    assertRethrown(
        IOException.class, () -> new TestExceptionIntArg(-1)); // No appropriate constructor
    assertRethrown(
        IOException.class,
        NonIOCheckedTestException::new); // Not an IOException or InterruptedException or unchecked
    assertRethrown(IOException.class, IOException::new);
    assertRethrown(InterruptedException.class, InterruptedException::new);
  }

  private <X extends Throwable> void assertRethrown(
      Class<X> expectedType, Supplier<Throwable> failureSupplier) {
    var response = failingWith(failureSupplier);
    assertThrows(expectedType, () -> response.handle(BodyHandlers.ofString()));
  }

  private RawResponse failingWith(Supplier<Throwable> supplier) {
    return NetworkResponse.from(
        ResponseBuilder.newBuilder(responseTemplate)
            .body((Publisher<List<ByteBuffer>>) new FailingPublisher<List<ByteBuffer>>(supplier))
            .buildTrackedResponse());
  }

  private static Publisher<List<ByteBuffer>> strPublisher(
      String str, Charset charset, Executor executor) {
    return FlowAdapters.toFlowPublisher(
        new AsyncIterablePublisher<>(
            () -> new BuffListIterator(charset.encode(str), 10, 1), executor));
  }

  private static void assertEqualResponses(TrackedResponse<?> expected, TrackedResponse<?> actual) {
    assertEquals(expected.uri(), actual.uri());
    assertEquals(expected.statusCode(), actual.statusCode());
    assertEquals(expected.headers(), actual.headers());
    assertEquals(expected.version(), actual.version());
    assertEquals(expected.timeRequestSent(), actual.timeRequestSent());
    assertEquals(expected.timeResponseReceived(), actual.timeResponseReceived());
    assertEquals(expected.sslSession().isPresent(), actual.sslSession().isPresent());
    if (expected.sslSession().isPresent()) {
      assertEquals(expected.sslSession().get(), actual.sslSession().get());
    }
  }

  public static class TestException2Arg extends RuntimeException {
    public TestException2Arg(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static class TestExceptionStringArg extends Error {
    public TestExceptionStringArg(String message) {
      super(message);
    }
  }

  public static class TestExceptionThrowableArg extends IOException {
    public TestExceptionThrowableArg(Throwable cause) {
      super(cause);
    }
  }

  public static class TestExceptionIntArg extends IOException {
    public TestExceptionIntArg(int someImportantValue) {}
  }

  public static class NonIOCheckedTestException extends Exception {}
}
