package com.github.mizosoft.methanol.tests;

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
