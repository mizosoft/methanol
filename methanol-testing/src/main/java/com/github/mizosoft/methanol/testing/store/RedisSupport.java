/*
 * Copyright (c) 2024 Moataz Hussein
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

package com.github.mizosoft.methanol.testing.store;

import static com.github.mizosoft.methanol.internal.Validate.requireArgument;

import com.github.mizosoft.methanol.testing.TestUtils;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.io.IOException;
import java.io.Reader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

class RedisSupport {
  private static final Logger logger = System.getLogger(RedisSupport.class.getName());

  static final String SERVER_CMD = "redis-server";
  static final String CLI_CMD = "redis-cli";

  @GuardedBy("RedisSupport.class")
  private static final @MonotonicNonNull Set<String> AVAILABLE_COMMANDS = checkAvailability();

  private RedisSupport() {}

  public static boolean isRedisStandaloneAvailable() {
    return isAvailable(SERVER_CMD);
  }

  public static boolean isRedisClusterAvailable() {
    return isAvailable(SERVER_CMD) && isAvailable(CLI_CMD);
  }

  private static synchronized boolean isAvailable(String command) {
    requireArgument(
        command.equals(SERVER_CMD) || command.equals(CLI_CMD), "unrecognized command: %s", command);
    return AVAILABLE_COMMANDS.contains(command);
  }

  private static Set<String> checkAvailability() {
    try {
      var version = versionOf(SERVER_CMD);
      logger.log(Level.INFO, () -> "Found " + SERVER_CMD + ": " + version);
    } catch (UnavailableCommandException e) {
      logger.log(
          Level.WARNING, "Couldn't find " + SERVER_CMD + ". Related tests will be skipped.", e);

      // If we can't launch a redis server, then we surely can't create a redis cluster. We can thus
      // ignore the availability of the CLI client.
      return Set.of();
    }

    try {
      var version = versionOf(CLI_CMD);
      logger.log(Level.INFO, () -> "Found " + CLI_CMD + ": " + version);
      return Set.of(SERVER_CMD, CLI_CMD);
    } catch (UnavailableCommandException e) {
      logger.log(Level.WARNING, "Couldn't find " + CLI_CMD + ". Related tests will be skipped.", e);
      return Set.of(SERVER_CMD);
    }
  }

  private static String versionOf(String command) throws UnavailableCommandException {
    try {
      var process =
          new ProcessBuilder().command(command, "--version").redirectErrorStream(true).start();
      try (var reader = TestUtils.inputReaderOf(process)) {
        if (!process.waitFor(TestUtils.TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          reportUnavailability(command, "timed out", null, reader);
        }
        if (process.exitValue() != 0) {
          reportUnavailability(command, "non-zero exit code", null, reader);
        }
        return dumpRemaining(reader);
      }
    } catch (IOException e) {
      reportUnavailability(command, "exception when executing command", e, null);
      throw new AssertionError("Unreachable");
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  private static void reportUnavailability(
      String cmd,
      String unavailabilityReason,
      @Nullable Throwable exception,
      @Nullable Reader reader)
      throws UnavailableCommandException {
    String output;
    try {
      if (reader != null) {
        output = dumpRemaining(reader);
      } else {
        output = "<unavailable process output>";
      }
    } catch (IOException e) {
      output = "<problem reading process output>";
      if (exception != null) {
        exception.addSuppressed(e);
      } else {
        exception = e;
      }
    }

    throw new UnavailableCommandException(
        String.format(
            "Unavailable redis command <%s>: %s%n%s",
            cmd,
            unavailabilityReason,
            Stream.of(output.split("\\r?\\n"))
                .map(line -> "\t" + line)
                .collect(Collectors.joining())),
        exception);
  }

  static String dumpRemaining(Reader reader) throws IOException {
    // Spill what's written while ensuring we won't block.
    var sb = new StringBuilder();
    int read;
    while (reader.ready() && (read = reader.read()) != -1) {
      sb.append((char) read);
    }
    return sb.toString();
  }

  private static final class UnavailableCommandException extends IOException {
    UnavailableCommandException(String message, @Nullable Throwable cause) {
      super(message, cause);
    }
  }
}
