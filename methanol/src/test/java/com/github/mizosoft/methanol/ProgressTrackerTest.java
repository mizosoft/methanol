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

package com.github.mizosoft.methanol;

import static com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType.FIXED_POOL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.mizosoft.methanol.ProgressTracker.Builder;
import com.github.mizosoft.methanol.ProgressTracker.ImmutableProgress;
import com.github.mizosoft.methanol.ProgressTracker.Listener;
import com.github.mizosoft.methanol.ProgressTracker.MultipartListener;
import com.github.mizosoft.methanol.ProgressTracker.MultipartProgress;
import com.github.mizosoft.methanol.ProgressTracker.Progress;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorConfig;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorParameterizedTest;
import com.github.mizosoft.methanol.testutils.BodyCollector;
import com.github.mizosoft.methanol.testutils.BuffIterator;
import com.github.mizosoft.methanol.testutils.BuffListIterator;
import com.github.mizosoft.methanol.testutils.FailedPublisher;
import com.github.mizosoft.methanol.testutils.TestException;
import com.github.mizosoft.methanol.testutils.TestSubscriber;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.reactivestreams.FlowAdapters;
import org.reactivestreams.example.unicast.AsyncIterablePublisher;

@ExtendWith(ExecutorExtension.class)
class ProgressTrackerTest {

  // Virtual tick between each onXXXX method
  private static final Duration virtualTick = Duration.ofSeconds(1);

  private Executor upstreamExecutor;

  @BeforeEach
  @ExecutorConfig(FIXED_POOL)
  void setupUpstreamExecutor(Executor executor) {
    upstreamExecutor = executor;
  }

  @Test
  void buildTracker() {
    var bytesThreshold = 1024;
    var timeThreshold = Duration.ofSeconds(1);
    Executor executor = r -> { throw new RejectedExecutionException(); };
    var tracker = ProgressTracker.newBuilder()
        .bytesTransferredThreshold(bytesThreshold)
        .timePassedThreshold(timeThreshold)
        .enclosedProgress(false)
        .executor(executor)
        .build();
    assertEquals(bytesThreshold, tracker.bytesTransferredThreshold());
    assertEquals(Optional.of(timeThreshold), tracker.timePassedThreshold());
    assertFalse(tracker.enclosedProgress());
    assertEquals(Optional.of(executor), tracker.executor());
  }

  @Test
  void defaultTracker() {
    var tracker = ProgressTracker.create();
    assertEquals(0, tracker.bytesTransferredThreshold());
    assertEquals(Optional.empty(), tracker.timePassedThreshold());
    assertTrue(tracker.enclosedProgress());
    assertEquals(Optional.empty(), tracker.executor());
  }

  @Test
  void progressToString() {
    var progress = new ImmutableProgress(
        1024,
        8969,
        10000,
        Duration.ofSeconds(1),
        Duration.ofSeconds(10),
        false);
    var progressUnknownLength = new ImmutableProgress(
        1024,
        8969,
        -1,
        Duration.ofSeconds(1),
        Duration.ofSeconds(10),
        false);
    assertEquals(
        "Progress[bytes=1024, totalBytes=8969, time=PT1S, totalTime=PT10S, contentLength=10000] 89.69%",
        progress.toString());
    assertEquals(
        "Progress[bytes=1024, totalBytes=8969, time=PT1S, totalTime=PT10S, contentLength=UNKNOWN]",
        progressUnknownLength.toString());
  }

  @Test
  void progressValue() {
    var duration = Duration.ofSeconds(1);
    var progress1 = new ImmutableProgress(0L, 0L, 0L, duration, duration, false);
    var progress8 = new ImmutableProgress(1L, 8L, 10L, duration, duration, false);
    var progressNaN = new ImmutableProgress(0L, 0L, -1, duration, duration, false);
    assertEquals(1.d, progress1.value());
    assertEquals(0.8d, progress8.value());
    assertTrue(Double.isNaN(progressNaN.value()));
  }

  @ExecutorParameterizedTest
  void trackUploadProgressNoThreshold(Executor executor) {
    int batchSize = 64;
    int count = 20;
    var tracker = withVirtualClock(executor).build();
    var listener = new TestListener();
    var trackedUpstream = tracker.tracking(bodyPublisher(batchSize, count), listener);

    var downstream = new TestSubscriber<ByteBuffer>();
    trackedUpstream.subscribe(downstream);
    assertBodyForwarded(downstream, batchSize, count);

    testProgressSequenceNoThreshold(listener, batchSize, count);
  }

