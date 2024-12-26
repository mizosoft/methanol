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

package com.github.mizosoft.methanol.benchmarks;

import com.github.mizosoft.methanol.internal.concurrent.SerialExecutor;
import com.github.mizosoft.methanol.testing.TestUtils;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 5, time = 5)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.Throughput)
public class SerialExecutorBenchmark {
  private final ExecutorService delegate = Executors.newCachedThreadPool();
  private final SerialExecutor executor = new SerialExecutor(delegate);

  @TearDown
  public void tearDown() throws InterruptedException {
    delegate.shutdown();
    if (!delegate.awaitTermination(TestUtils.TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      throw new RuntimeException("timed out while waiting for termination");
    }
  }

  @Benchmark
  @Threads(1)
  public int execute_1thread(PhaserState state) {
    executor.execute(state.phaser::arrive);
    return state.phaser.arriveAndAwaitAdvance();
  }

  @Benchmark
  @Threads(2)
  public int execute_2thread(PhaserState state) {
    executor.execute(state.phaser::arrive);
    return state.phaser.arriveAndAwaitAdvance();
  }

  @Benchmark
  @Threads(4)
  public int execute_4threads(PhaserState state) {
    executor.execute(state.phaser::arrive);
    return state.phaser.arriveAndAwaitAdvance();
  }

  @Benchmark
  @Threads(8)
  public int execute_8threads(PhaserState state) {
    executor.execute(state.phaser::arrive);
    return state.phaser.arriveAndAwaitAdvance();
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void bulkExecute(PhaserState phaserState, Counter counter) {
    counter.val = 0;
    int iterations = 100;
    for (int i = 0; i < iterations; i++) {
      executor.execute(counter::incr);
    }
    executor.execute(phaserState.phaser::arrive);
    phaserState.phaser.arriveAndAwaitAdvance();
    if (counter.val != iterations) {
      throw new AssertionError("expected: " + iterations + ", actual: " + counter.val);
    }
  }

  @State(Scope.Thread)
  public static class PhaserState {
    final Phaser phaser = new Phaser(2);
  }

  @State(Scope.Thread)
  public static class Counter {
    int val;

    void incr() {
      val++;
    }
  }

  public static void main(String[] args) throws RunnerException {
    var builder =
        new OptionsBuilder()
            .include(SerialExecutorBenchmark.class.getSimpleName())
            .shouldFailOnError(true);
    new Runner(builder.build()).run();
  }
}
