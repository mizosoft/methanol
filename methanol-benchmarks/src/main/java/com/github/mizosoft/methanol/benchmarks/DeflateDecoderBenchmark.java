/*
 * Copyright (c) 2022 Moataz Abdelnasser
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

import static com.github.mizosoft.methanol.benchmarks.BenchmarkUtils.deflate;
import static com.github.mizosoft.methanol.testing.TestUtils.load;

import com.github.mizosoft.methanol.MoreBodyHandlers;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.BodySubscribers;
import java.util.concurrent.TimeUnit;
import java.util.zip.InflaterInputStream;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okio.Buffer;
import org.jetbrains.annotations.NotNull;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
@Fork(value = 1)
@Threads(Threads.MAX)
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 5, time = 5)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
public class DeflateDecoderBenchmark extends ClientServerLifecycle {

  @Benchmark
  public byte[] readBytesInflaterInputStream() throws Exception {
    return client
        .sendAsync(
            defaultGet,
            info ->
                BodySubscribers.mapping(BodySubscribers.ofInputStream(), InflaterInputStream::new))
        .join()
        .body()
        .readAllBytes();
  }

  @Benchmark
  public byte[] readBytesDeflateDecoder() throws Exception {
    return client
        .sendAsync(defaultGet, MoreBodyHandlers.decoding(BodyHandlers.ofInputStream()))
        .join()
        .body()
        .readAllBytes();
  }

  @Override
  public void configureServer(MockWebServer server) {
    byte[] data = load(DeflateDecoderBenchmark.class, "/payload/alice29.txt");
    var body = new Buffer().write(deflate(data));
    server.setDispatcher(
        new Dispatcher() {
          @NotNull
          @Override
          public MockResponse dispatch(@NotNull RecordedRequest recordedRequest) {
            return new MockResponse()
                .addHeader("Content-Encoding", "deflate")
                .setBody(body.clone());
          }
        });
  }

  public static void main(String[] args) throws RunnerException {
    var builder =
        new OptionsBuilder()
            .include(DeflateDecoderBenchmark.class.getSimpleName())
            .shouldFailOnError(true);
    new Runner(builder.build()).run();
  }
}
