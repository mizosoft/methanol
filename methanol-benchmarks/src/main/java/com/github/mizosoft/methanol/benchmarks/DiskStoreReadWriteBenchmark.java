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

package com.github.mizosoft.methanol.benchmarks;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
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
public class DiskStoreReadWriteBenchmark extends DiskStoreState {
  private static final int KEY_COUNT = 8;
  private static final int KEY_DISTRIBUTION_SIZE = 8 * KEY_COUNT;
  private static final int KEY_DISTRIBUTION_SIZE_MASK =
      KEY_DISTRIBUTION_SIZE - 1; // Assumes a power of two.

  private List<String> keyDistribution;

  @Setup
  public void setUp(WriteSrc src) throws Exception {
    super.setupStore(Long.MAX_VALUE);

    var keys =
        IntStream.range(0, KEY_COUNT)
            .mapToObj(Integer::toString)
            .collect(Collectors.toUnmodifiableList());

    for (var key : keys) {
      write(key, src);
    }

    // TODO use a more realistic distribution (zipf).
    var keyDistribution = new ArrayList<String>(KEY_DISTRIBUTION_SIZE);
    for (int i = 0; i < KEY_DISTRIBUTION_SIZE; i++) {
      keyDistribution.add(keys.get(ThreadLocalRandom.current().nextInt(0, keys.size())));
    }
    this.keyDistribution = List.copyOf(keyDistribution);
  }

  private ByteBuffer read(ThreadLocalInt index, ReadDst dst) throws Exception {
    var key = keyDistribution.get(index.getAndIncrement() & KEY_DISTRIBUTION_SIZE_MASK);
    return super.read(key, dst);
  }

  private void write(ThreadLocalInt index, WriteSrc src) throws Exception {
    var key = keyDistribution.get(index.getAndIncrement() & KEY_DISTRIBUTION_SIZE_MASK);
    super.write(key, src);
  }

  @Benchmark
  public ByteBuffer read_singleThreaded(ThreadLocalInt index, ReadDst dst) throws Exception {
    return read(index, dst);
  }

  @Benchmark
  public void write_singleThreaded(ThreadLocalInt index, WriteSrc src) throws Exception {
    write(index, src);
  }

  @Benchmark
  @Threads(8)
  public ByteBuffer read_multiThreaded(ThreadLocalInt index, ReadDst dst) throws Exception {
    return read(index, dst);
  }

  @Benchmark
  @Group("readWrite")
  @GroupThreads(6)
  public ByteBuffer readWrite_reader(ThreadLocalInt index, ReadDst dst) throws Exception {
    return read(index, dst);
  }

  @Benchmark
  @Group("readWrite")
  @GroupThreads(1) // Can only have one writer for the same entry.
  public void readWrite_writer(ThreadLocalInt index, WriteSrc src) throws Exception {
    write(index, src);
  }

  @State(Scope.Thread)
  public static class ThreadLocalInt {
    private int index;

    int getAndIncrement() {
      return index++;
    }
  }

  public static void main(String[] args) throws RunnerException {
    var builder =
        new OptionsBuilder()
            .include(DiskStoreReadWriteBenchmark.class.getSimpleName())
            .shouldFailOnError(true);
    new Runner(builder.build()).run();
  }
}