  @ExecutorParameterizedTest
  void trackUploadProgressWithError(Executor executor) {
    var tracker = withExecutor(executor).build();
    var listener = new TestListener();
    var trackedUpstream = tracker.tracking(
        BodyPublishers.fromPublisher(new FailedPublisher<>(TestException::new)), listener);

    var downstream = new TestSubscriber<ByteBuffer>();
    upstreamExecutor.execute(
        () -> trackedUpstream.subscribe(downstream));
    downstream.awaitError();

    listener.awaitComplete();
    assertEquals(1, listener.errors);
  }

  @ExecutorParameterizedTest
  void trackMultipartUploadProgressNoThreshold(Executor executor) {
    int batchSize = 64;
    int[] partCounts = {4, 2, 1};
    int count = IntStream.of(partCounts).sum();
    var tracker = withVirtualClock(executor).build();
    var listener = new TestMultipartListener();
    var builder = MultipartBodyPublisher.newBuilder();
    for (int i = 0; i < partCounts.length; i++) {
      builder.formPart("part_" + i, bodyPublisher(batchSize, partCounts[i]));
    }
    var multipartBody = builder.build();
    var trackedUpstream = tracker.trackingMultipart(multipartBody, listener);

    trackedUpstream.subscribe(new TestSubscriber<>());

    listener.awaitComplete();
    // enclosed progress receives additional 0%
    // 100% will already be received from onNext as there is no lastly missed progress
    // also each part will account for an additional progress event for each headers
    // and finally 1 for the ending boundary
    int signalCount = count + 1 + partCounts.length + 1;
    assertEquals(signalCount, listener.nexts);

    // 0% progress
    assertProgress(
        listener.items.remove(),
        0L,
        0L,
        multipartBody.contentLength(),
        Duration.ZERO,
        Duration.ZERO,
        false);

    for (int partIndex = 0; partIndex < partCounts.length; partIndex++) {
      // for each part we get: part headers batch + batches representing part's body
      int progressCountForPart = partCounts[partIndex] + 1;
      for (int i = 0; i < progressCountForPart; i++) {
        var progress = listener.items.remove();
        assertEquals(multipartBody.parts().get(partIndex), progress.part());
        assertEquals(i == 0, progress.partChanged(), i + ", " + partIndex);

        long length = progress.part().bodyPublisher().contentLength();
        assertProgress(
            progress.partProgress(),
            i > 0 ? batchSize : 0, // current
            i * batchSize, // total
            length,
            i > 0 ? virtualTick : Duration.ZERO, // current
            virtualTick.multipliedBy(i), // total
            i * batchSize == length);
      }
    }

    // end boundary
    assertProgress(
        listener.items.remove(),
        -1, // ¯\_(ツ)_/¯
        multipartBody.contentLength(),
        multipartBody.contentLength(),
        virtualTick,
        virtualTick.multipliedBy(signalCount - 1),
        true);

    assertTrue(listener.items.isEmpty());
  }

  @ExecutorParameterizedTest
  void trackUploadProgressWithByteThreshold(Executor executor) {
    int batchSize = 64;
    int count = 20;
    for (int scale = 1; scale <= count; scale++) {
      int finalScale = scale;
      testUploadWithThreshold(
          batchSize,
          count,
          scale,
          b -> b.bytesTransferredThreshold(batchSize * finalScale),
          executor);
    }
  }

  @ExecutorParameterizedTest
  void trackUploadProgressWithTimeThreshold(Executor executor) {
    int batchSize = 64;
    int count = 20;
    for (int scale = 1; scale <= count; scale++) {
      int finalScale = scale;
      testUploadWithThreshold(
          batchSize,
          count,
          scale,
          b -> b.timePassedThreshold(virtualTick.multipliedBy(finalScale)),
          executor);
    }
  }

  @ExecutorParameterizedTest
  void trackDownloadProgressNoThresholds(Executor executor) {
    int batchSize = 64;
    int count = 20;
    int length = batchSize * count;
    var tracker = withVirtualClock(executor).build();
    var listener = new TestListener();
    var downstream = new TestSubscriber<List<ByteBuffer>>();
    var trackedDownstream = tracker.tracking(
        BodySubscribers.fromSubscriber(downstream, d -> countBytes(d.items)), listener, length);

    var publisher = listPublisher(batchSize, count);
    publisher.subscribe(trackedDownstream);
    downstream.awaitComplete();
    assertEquals(count, downstream.nexts);
    assertEquals(length, trackedDownstream.getBody().toCompletableFuture().join());

    testProgressSequenceNoThreshold(listener, batchSize, count);
  }

