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

package com.github.mizosoft.methanol.blackbox;

import com.github.mizosoft.methanol.testutils.TestUtils;
import java.io.IOException;
import java.net.http.HttpClient;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

class Lifecycle {

  MockWebServer server;
  HttpClient client;
  Executor executor;

  @BeforeEach
  void setup() throws IOException {
    server = new MockWebServer();
    configureServer(server);
    server.start();
    var builder = HttpClient.newBuilder();
    configureClient(builder);
    client = builder.build();
    executor = createExecutor();
  }

  @AfterEach
  void teardown() throws IOException {
    if (server != null) {
      server.shutdown();
    }
    TestUtils.shutdown(executor);
  }

  void configureServer(MockWebServer server) {}

  void configureClient(HttpClient.Builder builder) {}

  Executor createExecutor() {
    return Executors.newFixedThreadPool(8);
  }
}
