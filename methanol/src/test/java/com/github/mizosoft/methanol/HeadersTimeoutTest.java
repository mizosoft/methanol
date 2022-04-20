package com.github.mizosoft.methanol;

import static com.github.mizosoft.methanol.MutableRequest.GET;
import static com.github.mizosoft.methanol.testutils.Verification.verifyThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.testing.MockDelayer;
import com.github.mizosoft.methanol.testutils.FakeResponseInfo;
import com.github.mizosoft.methanol.testutils.MockClock;
import com.github.mizosoft.methanol.testutils.RecordingHttpClient;
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
    var subscriber = recordingClient.latestCall().bodyHandler().apply(new FakeResponseInfo());
    assertThat(delayer.peekEarliestTaskFuture()).isCancelled();
    assertThat(subscriber.getBody()).isNotCompletedExceptionally();

    // Executing the timeout task is NOOP
    clock.advanceSeconds(1);
    assertThat(delayer.taskCount()).isZero();

    recordingClient.completeLatestCall();
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
        .failsWithin(Duration.ofSeconds(1))
        .withThrowableOfType(ExecutionException.class)
        .withCauseInstanceOf(HttpHeadersTimeoutException.class);
    assertThat(recordingClient.latestCall().future()).isCancelled();

    var subscriber = recordingClient.latestCall().bodyHandler().apply(new FakeResponseInfo());
    verifyThat(subscriber).failsWith(HttpHeadersTimeoutException.class);
  }
}
