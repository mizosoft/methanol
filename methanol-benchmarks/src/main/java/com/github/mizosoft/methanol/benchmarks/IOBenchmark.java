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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

@Fork(value = 1)
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 5, time = 5)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@State(Scope.Benchmark)
public class IOBenchmark {

  @Param({
    "32768",
    "131072",
    "524288",
    "2097152",
    "8388608",
    "33554432",
    "134217728",
    "536870912",
    "2147483648",
    "8589934592"
  })
  public long fileSizeBytes;

  private Path tempFile;
  private FileChannel channel;
  private ByteBuffer readBuffer;

  @Setup
  public void setUp() throws IOException {
    int unitSizeBytes = 8 * 1024;

    tempFile = Files.createTempFile(IOBenchmark.class.getSimpleName(), ".txt");
    channel = FileChannel.open(tempFile, StandardOpenOption.READ, StandardOpenOption.WRITE);
    readBuffer = ByteBuffer.allocate(unitSizeBytes);

    // Populate channel.
    var bytes = new byte[unitSizeBytes];
    var buffer = ByteBuffer.wrap(bytes);
    for (long written = 0; written < fileSizeBytes; ) {
      ThreadLocalRandom.current().nextBytes(bytes);
      buffer.rewind();
      while (buffer.hasRemaining()) {
        written += channel.write(buffer);
      }
    }
  }

  @TearDown
  public void tearDown() throws Exception {
    try (var __ = channel) {
      Files.delete(tempFile);
    }
  }

  @Benchmark
  public void readWithoutCrc(Blackhole bh) throws Exception {
    channel.position(0);
    while (channel.read(readBuffer.clear()) != -1) {
      bh.consume(readBuffer.flip());
    }
  }

  @Benchmark
  public long readWithCrc(Blackhole bh) throws Exception {
    var crc32 = new CRC32();
    channel.position(0);
    while (channel.read(readBuffer.clear()) != -1) {
      crc32.update(readBuffer.flip());
      bh.consume(readBuffer.flip());
    }
    return crc32.getValue();
  }

  public static void main(String[] args) throws RunnerException {
    var builder =
        new OptionsBuilder().include(IOBenchmark.class.getSimpleName()).shouldFailOnError(true);
    new Runner(builder.build()).run();
  }
}
