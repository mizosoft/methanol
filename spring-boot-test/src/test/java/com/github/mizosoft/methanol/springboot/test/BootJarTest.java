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

package com.github.mizosoft.methanol.springboot.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.github.mizosoft.methanol.Methanol;
import com.github.mizosoft.methanol.MoreBodyHandlers;
import com.github.mizosoft.methanol.MutableRequest;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorSpec;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.TestUtils;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ExecutorExtension.class)
class BootJarTest {
  private static final String JAR_PATH_PROP =
      "com.github.mizosoft.methanol.springboot.test.bootJarPath";
  private static final int LAUNCH_BOOT_JAR_TIMEOUT = TestUtils.VERY_SLOW_TIMEOUT_SECONDS;

  // Range for 'dynamic ports'
  private static final int PORT_START = 49152;
  private static final int PORT_END = 65535;

  private final List<String> processOutput = new ArrayList<>();
  private Process bootJarProcess;
  private Methanol client;
  private ExecutorService executor;

  @BeforeEach
  void assumeJava() throws Exception {
    var process =
        new ProcessBuilder().command("java", "--version").redirectErrorStream(true).start();
    try (var in = TestUtils.inputReaderOf(process)) {
      assertThat(process.waitFor(TestUtils.TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .withFailMessage("'java --version' timed out")
          .isTrue();
      assumeThat(process.exitValue())
          .withFailMessage(
              () ->
                  formatProcessOutput(
                      "'java --version' failed", in.lines().collect(Collectors.toList())))
          .isEqualTo(0);
    }
  }

  @BeforeEach
  @ExecutorSpec(ExecutorType.CACHED_POOL)
  void setUp(ExecutorService executor) {
    this.executor = executor;
  }

  private boolean tryLaunchBootJar(int port) throws Exception {
    // Destroy previously unsuccessful process if any
    if (bootJarProcess != null) {
      bootJarProcess.destroyForcibly();
      processOutput.clear();
    }
    bootJarProcess =
        new ProcessBuilder()
            .command("java", "-jar", findBootJarPath(), "--server.port=" + port)
            .redirectErrorStream(true)
            .start();

    // No need to close reader as the underlying InputStream will be closed when the process is
    // destroyed. Additionally, closing causes deadlocks if an assertion fails while readLine is
    // blocked indefinitely (BufferedReader's close & readLine wait on the same lock).
    @SuppressWarnings("resource")
    var in = TestUtils.inputReaderOf(bootJarProcess);
    while (true) {
      String line;
      var lineFuture = executor.submit(in::readLine);
      try {
        line = lineFuture.get(TestUtils.VERY_SLOW_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (TimeoutException e) {
        lineFuture.cancel(true);
        return fail(formatProcessOutput("readLine timed out", processOutput), e);
      }

      assertThat(line)
          .withFailMessage(
              () ->
                  formatProcessOutput(
                      "log reached EOS (the app didn't start as expected)", processOutput))
          .isNotNull();
      processOutput.add(line);

      if (line.matches("Web server failed to start. Port \\d{1,5} was already in use.")) {
        return false;
      }

      if (line.contains("Started SpringBootApp")) {
        return true;
      }
    }
  }

  @BeforeEach
  @Timeout(LAUNCH_BOOT_JAR_TIMEOUT)
  void launchBootJar() throws Exception {
    int port;
    do {
      port = ThreadLocalRandom.current().nextInt(PORT_START, PORT_END + 1);
    } while (!tryLaunchBootJar(port));
    client = Methanol.newBuilder().baseUri("http://localhost:" + port).build();
  }

  @AfterEach
  void tearDown() {
    TestUtils.shutdown(executor);
    if (bootJarProcess != null) {
      bootJarProcess.destroyForcibly();
    }
  }

  @Test
  void test() throws Exception {
    HttpResponse<Point> response;
    try {
      response =
          client.send(
              MutableRequest.GET("?x=11&y=22")
                  .timeout(Duration.ofSeconds(TestUtils.TIMEOUT_SECONDS)),
              MoreBodyHandlers.ofObject(Point.class));
    } catch (IOException e) {
      // Spill what's remaining in stdout without blocking
      var sb = new StringBuilder();
      try (var in = TestUtils.inputReaderOf(bootJarProcess)) {
        while (in.ready()) {
          sb.append((char) in.read());
        }
        sb.toString().lines().forEach(processOutput::add);
        fail(formatProcessOutput("Test failed (see cause)", processOutput), e);
      }

      return; // Effectively unreachable
    }

    assertThat(response.body()).isEqualTo(new Point(11, 22));
  }

  private static String formatProcessOutput(String message, List<String> processOutput) {
    var sb = new StringBuilder(message);
    if (!processOutput.isEmpty()) {
      sb.append(System.lineSeparator()).append("Process output: <");
    }
    for (int i = 0; i < processOutput.size(); i++) {
      sb.append(processOutput.get(i));
      if (i < processOutput.size() - 1) {
        sb.append(System.lineSeparator()).append("\t");
      } else {
        sb.append(">");
      }
    }
    return sb.toString();
  }

  private static String findBootJarPath() {
    var path = System.getProperty(JAR_PATH_PROP);
    assertThat(path)
        .withFailMessage(() -> "Couldn't find boot jar: " + JAR_PATH_PROP + " isn't set")
        .isNotNull();
    return path;
  }
}