  @ExecutorParameterizedTest
  void trackDownloadProgressWithError(Executor executor) {
    var tracker = withExecutor(executor).build();
    var listener = new TestListener();
    var downstream = new TestSubscriber<List<ByteBuffer>>();
    var trackedDownstream = tracker.tracking(
        BodySubscribers.fromSubscriber(downstream), listener, -1);

    var publisher = new FailedPublisher<List<ByteBuffer>>(TestException::new);
    upstreamExecutor.execute(() -> publisher.subscribe(trackedDownstream));
    downstream.awaitError();

    listener.awaitComplete();
    assertEquals(1, listener.errors);
  }

  @ExecutorParameterizedTest
  void trackDownloadProgressWithByteThreshold(Executor executor) {
    int batchSize = 64;
    int count = 20;
    for (int scale = 1; scale <= count; scale++) {
      int finalScale = scale;
      testDownloadWithThreshold(
          batchSize,
          count,
          scale,
          b -> b.bytesTransferredThreshold(batchSize * finalScale),
          executor);
    }
  }

  @ExecutorParameterizedTest
  void trackDownloadProgressWithTimeThreshold(Executor executor) {
    int batchSize = 64;
    int count = 20;
    for (int scale = 1; scale <= count; scale++) {
      int finalScale = scale;
      testDownloadWithThreshold(
          batchSize,
          count,
          scale,
          b -> b.timePassedThreshold(virtualTick.multipliedBy(finalScale)),
          executor);
    }
  }

  private void testProgressSequenceNoThreshold(TestListener listener, int batchSize, int count) {
    listener.awaitComplete();
    // enclosed progress receives additional 0%
    // 100% will already be received from onNext as there is no lastly missed progress
    assertEquals(count + 1, listener.nexts);

    int length = batchSize * count;
    for (int i = 0; i < count + 1; i++) {
      listener.assertNext(
          i > 0 ? batchSize : 0, // current
          i * batchSize, // total
          length,
          i > 0 ? virtualTick : Duration.ZERO, // current
          virtualTick.multipliedBy(i), // total
          i * batchSize == length);
    }
  }

  /*
  For easier testing and predictability of trackers with thresholds, thresholds are scaled with an
  integer from actual signal difference (batchSize for byte count and virtualTick for time passed).
   */

  private void testUploadWithThreshold(
      int batchSize,
      int count,
      int thresholdScale,
      Consumer<Builder> thresholdApplier,
      Executor executor) {
    var builder = withVirtualClock(executor);
    thresholdApplier.accept(builder);
    var tracker = builder.build();
    var listener = new TestListener();
    var trackedUpstream = tracker.tracking(bodyPublisher(batchSize, count), listener);

    var downstream = new TestSubscriber<ByteBuffer>();
    trackedUpstream.subscribe(downstream);
    assertBodyForwarded(downstream, batchSize, count);

    int lastlyMissed = count % thresholdScale; // missed progresses before onComplete due to threshold
    listener.awaitComplete();
    // 1 (0%) + count / thresholdScale (not missed) + 1 (100% from onComplete if any last progress is missed)
    assertEquals(1 + (count / thresholdScale) + (lastlyMissed > 0 ? 1 : 0), listener.nexts);

    testProgressSequenceWithThreshold(listener, batchSize, count, thresholdScale);
  }

  private void testDownloadWithThreshold(
      int batchSize,
      int count,
      int thresholdScale,
      Consumer<Builder> thresholdApplier,
      Executor executor) {
    var builder = withVirtualClock(executor);
    thresholdApplier.accept(builder);
    var tracker = builder.build();
    var listener = new TestListener();
    var downstream = new TestSubscriber<List<ByteBuffer>>();
    int length = batchSize * count;
    var trackedDownstream = tracker.tracking(
        BodySubscribers.fromSubscriber(downstream, d -> countBytes(d.items)), listener, length);

    var publisher = listPublisher(batchSize, count);
    publisher.subscribe(trackedDownstream);
    downstream.awaitComplete();
    assertEquals(count, downstream.nexts);
    assertEquals(length, trackedDownstream.getBody().toCompletableFuture().join());

    int lastlyMissed = count % thresholdScale; // missed progresses before onComplete due to threshold
    listener.awaitComplete();
    // 1 (0%) + count / thresholdScale (not missed) + 1 (100% from onComplete if any last progress is missed)
    assertEquals(1 + (count / thresholdScale) + (lastlyMissed > 0 ? 1 : 0), listener.nexts);

    testProgressSequenceWithThreshold(listener, batchSize, count, thresholdScale);
  }

