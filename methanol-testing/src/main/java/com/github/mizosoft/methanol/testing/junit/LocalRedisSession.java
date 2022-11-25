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

package com.github.mizosoft.methanol.testing.junit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class LocalRedisSession implements AutoCloseable {
  private static final String LOOPBACK_ADDRESS = "127.0.0.1";
  private static final int DYNAMIC_PORT_START = 49152;
  private static final int DYNAMIC_PORT_END = 65535;

  private final Process process;
  private final Path directory;
  private final RedisClient client;

  private LocalRedisSession(Process process, Path directory, int port) {
    this.process = requireNonNull(process);
    this.directory = requireNonNull(directory);
    client = RedisClient.create(RedisURI.create(LOOPBACK_ADDRESS, port));
  }

  public RedisClient client() {
    return client;
  }

  @Override
  public void close() throws IOException {
    Directories.deleteRecursively(directory);
    client.close();
    process.destroy();
  }

  void spillRemainingLog(List<String> log) throws IOException {
    // Spill what's written to redis log while ensuring we won't block.
    var sb = new StringBuilder();
    var processOutput = process.inputReader(UTF_8);
    int read;
    while (processOutput.ready() && (read = processOutput.read()) != -1) {
      sb.append((char) read);
    }
    sb.toString().lines().forEach(log::add);
  }

  public static LocalRedisSession start(List<String> log)
      throws IOException, InterruptedException, TimeoutException {
    var directory = Files.createTempDirectory(LocalRedisSession.class.getName());
    var tempLog = new ArrayList<String>();
    while (true) {
      int port = ThreadLocalRandom.current().nextInt(DYNAMIC_PORT_START, DYNAMIC_PORT_END + 1);
      try {
        tempLog.clear();
        var process = tryStart(port, directory, tempLog);
        if (process != null) {
          log.addAll(tempLog);
          return new LocalRedisSession(process, directory, port);
        }
      } catch (IOException | TimeoutException | CompletionException e) {
        log.addAll(tempLog);
        throw e;
      }
    }
  }

  private static @Nullable Process tryStart(int port, Path directory, List<String> log)
      throws IOException, TimeoutException, InterruptedException {
    var process =
        new ProcessBuilder(
                "redis-server",
                "--bind",
                LOOPBACK_ADDRESS,
                "--port",
                Integer.toString(port),
                "--dir",
                directory.toAbsolutePath().toString(),
                "--save",
                "")
            .redirectErrorStream(true)
            .start();
    var processOutput = process.inputReader(UTF_8);
    var executor = Executors.newSingleThreadExecutor();
    while (true) {
      String line;
      var readLineFuture = executor.submit(processOutput::readLine);
      try {
        line = readLineFuture.get(10, TimeUnit.SECONDS);
        log.add(line);
      } catch (InterruptedException | TimeoutException e) {
        readLineFuture.cancel(true);
        executor.shutdown();
        process.destroy();
        throw e;
      } catch (ExecutionException e) {
        readLineFuture.cancel(true);
        executor.shutdown();
        process.destroy();
        var cause = e.getCause();
        if (cause instanceof RuntimeException) {
          throw (RuntimeException) cause;
        } else if (cause instanceof Error) {
          throw (Error) cause;
        } else if (cause instanceof IOException) {
          throw (IOException) cause;
        } else {
          throw new CompletionException(cause);
        }
      }

      if (line == null) {
        process.destroy();
        executor.shutdown();
        throw new EOFException("EOF reached unexpectedly");
      }

      if (line.contains("Ready to accept connections")) {
        executor.shutdown();
        return process;
      }

      if (line.contains("Address already in use")) {
        executor.shutdown();
        process.destroy();
        return null;
      }
    }
  }
}