/*
 * Copyright (c) 2024 Moataz Hussein
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

package com.github.mizosoft.methanol.internal.concurrent;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorSpec;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.TestUtils;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

class CancellationPropagatingFutureTest {
  @Test
  void upstreamIsCancelledWhenDownstreamIs() {
    var upstream = new TrackedFuture();
    var downstream = CancellationPropagatingFuture.of(upstream);
    downstream.cancel(true);
    upstream.assertCancelledWith(true);
  }

  @Test
  void upstreamIsCancelledWhenDownstreamIsWithFalseArgument() {
    var upstream = new TrackedFuture();
    var downstream = CancellationPropagatingFuture.of(upstream);
    downstream.cancel(false);
    upstream.assertCancelledWith(false);
  }

  @Test
  void downstreamIsCompletedNormallyWhenUpstreamIs() {
    var upstream = new CompletableFuture<>();
    var downstream = CancellationPropagatingFuture.of(upstream);
    upstream.complete(1);
    assertThat(downstream).isCompletedWithValue(1);
  }

  @Test
  void downstreamIsCompletedExceptionallyWhenUpstreamIs() {
    var upstream = new CompletableFuture<Integer>();
    var downstream = CancellationPropagatingFuture.of(upstream);
    upstream.completeExceptionally(new TestException());
    assertThat(downstream).isCompletedExceptionally();
  }

  @Test
  void downstreamDependenciesAreCancelledWhenUpstreamIs() {
    var upstream = new CompletableFuture<Integer>();
    var downstream = CancellationPropagatingFuture.of(upstream);
    var dep1 = downstream.thenRun(() -> {});
    var dep2 = downstream.thenApply(__ -> 1);
    upstream.cancel(false);
    assertThat(downstream).isCancelled();

    // Sometimes cancellation is propagated downstream wrapped in a CompletionException
    // (ExecutionException when queried with Future::get).
    assertCancelledFromUpstream(dep1);
    assertCancelledFromUpstream(dep2);
  }

  @Test
  void downstreamDependenciesAndUpstreamAreCancelledWhenDownstreamIs() {
    var upstream = new TrackedFuture();
    var downstream = CancellationPropagatingFuture.of(upstream);
    var dep1 = downstream.thenRun(() -> {});
    var dep2 = downstream.thenApply(__ -> 1);
    downstream.cancel(true);
    upstream.assertCancelledWith(true);
    assertCancelledFromUpstream(dep1);
    assertCancelledFromUpstream(dep2);
  }

  @Test
  void upstreamIsCancelledWhenDownstreamDependencyIs() {
    var upstream = new TrackedFuture();
    var downstream = CancellationPropagatingFuture.of(upstream);
    var dep1 = downstream.thenRun(() -> {});
    var dep2 = downstream.thenApply(__ -> 1);
    dep1.cancel(true);
    upstream.assertCancelledWith(true);
    assertThat(downstream).isCancelled();
    assertCancelledFromUpstream(dep2);
  }

  @Test
  void downstreamThenComposeInnerFutureIsCancelledWhenDownstreamDependencyIs() {
    var upstream = new TrackedFuture();
    var composeInnerFuture = new TrackedFuture();
    var downstream = CancellationPropagatingFuture.of(upstream);
    var dep1 =
        downstream.thenCompose(__ -> composeInnerFuture).thenRun(() -> {}).thenAccept(__ -> {});
    upstream.complete(1);
    dep1.cancel(true);
    composeInnerFuture.assertCancelledWith(true);
  }

  @Test
  void downstreamThenComposeAsyncInnerFutureIsCancelledWhenDownstreamDependencyIs() {
    var upstream = new TrackedFuture();
    var composeInnerFuture = new TrackedFuture();
    var downstream = CancellationPropagatingFuture.of(upstream);
    var dep1 =
        downstream
            .thenComposeAsync(__ -> composeInnerFuture)
            .thenRun(() -> {})
            .thenAccept(__ -> {});
    upstream.complete(1);
    dep1.cancel(true);

    // Must wait as the future is acquired asynchronously
    assertThat(composeInnerFuture).failsWithin(Duration.ofSeconds(TestUtils.TIMEOUT_SECONDS));
    composeInnerFuture.assertCancelledWith(true);
  }

  @Test
  @ExecutorSpec(ExecutorType.CACHED_POOL)
  @ExtendWith(ExecutorExtension.class)
  void downstreamThenComposeAsyncWithExecutorInnerFutureIsCancelledWhenDownstreamDependencyIs(
      Executor executor) {
    var upstream = new TrackedFuture();
    var composeInnerFuture = new TrackedFuture();
    var downstream = CancellationPropagatingFuture.of(upstream);
    var dep1 =
        downstream
            .thenComposeAsync(__ -> composeInnerFuture, executor)
            .thenRun(() -> {})
            .thenAccept(__ -> {});
    upstream.complete(1);
    dep1.cancel(true);

    // Must wait as the future is acquired asynchronously
    assertThat(composeInnerFuture).failsWithin(Duration.ofSeconds(TestUtils.TIMEOUT_SECONDS));
    composeInnerFuture.assertCancelledWith(true);
  }

  private static void assertCancelledFromUpstream(CompletableFuture<?> future) {
    // Cancellation propagates from upstream as a CancellationException, and future::isCancelled may
    // not return true then as the exception is wrapped in a CompletionException.
    assertThat(future)
        .failsWithin(Duration.ZERO)
        .withThrowableOfType(ExecutionException.class)
        .withCauseInstanceOf(CancellationException.class);
  }

  private static final class TrackedFuture extends CompletableFuture<Integer> {
    final AtomicReference<Boolean> cancelledMayInterruptIfRunning = new AtomicReference<>();

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      assertThat(cancelledMayInterruptIfRunning.compareAndSet(null, mayInterruptIfRunning))
          .isTrue();
      return super.cancel(mayInterruptIfRunning);
    }

    void assertCancelledWith(boolean mayInterruptIfRunning) {
      assertThat(cancelledMayInterruptIfRunning.get()).isNotNull().isEqualTo(mayInterruptIfRunning);
    }
  }
}
