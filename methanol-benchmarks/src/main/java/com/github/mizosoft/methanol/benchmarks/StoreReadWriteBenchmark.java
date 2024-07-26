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

package com.github.mizosoft.methanol.benchmarks;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.Store;
import com.github.mizosoft.methanol.testing.TestUtils;
import com.github.mizosoft.methanol.testing.store.DiskStoreConfig;
import com.github.mizosoft.methanol.testing.store.StoreConfig;
import com.github.mizosoft.methanol.testing.store.StoreConfig.Execution;
import com.github.mizosoft.methanol.testing.store.StoreConfig.FileSystemType;
import com.github.mizosoft.methanol.testing.store.StoreConfig.StoreType;
import com.github.mizosoft.methanol.testing.store.StoreContext;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.commons.math3.distribution.ZipfDistribution;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
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
import org.openjdk.jmh.runner.options.OptionsBuilder;

@Fork(value = 1, jvmArgsAppend = "-Xmx6g")
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 5, time = 5)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@State(Scope.Benchmark)
public class StoreReadWriteBenchmark {
  private static final int KEYS_COUNT = 2 << 6;
  private static final int KEYS_COUNT_MASK = KEYS_COUNT - 1;
  private static final int BUFFER_SIZE = 16 * 1024;

  @Param public StoreType storeType;

  @Param({"16384", "262144", "4194304", "67108864"})
  public int dataSize;

  private StoreContext context;
  private Store store;
  private String[] keys;

  @Setup
  public void setUp() throws IOException {
    context =
        StoreContext.of(
            storeType == StoreType.DISK
                ? new DiskStoreConfig(
                    Long.MAX_VALUE,
                    1,
                    FileSystemType.SYSTEM,
                    Execution.ASYNC,
                    -1,
                    false,
                    false,
                    false)
                : StoreConfig.createDefault(storeType));
    store = context.createAndRegisterStore();
    keys = new String[KEYS_COUNT];

    // The actual number of unique keys will be less than KEYS_COUNT.
    var distribution = new ZipfDistribution(KEYS_COUNT, 1);
    for (int i = 0; i < KEYS_COUNT; i++) {
      keys[i] = "e" + distribution.sample();
    }
  }

  @TearDown
  public void tearDown() throws Exception {
    context.close();
  }

  @Benchmark
  public void read_singleThreaded(
      ThreadLocalInt entryId, ReadDataState ignored, ReadDstState dstState, Blackhole blackhole)
      throws IOException {
    read(keys[entryId.getAndIncrement() & KEYS_COUNT_MASK], dstState, blackhole);
  }

  @Benchmark
  public void write_singleThreaded(
      ThreadLocalInt entryId, WriteMetadataState metadataState, WriteSrcsState srcsState)
      throws IOException {
    write(keys[entryId.getAndIncrement() & KEYS_COUNT_MASK], metadataState, srcsState);
  }

  @Benchmark
  @Group("readWrite")
  @GroupThreads(6)
  public void readWrite_reader(
      ThreadLocalInt entryId, ReadDataState ignored, ReadDstState dstState, Blackhole blackhole)
      throws IOException {
    read(keys[entryId.getAndIncrement() & KEYS_COUNT_MASK], dstState, blackhole);
  }

  @Benchmark
  @Group("readWrite")
  @GroupThreads(1) // Can only have one writer for the same entry.
  public void readWrite_writer(
      ThreadLocalInt entryId, WriteMetadataState metadataState, WriteSrcsState srcsState)
      throws IOException {
    write(keys[entryId.getAndIncrement() & KEYS_COUNT_MASK], metadataState, srcsState);
  }

  private void read(String key, ReadDstState dstState, Blackhole blackhole) throws IOException {
    var dst = dstState.dst.duplicate();
    try (var viewer = store.view(key).orElseThrow()) {
      var reader = viewer.newReader();
      int totalRead = 0;
      int read;
      while ((read = reader.read(dst.clear())) >= 0) {
        blackhole.consume(dst.flip());
        totalRead += read;
      }
      if (totalRead != dataSize) {
        throw new AssertionError(
            "Expected " + dataSize + " bytes to be read, instead read " + totalRead + " bytes");
      }
    }
  }

  private void write(String key, WriteMetadataState metadataState, WriteSrcsState srcsState)
      throws IOException {
    try (var editor = store.edit(key).orElseThrow()) {
      var writer = editor.writer();
      int totalWritten = 0;
      for (var src : srcsState.srcs) {
        // EntryWriter writes all given bytes.
        totalWritten += writer.write(src.duplicate());
      }
      editor.commit(metadataState.metadata.duplicate());
      if (totalWritten != dataSize) {
        throw new AssertionError(
            "Expected "
                + dataSize
                + " bytes to be written, instead wrote "
                + totalWritten
                + " bytes");
      }
    }
  }

  @State(Scope.Thread)
  public static class ThreadLocalInt {
    private int value;

    int getAndIncrement() {
      return value++ & KEYS_COUNT_MASK;
    }
  }

  @State(Scope.Benchmark)
  public static class DataState {
    ByteBuffer data;

    @Setup
    public void setUp(StoreReadWriteBenchmark benchmark) {
      int dataSize = benchmark.dataSize;
      data = ByteBuffer.allocate(dataSize);
      TestUtils.newRandom().ints(dataSize, 0x21, 0x7F).forEach(i -> data.put((byte) i));
      data.flip();
    }
  }

  @State(Scope.Benchmark)
  public static class ReadDataState {
    @Setup
    public void setUp(StoreReadWriteBenchmark benchmark, DataState dataState) throws IOException {
      // Populate all entries.
      for (var key : new HashSet<>(List.of(benchmark.keys))) {
        try (var editor = benchmark.store.edit(key).orElseThrow()) {
          editor.writer().write(dataState.data.duplicate());
          editor.commit(TestUtils.EMPTY_BUFFER);
        }
      }
    }
  }

  @State(Scope.Thread)
  public static class ReadDstState {
    ByteBuffer dst;

    @Setup
    public void setUp() {
      dst = ByteBuffer.allocate(BUFFER_SIZE);
    }
  }

  @State(Scope.Benchmark)
  public static class WriteMetadataState {
    private static final int METADATA_SIZE = 1024;

    ByteBuffer metadata;

    @Setup
    public void setUp() {
      metadata = ByteBuffer.allocate(METADATA_SIZE);
      TestUtils.newRandom().ints(METADATA_SIZE, 0x21, 0x7F).forEach(i -> metadata.put((byte) i));
    }
  }

  @State(Scope.Thread)
  public static class WriteSrcsState {
    List<ByteBuffer> srcs;

    @Setup
    public void setUp(DataState dataState) {
      srcs = new ArrayList<>();
      var data = dataState.data.duplicate();
      while (data.hasRemaining()) {
        var src = ByteBuffer.allocate(BUFFER_SIZE);
        Utils.copyRemaining(data, src);
        srcs.add(src.flip());
      }
    }
  }

  public static void main(String[] args) throws Exception {
    var builder =
        new OptionsBuilder()
            .include(StoreReadWriteBenchmark.class.getName())
            .shouldFailOnError(true);
    new Runner(builder.build()).run();
  }
}
