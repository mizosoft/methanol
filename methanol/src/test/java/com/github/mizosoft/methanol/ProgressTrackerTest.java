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

package com.github.mizosoft.methanol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.from;

import com.github.mizosoft.methanol.MultipartBodyPublisher.Part;
import com.github.mizosoft.methanol.ProgressTracker.Listener;
import com.github.mizosoft.methanol.ProgressTracker.MultipartListener;
import com.github.mizosoft.methanol.ProgressTracker.MultipartProgress;
import com.github.mizosoft.methanol.ProgressTracker.Progress;
import com.github.mizosoft.methanol.ProgressTracker.ProgressSnapshot;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.text.HeaderValueTokenizer;
import com.github.mizosoft.methanol.testing.MockClock;
import com.github.mizosoft.methanol.testing.SubmittablePublisher;
import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.TestSubscriber;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension.ExecutorParameterizedTest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProgressTrackerTest {
  private MockClock clock;

  @BeforeEach
  void setUp() {
    clock = new MockClock();
  }

  @Test
  void buildTracker() {
    Executor executor =
        r -> {
          throw new RejectedExecutionException();
        };
    var tracker =
        ProgressTracker.newBuilder()
            .bytesTransferredThreshold(1024)
            .timePassedThreshold(Duration.ofSeconds(1))
            .enclosedProgress(false)
            .executor(executor)
            .build();
    assertThat(tracker.bytesTransferredThreshold()).isEqualTo(1024);
    assertThat(tracker.timePassedThreshold()).hasValue(Duration.ofSeconds(1));
    assertThat(tracker.enclosedProgress()).isFalse();
    assertThat(tracker.executor()).hasValue(executor);
  }

  @Test
  void defaultTracker() {
    var tracker = ProgressTracker.create();
    assertThat(tracker.bytesTransferredThreshold()).isZero();
    assertThat(tracker.timePassedThreshold()).isEmpty();
    assertThat(tracker.enclosedProgress()).isTrue();
    assertThat(tracker.executor()).isEmpty();
  }

  @ExecutorParameterizedTest
  void downloadProgress(Executor executor) {
    var tracker = ProgressTracker.newBuilder().clock(clock).executor(executor).build();
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(FlowSupport.SYNC_EXECUTOR);
    var listener = new ProgressSubscriber();
    var subscriber = tracker.tracking(BodySubscribers.discarding(), listener, 10);
    upstream.subscribe(subscriber);

    // Receive enclosing 0% progress.
    verifyThat(listener.pollNext())
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(0)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(0)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    upstream.submit(buffers(2));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(2)
        .hasTotalBytesTransferred(2)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(0)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    clock.advanceSeconds(1);
    upstream.submit(buffers(2, 2));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(4)
        .hasTotalBytesTransferred(6)
        .hasTimePassedInSeconds(1)
        .hasTotalTimePassedInSeconds(1)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    clock.advanceSeconds(2);
    upstream.submit(buffers()); // Transfer nothing
    verifyThat(listener.pollNext())
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(6)
        .hasTimePassedInSeconds(2)
        .hasTotalTimePassedInSeconds(3)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    upstream.submit(buffers(1, 1, 2));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(4)
        .hasTotalBytesTransferred(10)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(3)
        .hasContentLength(10)
        .isDone();
    listener.assertNoProgress();

    upstream.close();
    listener.awaitCompletion();

    // No enclosing progress is received as 100% progress has already been signaled
    listener.assertNoProgress();
  }

  @ExecutorParameterizedTest
  void downloadProgressWithUnknownContentLength(Executor executor) {
    var tracker = ProgressTracker.newBuilder().clock(clock).executor(executor).build();
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(FlowSupport.SYNC_EXECUTOR);
    var listener = new ProgressSubscriber();
    var subscriber = tracker.tracking(BodySubscribers.discarding(), listener, -1);
    upstream.subscribe(subscriber);

    // Receive enclosing 0% progress
    verifyThat(listener.pollNext())
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(0)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(0)
        .hasContentLength(-1)
        .isNotDone();
    listener.assertNoProgress();

    upstream.submit(buffers(2));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(2)
        .hasTotalBytesTransferred(2)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(0)
        .hasContentLength(-1)
        .isNotDone();
    listener.assertNoProgress();

    clock.advanceSeconds(1);
    upstream.submit(buffers(2, 2));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(4)
        .hasTotalBytesTransferred(6)
        .hasTimePassedInSeconds(1)
        .hasTotalTimePassedInSeconds(1)
        .hasContentLength(-1)
        .isNotDone();
    listener.assertNoProgress();

    clock.advanceSeconds(2);
    upstream.submit(buffers()); // Transfer nothing
    verifyThat(listener.pollNext())
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(6)
        .hasTimePassedInSeconds(2)
        .hasTotalTimePassedInSeconds(3)
        .hasContentLength(-1)
        .isNotDone();
    listener.assertNoProgress();

    upstream.submit(buffers(1, 1, 2));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(4)
        .hasTotalBytesTransferred(10)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(3)
        .hasContentLength(-1)
        .isNotDone(); // The tracker doesn't yet know the body is completed
    listener.assertNoProgress();

    // Receive enclosing 100% progress
    clock.advanceSeconds(1);
    upstream.close();
    listener.awaitCompletion();
    verifyThat(listener.pollNext())
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(10)
        .hasTimePassedInSeconds(1)
        .hasTotalTimePassedInSeconds(4)
        .hasContentLength(-1)
        .isDone();
    listener.assertNoProgress();
  }

  @ExecutorParameterizedTest
  void downloadProgressWithUnknownContentLengthWithoutEnclosedProgress(Executor executor) {
    var tracker =
        ProgressTracker.newBuilder()
            .clock(clock)
            .executor(executor)
            .enclosedProgress(false)
            .build();
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(FlowSupport.SYNC_EXECUTOR);
    var listener = new ProgressSubscriber();
    var subscriber = tracker.tracking(BodySubscribers.discarding(), listener, -1);
    upstream.subscribe(subscriber);

    // No enclosing 0% progress
    listener.awaitSubscription();
    listener.assertNoProgress();

    upstream.submit(buffers(2));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(2)
        .hasTotalBytesTransferred(2)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(0)
        .hasContentLength(-1)
        .isNotDone();
    listener.assertNoProgress();

    clock.advanceSeconds(1);
    upstream.submit(buffers(2, 2));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(4)
        .hasTotalBytesTransferred(6)
        .hasTimePassedInSeconds(1)
        .hasTotalTimePassedInSeconds(1)
        .hasContentLength(-1)
        .isNotDone();
    listener.assertNoProgress();

    clock.advanceSeconds(2);
    upstream.submit(buffers()); // Transfer nothing
    verifyThat(listener.pollNext())
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(6)
        .hasTimePassedInSeconds(2)
        .hasTotalTimePassedInSeconds(3)
        .hasContentLength(-1)
        .isNotDone();
    listener.assertNoProgress();

    upstream.submit(buffers(1, 1, 2));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(4)
        .hasTotalBytesTransferred(10)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(3)
        .hasContentLength(-1)
        .isNotDone(); // The tracker doesn't yet know the body is completed
    listener.assertNoProgress();

    // No enclosing 100% progress
    clock.advanceSeconds(1);
    upstream.close();
    listener.awaitCompletion();
    listener.assertNoProgress();
  }

  @ExecutorParameterizedTest
  void downloadProgressWithByteThreshold(Executor executor) {
    var tracker =
        ProgressTracker.newBuilder()
            .clock(clock)
            .executor(executor)
            .bytesTransferredThreshold(2)
            .build();
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(FlowSupport.SYNC_EXECUTOR);
    var listener = new ProgressSubscriber();
    var subscriber = tracker.tracking(BodySubscribers.discarding(), listener, 10);
    upstream.subscribe(subscriber);

    // Receive enclosing 0% progress
    verifyThat(listener.pollNext())
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(0)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(0)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 1 byte (< threshold)
    upstream.submit(buffers(1));
    listener.assertNoProgress();

    // Pending progress: 2 bytes (>= to threshold)
    upstream.submit(buffers(1));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(2)
        .hasTotalBytesTransferred(2)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(0)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 1 byte (< threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffers(1));
    listener.assertNoProgress();

    // Pending progress: 5 bytes (>= threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffers(2, 2));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(5)
        .hasTotalBytesTransferred(7)
        .hasTimePassedInSeconds(2)
        .hasTotalTimePassedInSeconds(2)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 2 bytes (>= threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffers(2));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(2)
        .hasTotalBytesTransferred(9)
        .hasTimePassedInSeconds(1)
        .hasTotalTimePassedInSeconds(3)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 1 byte (< threshold)
    clock.advanceSeconds(2);
    upstream.submit(buffers(1));
    listener.assertNoProgress();

    // Pending progress: 1 byte (< threshold, but received in enclosing 100% progress)
    upstream.close();
    listener.awaitCompletion();
    verifyThat(listener.pollNext())
        .hasBytesTransferred(1)
        .hasTotalBytesTransferred(10)
        .hasTimePassedInSeconds(2)
        .hasTotalTimePassedInSeconds(5)
        .hasContentLength(10)
        .isDone();
    listener.assertNoProgress();
  }

  @ExecutorParameterizedTest
  void downloadProgressWithByteThresholdWithoutEnclosedProgress(Executor executor) {
    var tracker =
        ProgressTracker.newBuilder()
            .clock(clock)
            .executor(executor)
            .bytesTransferredThreshold(2)
            .enclosedProgress(false)
            .build();
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(FlowSupport.SYNC_EXECUTOR);
    var listener = new ProgressSubscriber();
    var subscriber = tracker.tracking(BodySubscribers.discarding(), listener, 10);
    upstream.subscribe(subscriber);

    // No enclosing 0% progress
    listener.awaitSubscription();
    listener.assertNoProgress();

    // Pending progress: 1 byte (< threshold)
    upstream.submit(buffers(1));
    listener.assertNoProgress();

    // Pending progress: 2 bytes (>= to threshold)
    upstream.submit(buffers(1));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(2)
        .hasTotalBytesTransferred(2)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(0)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 1 byte (< threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffers(1));
    listener.assertNoProgress();

    // Pending progress: 5 bytes (>= threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffers(2, 2));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(5)
        .hasTotalBytesTransferred(7)
        .hasTimePassedInSeconds(2)
        .hasTotalTimePassedInSeconds(2)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 0 bytes (< threshold)
    upstream.submit(buffers()); // Transfer nothing
    listener.assertNoProgress();

    // Pending progress: 2 bytes (>= threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffers(2));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(2)
        .hasTotalBytesTransferred(9)
        .hasTimePassedInSeconds(1)
        .hasTotalTimePassedInSeconds(3)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 1 byte (< threshold)
    clock.advanceSeconds(2);
    upstream.submit(buffers(1));
    listener.assertNoProgress();

    // Pending progress: 1 byte (< threshold)
    // No enclosing 100% progress
    upstream.close();
    listener.awaitCompletion();
    listener.assertNoProgress();
  }

  @ExecutorParameterizedTest
  void downloadProgressWithTimeThreshold(Executor executor) {
    var tracker =
        ProgressTracker.newBuilder()
            .clock(clock)
            .executor(executor)
            .timePassedThreshold(Duration.ofSeconds(2))
            .build();
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(FlowSupport.SYNC_EXECUTOR);
    var listener = new ProgressSubscriber();
    var subscriber = tracker.tracking(BodySubscribers.discarding(), listener, 10);
    upstream.subscribe(subscriber);

    // Receive enclosing 0% progress
    verifyThat(listener.pollNext())
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(0)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(0)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 1 second (< threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffers(1));
    listener.assertNoProgress();

    // Pending progress: 2 seconds (>= threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffers(2));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(3)
        .hasTotalBytesTransferred(3)
        .hasTimePassedInSeconds(2)
        .hasTotalTimePassedInSeconds(2)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 2 seconds (>= threshold)
    clock.advanceSeconds(2);
    upstream.submit(buffers()); // Transfer nothing
    verifyThat(listener.pollNext())
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(3)
        .hasTimePassedInSeconds(2)
        .hasTotalTimePassedInSeconds(4)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 0 seconds (< threshold)
    upstream.submit(buffers(4));
    listener.assertNoProgress();

    // Pending progress: 1 seconds (< threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffers(1));
    listener.assertNoProgress();

    // Pending progress: 3 seconds (>= threshold)
    clock.advanceSeconds(2);
    upstream.submit(buffers(1));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(6)
        .hasTotalBytesTransferred(9)
        .hasTimePassedInSeconds(3)
        .hasTotalTimePassedInSeconds(7)
        .hasContentLength(10)
        .isNotDone();

    // Pending progress: 1 second (< threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffers(1));
    listener.assertNoProgress();

    // Pending progress: 1 second (< threshold, but received in enclosing 100% progress)
    upstream.close();
    listener.awaitCompletion();
    verifyThat(listener.pollNext())
        .hasBytesTransferred(1)
        .hasTotalBytesTransferred(10)
        .hasTimePassedInSeconds(1)
        .hasTotalTimePassedInSeconds(8)
        .hasContentLength(10)
        .isDone();
  }

  @ExecutorParameterizedTest
  void downloadProgressWithTimeThresholdWithoutEnclosedProgress(Executor executor) {
    var tracker =
        ProgressTracker.newBuilder()
            .clock(clock)
            .executor(executor)
            .timePassedThreshold(Duration.ofSeconds(2))
            .enclosedProgress(false)
            .build();
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(FlowSupport.SYNC_EXECUTOR);
    var listener = new ProgressSubscriber();
    var subscriber = tracker.tracking(BodySubscribers.discarding(), listener, 10);
    upstream.subscribe(subscriber);

    // No enclosing 0% progress
    listener.awaitSubscription();
    listener.assertNoProgress();

    // Pending progress: 1 second (< threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffers(1));
    listener.assertNoProgress();

    // Pending progress: 2 seconds (>= threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffers(2));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(3)
        .hasTotalBytesTransferred(3)
        .hasTimePassedInSeconds(2)
        .hasTotalTimePassedInSeconds(2)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 2 seconds (>= threshold)
    clock.advanceSeconds(2);
    upstream.submit(buffers()); // Transfer nothing
    verifyThat(listener.pollNext())
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(3)
        .hasTimePassedInSeconds(2)
        .hasTotalTimePassedInSeconds(4)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 0 seconds (< threshold)
    upstream.submit(buffers(4));
    listener.assertNoProgress();

    // Pending progress: 1 seconds (< threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffers(1));
    listener.assertNoProgress();

    // Pending progress: 3 seconds (>= threshold)
    clock.advanceSeconds(2);
    upstream.submit(buffers(1));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(6)
        .hasTotalBytesTransferred(9)
        .hasTimePassedInSeconds(3)
        .hasTotalTimePassedInSeconds(7)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 1 second (< threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffers(1));
    listener.assertNoProgress();

    // Pending progress: 1 second (< threshold)
    // No enclosing 100% progress
    upstream.close();
    listener.awaitCompletion();
    listener.assertNoProgress();
  }

  @ExecutorParameterizedTest
  void downloadProgressWithError(Executor executor) {
    var tracker = ProgressTracker.newBuilder().clock(clock).executor(executor).build();
    var upstream = new SubmittablePublisher<List<ByteBuffer>>(FlowSupport.SYNC_EXECUTOR);
    var listener = new ProgressSubscriber();
    var downstream = new TestSubscriber<>();
    var subscriber = tracker.tracking(BodySubscribers.fromSubscriber(downstream), listener, -1);
    upstream.subscribe(subscriber);
    listener.awaitSubscription();
    upstream.firstSubscription().fireOrKeepAliveOnError(new TestException());
    assertThat(listener.awaitError()).isInstanceOf(TestException.class);
    assertThat(downstream.awaitError()).isInstanceOf(TestException.class);
  }

  @ExecutorParameterizedTest
  void uploadProgress(Executor executor) {
    var tracker = ProgressTracker.newBuilder().clock(clock).executor(executor).build();
    var upstream = new SubmittablePublisher<ByteBuffer>(FlowSupport.SYNC_EXECUTOR);
    var listener = new ProgressSubscriber();
    var publisher = tracker.tracking(BodyPublishers.fromPublisher(upstream, 10), listener);
    publisher.subscribe(new TestSubscriber<>());

    // Receive enclosing 0% progress
    verifyThat(listener.pollNext())
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(0)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(0)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    upstream.submit(buffer(2));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(2)
        .hasTotalBytesTransferred(2)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(0)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    clock.advanceSeconds(1);
    upstream.submit(buffer(4));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(4)
        .hasTotalBytesTransferred(6)
        .hasTimePassedInSeconds(1)
        .hasTotalTimePassedInSeconds(1)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    clock.advanceSeconds(2);
    upstream.submit(buffer(0)); // Transfer nothing
    verifyThat(listener.pollNext())
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(6)
        .hasTimePassedInSeconds(2)
        .hasTotalTimePassedInSeconds(3)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    upstream.submit(buffer(4));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(4)
        .hasTotalBytesTransferred(10)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(3)
        .hasContentLength(10)
        .isDone();
    listener.assertNoProgress();

    upstream.close();
    listener.awaitCompletion();

    // No enclosing progress is received as 100% progress has already been signaled
    listener.assertNoProgress();
  }

  @ExecutorParameterizedTest
  void uploadProgressWithUnknownContentLength(Executor executor) {
    var tracker = ProgressTracker.newBuilder().clock(clock).executor(executor).build();
    var upstream = new SubmittablePublisher<ByteBuffer>(FlowSupport.SYNC_EXECUTOR);
    var listener = new ProgressSubscriber();
    var publisher = tracker.tracking(BodyPublishers.fromPublisher(upstream), listener);
    publisher.subscribe(new TestSubscriber<>());

    // Receive enclosing 0% progress
    verifyThat(listener.pollNext())
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(0)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(0)
        .hasContentLength(-1)
        .isNotDone();
    listener.assertNoProgress();

    upstream.submit(buffer(2));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(2)
        .hasTotalBytesTransferred(2)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(0)
        .hasContentLength(-1)
        .isNotDone();
    listener.assertNoProgress();

    clock.advanceSeconds(1);
    upstream.submit(buffer(4));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(4)
        .hasTotalBytesTransferred(6)
        .hasTimePassedInSeconds(1)
        .hasTotalTimePassedInSeconds(1)
        .hasContentLength(-1)
        .isNotDone();
    listener.assertNoProgress();

    clock.advanceSeconds(2);
    upstream.submit(buffer(0)); // Transfer nothing
    verifyThat(listener.pollNext())
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(6)
        .hasTimePassedInSeconds(2)
        .hasTotalTimePassedInSeconds(3)
        .hasContentLength(-1)
        .isNotDone();
    listener.assertNoProgress();

    upstream.submit(buffer(4));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(4)
        .hasTotalBytesTransferred(10)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(3)
        .hasContentLength(-1)
        .isNotDone(); // The tracker doesn't yet know the body is completed
    listener.assertNoProgress();

    // Receive enclosing 100% progress
    clock.advanceSeconds(1);
    upstream.close();
    listener.awaitCompletion();
    verifyThat(listener.pollNext())
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(10)
        .hasTimePassedInSeconds(1)
        .hasTotalTimePassedInSeconds(4)
        .hasContentLength(-1)
        .isDone();
    listener.assertNoProgress();
  }

  @ExecutorParameterizedTest
  void uploadProgressWithUnknownContentLengthWithoutEnclosedProgress(Executor executor) {
    var tracker =
        ProgressTracker.newBuilder()
            .clock(clock)
            .executor(executor)
            .enclosedProgress(false)
            .build();
    var upstream = new SubmittablePublisher<ByteBuffer>(FlowSupport.SYNC_EXECUTOR);
    var listener = new ProgressSubscriber();
    var publisher = tracker.tracking(BodyPublishers.fromPublisher(upstream), listener);
    publisher.subscribe(new TestSubscriber<>());

    // No enclosing 0% progress
    listener.awaitSubscription();
    listener.assertNoProgress();

    upstream.submit(buffer(2));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(2)
        .hasTotalBytesTransferred(2)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(0)
        .hasContentLength(-1)
        .isNotDone();
    listener.assertNoProgress();

    clock.advanceSeconds(1);
    upstream.submit(buffer(4));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(4)
        .hasTotalBytesTransferred(6)
        .hasTimePassedInSeconds(1)
        .hasTotalTimePassedInSeconds(1)
        .hasContentLength(-1)
        .isNotDone();
    listener.assertNoProgress();

    clock.advanceSeconds(2);
    upstream.submit(buffer(0)); // Transfer nothing
    verifyThat(listener.pollNext())
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(6)
        .hasTimePassedInSeconds(2)
        .hasTotalTimePassedInSeconds(3)
        .hasContentLength(-1)
        .isNotDone();
    listener.assertNoProgress();

    upstream.submit(buffer(4));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(4)
        .hasTotalBytesTransferred(10)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(3)
        .hasContentLength(-1)
        .isNotDone(); // The tracker doesn't yet know the body is completed
    listener.assertNoProgress();

    // No enclosing 100% progress
    clock.advanceSeconds(1);
    upstream.close();
    listener.awaitCompletion();
    listener.assertNoProgress();
  }

  @ExecutorParameterizedTest
  void uploadProgressWithByteThreshold(Executor executor) {
    var tracker =
        ProgressTracker.newBuilder()
            .clock(clock)
            .executor(executor)
            .bytesTransferredThreshold(2)
            .build();
    var upstream = new SubmittablePublisher<ByteBuffer>(FlowSupport.SYNC_EXECUTOR);
    var listener = new ProgressSubscriber();
    var publisher = tracker.tracking(BodyPublishers.fromPublisher(upstream, 10), listener);
    publisher.subscribe(new TestSubscriber<>());

    // Receive enclosing 0% progress
    verifyThat(listener.pollNext())
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(0)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(0)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 1 byte (< threshold)
    upstream.submit(buffer(1));
    listener.assertNoProgress();

    // Pending progress: 2 bytes (>= to threshold)
    upstream.submit(buffer(1));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(2)
        .hasTotalBytesTransferred(2)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(0)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 1 byte (< threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffer(1));
    listener.assertNoProgress();

    // Pending progress: 5 bytes (>= threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffer(4));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(5)
        .hasTotalBytesTransferred(7)
        .hasTimePassedInSeconds(2)
        .hasTotalTimePassedInSeconds(2)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 2 bytes (>= threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffer(2));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(2)
        .hasTotalBytesTransferred(9)
        .hasTimePassedInSeconds(1)
        .hasTotalTimePassedInSeconds(3)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 1 byte (< threshold)
    clock.advanceSeconds(2);
    upstream.submit(buffer(1));
    listener.assertNoProgress();

    // Pending progress: 1 byte (< threshold, but received in enclosing 100% progress)
    upstream.close();
    listener.awaitCompletion();
    verifyThat(listener.pollNext())
        .hasBytesTransferred(1)
        .hasTotalBytesTransferred(10)
        .hasTimePassedInSeconds(2)
        .hasTotalTimePassedInSeconds(5)
        .hasContentLength(10)
        .isDone();
    listener.assertNoProgress();
  }

  @ExecutorParameterizedTest
  void uploadProgressWithByteThresholdWithoutEnclosedProgress(Executor executor) {
    var tracker =
        ProgressTracker.newBuilder()
            .clock(clock)
            .executor(executor)
            .bytesTransferredThreshold(2)
            .enclosedProgress(false)
            .build();
    var upstream = new SubmittablePublisher<ByteBuffer>(FlowSupport.SYNC_EXECUTOR);
    var listener = new ProgressSubscriber();
    var publisher = tracker.tracking(BodyPublishers.fromPublisher(upstream, 10), listener);
    publisher.subscribe(new TestSubscriber<>());

    // No enclosing 0% progress
    listener.awaitSubscription();
    listener.assertNoProgress();

    // Pending progress: 1 byte (< threshold)
    upstream.submit(buffer(1));
    listener.assertNoProgress();

    // Pending progress: 2 bytes (>= to threshold)
    upstream.submit(buffer(1));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(2)
        .hasTotalBytesTransferred(2)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(0)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 1 byte (< threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffer(1));
    listener.assertNoProgress();

    // Pending progress: 5 bytes (>= threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffer(4));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(5)
        .hasTotalBytesTransferred(7)
        .hasTimePassedInSeconds(2)
        .hasTotalTimePassedInSeconds(2)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 0 bytes (< threshold)
    upstream.submit(buffer(0)); // Transfer nothing
    listener.assertNoProgress();

    // Pending progress: 2 bytes (>= threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffer(2));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(2)
        .hasTotalBytesTransferred(9)
        .hasTimePassedInSeconds(1)
        .hasTotalTimePassedInSeconds(3)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 1 byte (< threshold)
    clock.advanceSeconds(2);
    upstream.submit(buffer(1));
    listener.assertNoProgress();

    // Pending progress: 1 byte (< threshold)
    // No enclosing 100% progress
    upstream.close();
    listener.awaitCompletion();
    listener.assertNoProgress();
  }

  @ExecutorParameterizedTest
  void uploadProgressWithTimeThreshold(Executor executor) {
    var tracker =
        ProgressTracker.newBuilder()
            .clock(clock)
            .executor(executor)
            .timePassedThreshold(Duration.ofSeconds(2))
            .build();
    var upstream = new SubmittablePublisher<ByteBuffer>(FlowSupport.SYNC_EXECUTOR);
    var listener = new ProgressSubscriber();
    var publisher = tracker.tracking(BodyPublishers.fromPublisher(upstream, 10), listener);
    publisher.subscribe(new TestSubscriber<>());

    // Receive enclosing 0% progress
    verifyThat(listener.pollNext())
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(0)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(0)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 1 second (< threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffer(1));
    listener.assertNoProgress();

    // Pending progress: 2 seconds (>= threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffer(2));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(3)
        .hasTotalBytesTransferred(3)
        .hasTimePassedInSeconds(2)
        .hasTotalTimePassedInSeconds(2)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 2 seconds (>= threshold)
    clock.advanceSeconds(2);
    upstream.submit(buffer(0)); // Transfer nothing
    verifyThat(listener.pollNext())
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(3)
        .hasTimePassedInSeconds(2)
        .hasTotalTimePassedInSeconds(4)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 0 seconds (< threshold)
    upstream.submit(buffer(4));
    listener.assertNoProgress();

    // Pending progress: 1 seconds (< threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffer(1));
    listener.assertNoProgress();

    // Pending progress: 3 seconds (>= threshold)
    clock.advanceSeconds(2);
    upstream.submit(buffer(1));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(6)
        .hasTotalBytesTransferred(9)
        .hasTimePassedInSeconds(3)
        .hasTotalTimePassedInSeconds(7)
        .hasContentLength(10)
        .isNotDone();

    // Pending progress: 1 second (< threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffer(1));
    listener.assertNoProgress();

    // Pending progress: 1 second (< threshold, but received in enclosing 100% progress)
    upstream.close();
    listener.awaitCompletion();
    verifyThat(listener.pollNext())
        .hasBytesTransferred(1)
        .hasTotalBytesTransferred(10)
        .hasTimePassedInSeconds(1)
        .hasTotalTimePassedInSeconds(8)
        .hasContentLength(10)
        .isDone();
  }

  @ExecutorParameterizedTest
  void uploadProgressWithTimeThresholdWithoutEnclosedProgress(Executor executor) {
    var tracker =
        ProgressTracker.newBuilder()
            .clock(clock)
            .executor(executor)
            .timePassedThreshold(Duration.ofSeconds(2))
            .enclosedProgress(false)
            .build();
    var upstream = new SubmittablePublisher<ByteBuffer>(FlowSupport.SYNC_EXECUTOR);
    var listener = new ProgressSubscriber();
    var publisher = tracker.tracking(BodyPublishers.fromPublisher(upstream, 10), listener);
    publisher.subscribe(new TestSubscriber<>());

    // No enclosing 0% progress
    listener.awaitSubscription();
    listener.assertNoProgress();

    // Pending progress: 1 second (< threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffer(1));
    listener.assertNoProgress();

    // Pending progress: 2 seconds (>= threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffer(2));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(3)
        .hasTotalBytesTransferred(3)
        .hasTimePassedInSeconds(2)
        .hasTotalTimePassedInSeconds(2)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 2 seconds (>= threshold)
    clock.advanceSeconds(2);
    upstream.submit(buffer(0)); // Transfer nothing
    verifyThat(listener.pollNext())
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(3)
        .hasTimePassedInSeconds(2)
        .hasTotalTimePassedInSeconds(4)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 0 seconds (< threshold)
    upstream.submit(buffer(4));
    listener.assertNoProgress();

    // Pending progress: 1 seconds (< threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffer(1));
    listener.assertNoProgress();

    // Pending progress: 3 seconds (>= threshold)
    clock.advanceSeconds(2);
    upstream.submit(buffer(1));
    verifyThat(listener.pollNext())
        .hasBytesTransferred(6)
        .hasTotalBytesTransferred(9)
        .hasTimePassedInSeconds(3)
        .hasTotalTimePassedInSeconds(7)
        .hasContentLength(10)
        .isNotDone();
    listener.assertNoProgress();

    // Pending progress: 1 second (< threshold)
    clock.advanceSeconds(1);
    upstream.submit(buffer(1));
    listener.assertNoProgress();

    // Pending progress: 1 second (< threshold)
    // No enclosing 100% progress
    upstream.close();
    listener.awaitCompletion();
    listener.assertNoProgress();
  }

  @ExecutorParameterizedTest
  void multipartUploadProgress(Executor executor) {
    var tracker = ProgressTracker.newBuilder().clock(clock).executor(executor).build();
    var firstUpstream = new SubmittablePublisher<ByteBuffer>(FlowSupport.SYNC_EXECUTOR);
    var secondUpstream = new SubmittablePublisher<ByteBuffer>(FlowSupport.SYNC_EXECUTOR);
    var multipartBody =
        MultipartBodyPublisher.newBuilder()
            .formPart(
                "part1", BodyPublishers.fromPublisher(firstUpstream, 5)) // Part with known length
            .formPart(
                "part2", BodyPublishers.fromPublisher(secondUpstream)) // Part with unknown length
            .build();
    var listener = new MultipartProgressSubscriber();
    var publisher = tracker.trackingMultipart(multipartBody, listener);
    var subscriber = new TestSubscriber<ByteBuffer>().autoRequest(0);
    publisher.subscribe(subscriber);

    // Receive enclosing 0% progress
    verifyThat(listener.pollNext())
        .partChanged(true)
        .hasPartWithName("part1")
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(0)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(0)
        .hasContentLength(-1)
        .isNotDone()
        .verifyPartProgress()
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(0)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(0)
        .hasContentLength(5)
        .isNotDone();
    listener.assertNoProgress();

    // Receive progress of first part's multipart metadata. This is not recorded in the progress
    // of the part itself.
    clock.advanceSeconds(1);
    subscriber.requestItems(1);
    int firstPartMetadataLength = subscriber.pollNext().remaining();
    verifyThat(listener.pollNext())
        .partChanged(true)
        .hasPartWithName("part1")
        .hasBytesTransferred(firstPartMetadataLength)
        .hasTotalBytesTransferred(firstPartMetadataLength)
        .hasTimePassedInSeconds(1)
        .hasTotalTimePassedInSeconds(1)
        .hasContentLength(-1)
        .isNotDone()
        .verifyPartProgress()
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(0)
        .hasTimePassedInSeconds(
            0) // 0 seconds has passed since this part started, not the multipart body
        .hasTotalTimePassedInSeconds(0)
        .hasContentLength(5)
        .isNotDone();
    listener.assertNoProgress();

    subscriber.requestItems(1);
    clock.advanceSeconds(1);
    firstUpstream.submit(buffer(5));
    subscriber.pollNext();
    verifyThat(listener.pollNext())
        .partChanged(false)
        .hasPartWithName("part1")
        .hasBytesTransferred(5)
        .hasTotalBytesTransferred(firstPartMetadataLength + 5)
        .hasTimePassedInSeconds(1)
        .hasTotalTimePassedInSeconds(2)
        .hasContentLength(-1)
        .isNotDone()
        .verifyPartProgress()
        .hasBytesTransferred(5)
        .hasTotalBytesTransferred(5)
        .hasTimePassedInSeconds(1)
        .hasTotalTimePassedInSeconds(1)
        .hasContentLength(5)
        .isDone();
    listener.assertNoProgress();

    // Receive progress of second part's multipart metadata. The metadata bytes are not recorded in
    // the progress of the part itself.
    subscriber.requestItems(1);
    firstUpstream.close();
    int secondPartMetadataLength = subscriber.pollNext().remaining();
    verifyThat(listener.pollNext())
        .partChanged(true)
        .hasPartWithName("part2")
        .hasBytesTransferred(secondPartMetadataLength)
        .hasTotalBytesTransferred(firstPartMetadataLength + 5 + secondPartMetadataLength)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(2)
        .hasContentLength(-1)
        .isNotDone()
        .verifyPartProgress()
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(0)
        .hasTimePassedInSeconds(
            0) // 0 seconds has passed since this part started, not the multipart body
        .hasTotalTimePassedInSeconds(0)
        .hasContentLength(-1)
        .isNotDone();
    listener.assertNoProgress();

    clock.advanceSeconds(1);
    subscriber.requestItems(1);
    secondUpstream.submit(buffer(3));
    subscriber.pollNext();
    verifyThat(listener.pollNext())
        .partChanged(false)
        .hasPartWithName("part2")
        .hasBytesTransferred(3)
        .hasTotalBytesTransferred(firstPartMetadataLength + 5 + secondPartMetadataLength + 3)
        .hasTimePassedInSeconds(1)
        .hasTotalTimePassedInSeconds(3)
        .hasContentLength(-1)
        .isNotDone()
        .verifyPartProgress()
        .hasBytesTransferred(3)
        .hasTotalBytesTransferred(3)
        .hasTimePassedInSeconds(1)
        .hasTotalTimePassedInSeconds(1)
        .hasContentLength(-1)
        .isNotDone();
    listener.assertNoProgress();

    clock.advanceSeconds(2);
    subscriber.requestItems(1);
    secondUpstream.submit(buffer(2));
    subscriber.pollNext();
    verifyThat(listener.pollNext())
        .partChanged(false)
        .hasPartWithName("part2")
        .hasBytesTransferred(2)
        .hasTotalBytesTransferred(firstPartMetadataLength + 5 + secondPartMetadataLength + 5)
        .hasTimePassedInSeconds(2)
        .hasTotalTimePassedInSeconds(5)
        .hasContentLength(-1)
        .isNotDone()
        .verifyPartProgress()
        .hasBytesTransferred(2)
        .hasTotalBytesTransferred(5)
        .hasTimePassedInSeconds(2)
        .hasTotalTimePassedInSeconds(3)
        .hasContentLength(-1)
        .isNotDone(); // The tracker doesn't yet know this part has completed
    listener.assertNoProgress();

    // Receive progress for the multipart closing boundary
    clock.advanceSeconds(1);
    subscriber.requestItems(1);
    secondUpstream.close();
    int closingBoundaryLength = subscriber.pollNext().remaining();
    verifyThat(listener.pollNext())
        .partChanged(false)
        .hasPartWithName("part2")
        .hasBytesTransferred(closingBoundaryLength)
        .hasTotalBytesTransferred(
            firstPartMetadataLength + 5 + secondPartMetadataLength + 5 + closingBoundaryLength)
        .hasTimePassedInSeconds(1)
        .hasTotalTimePassedInSeconds(6)
        .hasContentLength(-1)
        .isNotDone() // The tracker doesn't yet know the body has completed
        .verifyPartProgress()
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(5)
        .hasTimePassedInSeconds(0) // Last part's time progression stops
        .hasTotalTimePassedInSeconds(3)
        .hasContentLength(-1)
        .isNotDone();

    // Receive enclosing 100% progress
    verifyThat(listener.pollNext())
        .partChanged(false)
        .hasPartWithName("part2")
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(
            firstPartMetadataLength + 5 + secondPartMetadataLength + 5 + closingBoundaryLength)
        .hasTimePassedInSeconds(0)
        .hasTotalTimePassedInSeconds(6)
        .hasContentLength(-1)
        .isDone()
        .verifyPartProgress()
        .hasBytesTransferred(0)
        .hasTotalBytesTransferred(5)
        .hasTimePassedInSeconds(0) // Last part's time progression stops
        .hasTotalTimePassedInSeconds(3)
        .hasContentLength(-1);

    listener.awaitCompletion();
    listener.assertNoProgress();
  }

  @ExecutorParameterizedTest
  void uploadProgressWithError(Executor executor) {
    var tracker = ProgressTracker.newBuilder().clock(clock).executor(executor).build();
    var upstream = new SubmittablePublisher<ByteBuffer>(FlowSupport.SYNC_EXECUTOR);
    var listener = new ProgressSubscriber();
    var publisher = tracker.tracking(BodyPublishers.fromPublisher(upstream), listener);
    var subscriber = new TestSubscriber<>();
    publisher.subscribe(subscriber);
    listener.awaitSubscription();
    upstream.firstSubscription().fireOrKeepAliveOnError(new TestException());
    assertThat(listener.awaitError()).isInstanceOf(TestException.class);
    assertThat(subscriber.awaitError()).isInstanceOf(TestException.class);
  }

  @Test
  void mediaTypeIsPreserved() {
    var tracker = ProgressTracker.create();
    var upstream = MoreBodyPublishers.ofMediaType(BodyPublishers.noBody(), MediaType.TEXT_PLAIN);
    var publisher = tracker.tracking(upstream, progress -> {});
    assertThat(publisher)
        .isInstanceOf(MimeBodyPublisher.class)
        .returns(MediaType.TEXT_PLAIN, pub -> ((MimeBodyPublisher) pub).mediaType());
  }

  @Test
  void multipartMediaTypeIsPreserved() {
    var tracker = ProgressTracker.create();
    var upstream = MultipartBodyPublisher.newBuilder().textPart("a", "b").build();
    var publisher = tracker.trackingMultipart(upstream, progress -> {});
    assertThat(publisher).returns(publisher.mediaType(), from(MimeBodyPublisher::mediaType));
  }

  @Test
  void progressToString() {
    var progress =
        new ProgressSnapshot(
            1024, 8969, Duration.ofSeconds(1), Duration.ofSeconds(10), 10000, false);
    var progressWithUnknownLength =
        new ProgressSnapshot(1024, 8969, Duration.ofSeconds(1), Duration.ofSeconds(10), -1, false);
    assertThat(progress)
        .hasToString(
            "Progress[bytesTransferred=1024, totalBytesTransferred=8969, timePassed=PT1S, totalTimePassed=PT10S, contentLength=10000] 89.69%");
    assertThat(progressWithUnknownLength)
        .hasToString(
            "Progress[bytesTransferred=1024, totalBytesTransferred=8969, timePassed=PT1S, totalTimePassed=PT10S, contentLength=UNKNOWN]");
  }

  @Test
  void progressValue() {
    var progress1 =
        new ProgressSnapshot(0L, 0L, Duration.ofSeconds(1), Duration.ofSeconds(1), 0L, false);
    var progress8 =
        new ProgressSnapshot(1L, 8L, Duration.ofSeconds(1), Duration.ofSeconds(1), 10L, false);
    var progressNaN =
        new ProgressSnapshot(0L, 0L, Duration.ofSeconds(1), Duration.ofSeconds(1), -1, false);
    assertThat(progress1.value()).isEqualTo(1.d);
    assertThat(progress8.value()).isEqualTo(0.8d);
    assertThat(progressNaN.value()).isNaN();
  }

  private static ByteBuffer buffer(int size) {
    return ByteBuffer.allocate(size);
  }

  private static List<ByteBuffer> buffers(int... sizes) {
    return IntStream.of(sizes)
        .mapToObj(ByteBuffer::allocate)
        .collect(Collectors.toUnmodifiableList());
  }

  private static ProgressVerifier verifyThat(Progress progress) {
    return new ProgressVerifier(progress);
  }

  private static MultipartProgressVerifier verifyThat(MultipartProgress progress) {
    return new MultipartProgressVerifier(progress);
  }

  private abstract static class AbstractProgressVerifier<
      P extends Progress, VERIFIER extends AbstractProgressVerifier<P, VERIFIER>> {
    final P progress;

    AbstractProgressVerifier(P progress) {
      this.progress = progress;
    }

    abstract VERIFIER self();

    VERIFIER hasBytesTransferred(long bytesTransferred) {
      assertThat(progress).returns(bytesTransferred, from(Progress::bytesTransferred));
      return self();
    }

    VERIFIER hasTotalBytesTransferred(long totalBytesTransferred) {
      assertThat(progress).returns(totalBytesTransferred, from(Progress::totalBytesTransferred));
      return self();
    }

    VERIFIER hasTimePassedInSeconds(int seconds) {
      return hasTimePassed(Duration.ofSeconds(seconds));
    }

    VERIFIER hasTotalTimePassedInSeconds(int totalSeconds) {
      return hasTotalTimePassed(Duration.ofSeconds(totalSeconds));
    }

    VERIFIER hasTimePassed(Duration timePassed) {
      assertThat(progress).returns(timePassed, from(Progress::timePassed));
      return self();
    }

    VERIFIER hasTotalTimePassed(Duration totalTimePassed) {
      assertThat(progress).returns(totalTimePassed, from(Progress::totalTimePassed));
      return self();
    }

    VERIFIER hasContentLength(long contentLength) {
      assertThat(progress).returns(contentLength, from(Progress::contentLength));
      return self();
    }

    VERIFIER isDone() {
      assertThat(progress).returns(true, from(Progress::done));
      return self();
    }

    VERIFIER isNotDone() {
      assertThat(progress).returns(false, from(Progress::done));
      return self();
    }
  }

  private static final class ProgressVerifier
      extends AbstractProgressVerifier<Progress, ProgressVerifier> {
    ProgressVerifier(Progress progress) {
      super(progress);
    }

    @Override
    ProgressVerifier self() {
      return this;
    }
  }

  private static final class MultipartProgressVerifier
      extends AbstractProgressVerifier<MultipartProgress, MultipartProgressVerifier> {
    MultipartProgressVerifier(MultipartProgress progress) {
      super(progress);
    }

    @Override
    MultipartProgressVerifier self() {
      return this;
    }

    MultipartProgressVerifier partChanged(boolean partChanged) {
      assertThat(progress.partChanged()).isEqualTo(partChanged);
      return this;
    }

    MultipartProgressVerifier hasPartWithName(String name) {
      assertThat(name(progress.part())).isEqualTo(name);
      return this;
    }

    ProgressVerifier verifyPartProgress() {
      return new ProgressVerifier(progress.partProgress());
    }

    private static String name(Part part) {
      var contentDisposition = part.headers().firstValue("Content-Disposition");
      assertThat(contentDisposition).isNotEmpty();

      var tokenizer = new HeaderValueTokenizer(contentDisposition.orElseThrow());

      // Consume disposition type
      tokenizer.nextToken();
      tokenizer.consumeDelimiter(';');

      do {
        if ("name".equalsIgnoreCase(tokenizer.nextToken()) && tokenizer.consumeCharIfPresent('=')) {
          return tokenizer.nextTokenOrQuotedString();
        }
      } while (tokenizer.consumeDelimiter(';'));

      throw new AssertionError("absent name property");
    }
  }

  private static class AbstractProgressSubscriber<P extends Progress> extends TestSubscriber<P> {
    AbstractProgressSubscriber() {}

    void assertNoProgress() {
      assertThat(peekAvailable()).isEmpty();
    }
  }

  private static final class ProgressSubscriber extends AbstractProgressSubscriber<Progress>
      implements Listener {
    ProgressSubscriber() {}
  }

  private static final class MultipartProgressSubscriber
      extends AbstractProgressSubscriber<MultipartProgress> implements MultipartListener {
    MultipartProgressSubscriber() {}
  }
}
