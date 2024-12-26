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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A {@link CompletableFuture} that propagates cancellation to an upstream when a node in the
 * dependency tree starting from this future is cancelled.
 */
public final class CancellationPropagatingFuture<T> extends CompletableFuture<T> {
  /*
   * What we're trying to achieve is to propagate cancellation 'upwards' the dependency graph to all
   * nodes that have a perceivable in-degree of zero. This graph starts as a tree with a given
   * upstream CompletableFuture as its root. The tree becomes a graph when nodes with a perceivable
   * in-degree of zero are introduced by user with methods like thenCompose(...) (which we are
   * interested in for now), where the node is represented by the CompletableFuture returned by the
   * given function.
   *
   * The goal is for cancellation to inevitably reach the HTTP client whichever returned future
   * is cancelled. The HTTP client's future makes sure that this is the case if we start with it as
   * the root, but we usually introduce this future inside a thenCompose(...), which messes with the
   * built-in cancellation mechanism.
   */
  private static final boolean PROPAGATE_CANCELLATION =
      Boolean.parseBoolean(
          System.getProperty("com.github.mizosoft.methanol.future.propagateCancellation", "true"));

  private final CompletableFuture<Boolean> cancellation;

  private CancellationPropagatingFuture() {
    cancellation = new CompletableFuture<>();
  }

  private CancellationPropagatingFuture(CompletableFuture<Boolean> cancellation) {
    this.cancellation = cancellation;
  }

  private void propagateCancellationTo(CompletionStage<?> stage) {
    cancellation.thenAccept(stage.toCompletableFuture()::cancel);
  }

  private void propagateCancellationTo(Consumer<Boolean> cancellable) {
    cancellation.thenAccept(cancellable);
  }

  @Override
  public <U> CompletableFuture<U> newIncompleteFuture() {
    return new CancellationPropagatingFuture<>(cancellation);
  }

  @SuppressWarnings("NullableProblems")
  @Override
  public <U> CompletableFuture<U> thenCompose(
      Function<? super T, ? extends CompletionStage<U>> fn) {
    return super.thenCompose(attachTo(fn));
  }

  @SuppressWarnings("NullableProblems")
  @Override
  public <U> CompletableFuture<U> thenComposeAsync(
      Function<? super T, ? extends CompletionStage<U>> fn) {
    return super.thenComposeAsync(attachTo(fn));
  }

  @SuppressWarnings("NullableProblems")
  @Override
  public <U> CompletableFuture<U> thenComposeAsync(
      Function<? super T, ? extends CompletionStage<U>> fn, Executor executor) {
    return super.thenComposeAsync(attachTo(fn), executor);
  }

  private <U> Function<T, CompletionStage<U>> attachTo(
      Function<? super T, ? extends CompletionStage<U>> fn) {
    return result -> {
      var stage = fn.apply(result);
      propagateCancellationTo(stage);
      return stage;
    };
  }

  @Override
  public boolean cancel(boolean mayInterruptIfRunning) {
    if (!super.cancel(mayInterruptIfRunning)) {
      return false;
    }
    cancellation.complete(mayInterruptIfRunning);
    return true;
  }

  public static <T> CancellationPropagatingFuture<T> create() {
    var future = new CancellationPropagatingFuture<T>();
    future.propagateCancellationTo(future::cancel);
    return future;
  }

  public static <T> CompletionStage<T> of(CompletionStage<T> upstream) {
    return !PROPAGATE_CANCELLATION || upstream instanceof CancellationPropagatingFuture
        ? upstream
        : downstreamOf(upstream);
  }

  public static <T> CompletableFuture<T> of(CompletableFuture<T> upstream) {
    return !PROPAGATE_CANCELLATION || upstream instanceof CancellationPropagatingFuture
        ? upstream
        : downstreamOf(upstream);
  }

  private static <T> CancellationPropagatingFuture<T> downstreamOf(CompletionStage<T> upstream) {
    var downstream = new CancellationPropagatingFuture<T>();
    downstream.propagateCancellationTo(upstream);
    upstream.whenComplete(
        (result, exception) -> {
          if (exception != null) {
            downstream.completeExceptionally(exception);
          } else {
            downstream.complete(result);
          }
        });
    return downstream;
  }
}