  private void testProgressSequenceWithThreshold(
      TestListener listener,
      int batchSize,
      int count,
      int thresholdScale) {
    // e.g. if scale = 2
    // Progress[    count,     total,     time,    total]
    // Progress[       0L,        0L,     ZERO,     ZERO] (signalled)
    // Progress[    batch,     batch,     tick,     tick] (missed)
    // Progress[2 * batch, 2 * batch, 2 * tick, 2 * tick] (signalled and reset)
    // Progress[    batch, 3 * batch,     tick, 3 * tick] (missed)
    // Progress[2 * batch, 4 * batch, 2 * tick, 4 * tick] (signalled and reset)
    // etc...
    int length = batchSize * count;
    int lastlyMissed = count % thresholdScale;
    for (int i = 0; i <= count / thresholdScale; i++) {
      int thresholdBatchSize = batchSize * thresholdScale;
      int totalTransferred = i * thresholdBatchSize;
      listener.assertNext(
          i > 0 ? thresholdBatchSize : 0, // current
          totalTransferred, // total
          length,
          i > 0 ? virtualTick.multipliedBy(thresholdScale) : Duration.ZERO, // current
          virtualTick.multipliedBy(i * thresholdScale), // total
          totalTransferred == length);
    }
    if (lastlyMissed > 0) { // with enclosing 100% from onComplete
      listener.assertNext(
          batchSize * lastlyMissed, // missed progress
          length, // total
          length,
          virtualTick.multipliedBy(lastlyMissed + 1), // scale by lastlyMissed (missed ticks) + 1 (tick from onComplete)
          virtualTick.multipliedBy(count + 1), // scale by count (all ticks) + 1 (tick from onComplete)
          true);
    }
  }

  // ensure body is forwarded to actual downstream
  private static void assertBodyForwarded(
      TestSubscriber<ByteBuffer> downstream, int batchSize, int count) {
    downstream.awaitComplete();
    assertEquals(count, downstream.nexts);
    assertEquals(
        batchSize * count,
        BodyCollector.collect(List.copyOf(downstream.items)).remaining());
  }

  private BodyPublisher bodyPublisher(int batchSize, int count) {
    int length = batchSize * count;
    return BodyPublishers.fromPublisher(
        FlowAdapters.toFlowPublisher(
            new AsyncIterablePublisher<>(
                () -> new BuffIterator(ByteBuffer.allocate(length), batchSize), upstreamExecutor)),
        length);
  }

  private Flow.Publisher<List<ByteBuffer>> listPublisher(int batchSize, int count) {
    int length = batchSize * count;
    return FlowAdapters.toFlowPublisher(
        new AsyncIterablePublisher<>(
            () -> new BuffListIterator(ByteBuffer.allocate(length), batchSize, 1),
            upstreamExecutor));
  }

  private ProgressTracker.Builder withExecutor(Executor executor) {
    return  ProgressTracker.newBuilder().executor(executor);
  }

  private ProgressTracker.Builder withVirtualClock(Executor executor) {
    return withExecutor(executor).clock(new VirtualClock(virtualTick));
  }

  private static long countBytes(Collection<List<ByteBuffer>> items) {
    return items.stream().flatMap(Collection::stream).mapToLong(ByteBuffer::remaining).sum();
  }

  private static void assertProgress(
      Progress progress,
      long transferred, long totalTransferred, long contentLength,
      Duration time, Duration totalTime,
      boolean completed) {
    if (transferred >= 0) {
      assertEquals(transferred, progress.bytesTransferred());
    }
    assertEquals(totalTransferred, progress.totalBytesTransferred());
    assertEquals(contentLength, progress.contentLength());
    assertEquals(time, progress.timePassed());
    assertEquals(totalTime, progress.totalTimePassed());
    assertEquals(completed, progress.done());
  }

  private static final class TestListener
      extends TestSubscriber<Progress> implements Listener {

    TestListener() {}

    void assertNext(
        long transferred, long totalTransferred, long contentLength,
        Duration time, Duration totalTime,
        boolean completed) {
      assertFalse(items.isEmpty());
      assertProgress(
          items.poll(),
          transferred, totalTransferred, contentLength,
          time, totalTime, completed);
    }
  }

  private static final class TestMultipartListener
      extends TestSubscriber<MultipartProgress> implements MultipartListener {

    TestMultipartListener() {}
  }

  private static final class VirtualClock extends Clock {

    private final Duration tick;
    private Instant current;

    private VirtualClock(Duration tick) {
      this.tick = tick;
      current = Instant.now();
    }

    @Override
    public Instant instant() {
      Instant instant = current;
      current = instant.plus(tick);
      return instant;
    }

    @Override public ZoneId getZone() { throw new AssertionError(); }
    @Override public Clock withZone(ZoneId zone) { throw new AssertionError(); }
  }
}
