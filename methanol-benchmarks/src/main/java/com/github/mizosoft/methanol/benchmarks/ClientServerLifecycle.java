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

import static com.github.mizosoft.methanol.benchmarks.BenchmarkUtils.N_CPU;

import com.github.mizosoft.methanol.testing.TestUtils;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.util.concurrent.Executors;
import mockwebserver3.MockWebServer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@State(Scope.Benchmark)
public class ClientServerLifecycle {

  public @MonotonicNonNull MockWebServer server;
  public @MonotonicNonNull HttpClient client;
  public @MonotonicNonNull HttpRequest defaultGet;

  @Setup
  public void setUpClientServer() throws IOException {
    server = new MockWebServer();
    configureServer(server);
    server.start();
    client =
        HttpClient.newBuilder()
            .executor(Executors.newFixedThreadPool(N_CPU))
            .version(Version.HTTP_1_1)
            .build();
    defaultGet = HttpRequest.newBuilder(server.url("/").uri()).build();
  }

  @TearDown
  public void tearDownClientServer() throws IOException {
    server.shutdown();
    client.executor().ifPresent(TestUtils::shutdown);
  }

  public void configureServer(MockWebServer server) {}
}
