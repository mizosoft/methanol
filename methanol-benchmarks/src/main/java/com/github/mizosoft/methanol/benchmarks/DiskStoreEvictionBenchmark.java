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

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@Fork(value = 1)
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 5, time = 5)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@State(Scope.Benchmark)
public class DiskStoreEvictionBenchmark extends DiskStoreState {
  @Param({"1", "100", "10000", "1000000"})
  int entryCount;

  @Setup
  public void setUp(KeyGenerator keyGenerator, WriteSrc src) throws Exception {
    super.setupStore((long) entryCount * ENTRY_SIZE);

    for (int i = 0; i < entryCount; i++) {
      write(keyGenerator.next(), src);
    }
    if (store.size() != store.maxSize()) {
      throw new AssertionError(store.size() + " != " + store.maxSize());
    }
  }

  @Benchmark
  @Threads(1)
  public void triggerEviction(KeyGenerator keyGenerator, WriteSrc src) throws Exception {
    write(keyGenerator.next(), src);
  }

  /**
   * A key generator that ensures keys are of the same length for as long as possible. This ensures
   * calculating hashes for keys of different sizes don't skew benchmark results.
   */
  @State(Scope.Thread)
  public static class KeyGenerator {
    private long next = 1_000_000_000_000_000_000L;

    String next() {
      var key = Long.toString(next);
      next++;
      return key;
    }
  }

  public static void main(String[] args) throws RunnerException {
    var builder =
        new OptionsBuilder()
            .include(DiskStoreEvictionBenchmark.class.getSimpleName())
            .shouldFailOnError(true);
    new Runner(builder.build()).run();
  }
}
