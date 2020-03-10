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

package com.github.mizosoft.methanol.benchmarks;

import static com.github.mizosoft.methanol.benchmarks.BenchmarkUtils.gzip;
import static com.github.mizosoft.methanol.testutils.TestUtils.load;

import com.github.mizosoft.methanol.MoreBodyHandlers;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.BodySubscribers;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
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
@Warmup(iterations = 4, time = 5)
@Measurement(iterations = 6, time = 5)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
public class GzipDecoderBenchmark extends ClientServerLifcycle {

  @Benchmark
  public byte[] readBytesGZIPInputStream() throws Exception {
    return client
        .sendAsync(
            defaultGet,
            info ->
                BodySubscribers.mapping(
                    BodySubscribers.ofInputStream(), GzipDecoderBenchmark::wrapGzipped))
        .thenApplyAsync(res -> res.body().get(), client.executor().orElse(null))
        .join()
        .readAllBytes();
  }

  @Benchmark
  public byte[] readBytesGzipDecoder() throws Exception {
    return client
        .sendAsync(defaultGet, MoreBodyHandlers.decoding(BodyHandlers.ofInputStream()))
        .thenApply(HttpResponse::body)
        .join()
        .readAllBytes();
  }

  @Override
  public void configureServer(MockWebServer server) {
    byte[] data = load(GzipDecoderBenchmark.class, "/payload/alice29.txt");
    var body = new Buffer().write(gzip(data));
    server.setDispatcher(
        new Dispatcher() {
          @NotNull
          @Override
          public MockResponse dispatch(@NotNull RecordedRequest recordedRequest) {
            return new MockResponse().addHeader("Content-Encoding", "gzip").setBody(body.clone());
          }
        });
  }

  // use supplier due to deadlock in pre JDK13 when mapper blocks on input (GZIPInputStream::new
  // does)
  private static Supplier<InputStream> wrapGzipped(InputStream in) {
    return () -> {
      try {
        return new GZIPInputStream(in);
      } catch (IOException ioe) {
        throw new UncheckedIOException(ioe);
      }
    };
  }

  public static void main(String[] args) throws RunnerException {
    var builder =
        new OptionsBuilder()
            .include(GzipDecoderBenchmark.class.getSimpleName())
            .shouldFailOnError(true);
    new Runner(builder.build()).run();
  }
}
