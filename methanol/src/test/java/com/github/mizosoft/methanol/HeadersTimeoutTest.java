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

package com.github.mizosoft.methanol;

import static com.github.mizosoft.methanol.MutableRequest.GET;
import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.testing.ImmutableResponseInfo;
import com.github.mizosoft.methanol.testing.MockClock;
import com.github.mizosoft.methanol.testing.MockDelayer;
import com.github.mizosoft.methanol.testing.RecordingHttpClient;
import com.github.mizosoft.methanol.testing.TestUtils;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HeadersTimeoutTest {
  private MockClock clock;
  private MockDelayer delayer;
  private RecordingHttpClient recordingClient;

  @BeforeEach
  void setUp() {
    clock = new MockClock();
    delayer = new MockDelayer(clock);
    recordingClient = new RecordingHttpClient();
  }

  @Test
  void headersBeforeTimeout() {
    var client =
        Methanol.newBuilder(recordingClient).headersTimeout(Duration.ofSeconds(1), delayer).build();
    var responseFuture = client.sendAsync(GET("https://example.com"), BodyHandlers.discarding());

    assertThat(delayer.taskCount()).isOne();

    // Make headers arrive before timeout
    var subscriber = recordingClient.lastCall().bodyHandler().apply(new ImmutableResponseInfo());
    assertThat(delayer.peekEarliestFuture()).isCancelled();
    assertThat(subscriber.getBody()).isNotCompletedExceptionally();

    // Executing the timeout task is NOOP
    clock.advanceSeconds(1);
    assertThat(delayer.taskCount()).isZero();

    recordingClient.lastCall().complete();
    assertThat(responseFuture).isCompleted();
  }

  @Test
  void headersAfterTimeout() {
    var client =
        Methanol.newBuilder(recordingClient).headersTimeout(Duration.ofSeconds(1), delayer).build();
    var responseFuture = client.sendAsync(GET("https://example.com"), BodyHandlers.discarding());

    assertThat(delayer.taskCount()).isOne();

    // Make timeout evaluate before headers arrive
    clock.advanceSeconds(1);
    assertThat(responseFuture)
        .isCompletedExceptionally()
        .failsWithin(Duration.ofSeconds(TestUtils.TIMEOUT_SECONDS))
        .withThrowableOfType(ExecutionException.class)
        .withCauseInstanceOf(HttpHeadersTimeoutException.class);
    assertThat(recordingClient.lastCall().future()).isCancelled();

    var subscriber = recordingClient.lastCall().bodyHandler().apply(new ImmutableResponseInfo());
    verifyThat(subscriber).failsWith(HttpHeadersTimeoutException.class);
  }
}
