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

package com.github.mizosoft.methanol.testing.store;

import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.testing.TestUtils;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ShutdownArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.lang.ProcessBuilder.Redirect;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class RedisStandaloneSession implements RedisSession {
  private static final Logger logger = System.getLogger(RedisStandaloneSession.class.getName());

  private static final String LOOPBACK_ADDRESS = "127.0.0.1";

  private static final int DYNAMIC_PORT_START = 49152;
  private static final int DYNAMIC_PORT_END = 65535;

  private static final int SERVER_PORT_START = DYNAMIC_PORT_START;

  // Make room for the cluster bus port, which redis obtains by adding 10000 to the client
  // communication port.
  private static final int SERVER_PORT_END = DYNAMIC_PORT_END - 10000;

  private static final int PROCESS_WAIT_FOR_TIMEOUT_SECONDS = TestUtils.TIMEOUT_SECONDS;

  private static final int MASTER_SERVER_START_RETRIES = 10;

  private final RedisURI uri;
  private final Process process;
  private final Path directory;
  private final boolean deleteDirectoryOnClosure;
  private final Path logFile;
  private final RedisClient client;

  private boolean closed;

  private RedisStandaloneSession(
      int port, Process process, Path directory, boolean deleteDirectoryOnClosure, Path logFile) {
    this.uri = RedisURI.create(LOOPBACK_ADDRESS, port);
    this.process = requireNonNull(process);
    this.directory = requireNonNull(directory);
    this.deleteDirectoryOnClosure = deleteDirectoryOnClosure;
    this.logFile = requireNonNull(logFile);
    this.client = RedisClient.create(uri);
  }

  public RedisURI uri() {
    return uri;
  }

  public Path directory() {
    return directory;
  }

  @Override
  public List<Path> logFiles() {
    return List.of(logFile);
  }

  @Override
  public boolean reset() {
    if (closed || !process.isAlive()) {
      return false;
    }

    try (var client = RedisClient.create(uri);
        var connection = client.connect()) {
      connection.sync().flushall();
      return true;
    } catch (RedisException e) {
      logger.log(Level.WARNING, "Inoperable redis server", e);
      return false;
    }
  }

  @Override
  public boolean isHealthy() {
    if (closed || !process.isAlive()) {
      return false;
    }

    try (var client = RedisClient.create(uri);
        var connection = client.connect()) {
      connection.sync().set("k", "v");
      connection.sync().del("k");
      return true;
    } catch (RedisException e) {
      logger.log(Level.WARNING, "Inoperable redis server", e);
      return false;
    }
  }

  @Override
  public StatefulRedisConnection<String, String> connect() {
    return client.connect();
  }

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }

    closed = true;
    client.close();
    if (process.isAlive()) {
      shutdownServer();
    }
    if (deleteDirectoryOnClosure) {
      Directories.deleteRecursively(directory);
    }
  }

  private void shutdownServer() {
    boolean sentClientShutdown;
    try (var client = RedisClient.create(uri);
        var connection = client.connect()) {
      connection.sync().shutdown(ShutdownArgs.Builder.save(false).force());
      sentClientShutdown = true;
    } catch (RedisException ignored) {
      sentClientShutdown = false;
    }

    // Give the server a chance to respond to client SHUTDOWN if we'll have to destroy the process
    // forcibly.
    if (sentClientShutdown && !process.supportsNormalTermination()) {
      try {
        process.waitFor(PROCESS_WAIT_FOR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt(); // Restore interruption status.
      }
    }
    terminate(process);
  }

  @Override
  public String toString() {
    return getClass().getSimpleName()
        + "[uri="
        + uri
        + ", process="
        + process
        + ", directory="
        + directory
        + "]";
  }

  private static void terminate(Process process) {
    // First, try to terminate the process gracefully, giving it a chance to respond. If that takes
    // too long, terminate it forcibly.
    process.destroy();
    boolean interrupted = false;
    try {
      if (process.waitFor(PROCESS_WAIT_FOR_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        return;
      }
    } catch (InterruptedException e) {
      // Someone doesn't want to wait, fallthrough to forcible termination.
      interrupted = true;
    }
    process.destroyForcibly();

    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  public static RedisStandaloneSession start() throws IOException {
    return start(Map.of());
  }

  public static RedisStandaloneSession start(Map<String, String> config) throws IOException {
    var effectiveConfig = new LinkedHashMap<String, String>();
    for (var entry : config.entrySet()) {
      var key = entry.getKey();
      var option = key.startsWith("--") ? key : "--" + key;
      requireArgument(
          !option.equals("--bind") && !option.equals("--port"), "unexpected option: %s", option);
      effectiveConfig.put(option, entry.getValue());
    }
    effectiveConfig.put("--bind", LOOPBACK_ADDRESS);

    boolean deleteDirectoryOnClosure;
    Path directory;
    if (!effectiveConfig.containsKey("--dir")) {
      directory = Files.createTempDirectory(RedisStandaloneSession.class.getSimpleName());
      effectiveConfig.put("--dir", directory.toString());
      deleteDirectoryOnClosure = true;
    } else {
      directory = Path.of(effectiveConfig.get("--dir"));
      Files.createDirectories(directory);
      deleteDirectoryOnClosure = false;
    }

    effectiveConfig.putIfAbsent("--loglevel", "verbose");

    // Instead of having two different destinations for process output (stdout, logfile), let's
    // redirect all output to the log file instead of using the --logfile option explicitly (see
    // tryStart).
    var logFileString = effectiveConfig.remove("--logfile");
    var logFile =
        logFileString != null
            ? directory.resolve(logFileString)
            : Files.createTempFile(directory, RedisStandaloneSession.class.getSimpleName(), ".log");
    for (int retriesLeft = MASTER_SERVER_START_RETRIES; retriesLeft > 0; retriesLeft--) {
      int port = ThreadLocalRandom.current().nextInt(SERVER_PORT_START, SERVER_PORT_END);
      var process = tryStart(effectiveConfig, port, logFile);
      if (process != null) {
        return new RedisStandaloneSession(
            port, process, directory, deleteDirectoryOnClosure, logFile);
      }

      String logString;
      try {
        logString = Files.readString(logFile);
      } catch (IOException e) {
        logString = "<problem reading log: " + e.getMessage() + ">";
      }
      var lambdaLogString = logString;
      int lambdaRetriesLeft = retriesLeft;
      logger.log(
          Level.WARNING,
          () ->
              "Unable to start an operable redis server ("
                  + (lambdaRetriesLeft - 1)
                  + " retries left). Have a look at the attached log.\n"
                  + lambdaLogString);
      Files.delete(logFile);
    }

    throw new IOException("Unable to start an operable redis server");
  }

  private static @Nullable Process tryStart(Map<String, String> config, int port, Path logFile)
      throws IOException {
    var command =
        new ArrayList<>(List.of(RedisSupport.SERVER_CMD, "--port", Integer.toString(port)));
    command.addAll(
        config.entrySet().stream()
            .flatMap(entry -> Stream.of(entry.getKey(), entry.getValue()))
            .collect(Collectors.toUnmodifiableList()));
    var process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(logFile != null ? Redirect.to(logFile.toFile()) : Redirect.PIPE)
            .start();
    if (isHealthy(process, logFile, port)) {
      return process;
    }
    terminate(process);
    return null;
  }

  private static boolean isHealthy(Process process, Path logFile, int port) throws IOException {
    try (var reader =
        new BufferedReader(
            new InputStreamReader(new ActiveProcessFileInputStream(process, logFile), UTF_8))) {
      while (true) {
        String line;
        if (!process.isAlive() || (line = reader.readLine()) == null) {
          // The process was terminated prematurely, probably due to error.
          return false;
        }
        if (line.contains("Ready to accept connections")) {
          break;
        }
        if (line.contains("Could not create server")) {
          return false;
        }
      }
    }

    // Ping the server to make sure it's operating properly.
    try (var client = RedisClient.create(RedisURI.create(LOOPBACK_ADDRESS, port));
        var connection = client.connect()) {
      connection.sync().ping();
    } catch (RedisException ignored) {
      return false;
    }
    return true;
  }

  /**
   * An {@code InputStream} that doesn't report EOF immediately when reading from a file that's
   * being written to by an active process.
   */
  private static final class ActiveProcessFileInputStream extends InputStream {
    private static final int EOF_SPIN_WAIT_MILLIS = 50;
    private static final int EOF_MAX_RETRIES = 20;

    private final Process process;
    private final InputStream in;

    ActiveProcessFileInputStream(Process process, Path outputFile) throws IOException {
      this.process = process;
      this.in = Files.newInputStream(outputFile);
    }

    @Override
    public int read() throws IOException {
      var b = new byte[1];
      int read = read(b);
      return read > 0 ? b[0] : read;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      for (int retriesLeft = EOF_MAX_RETRIES; retriesLeft > 0; retriesLeft--) {
        int read = in.read(b, off, len);
        if (read > 0 || !process.isAlive()) {
          return read;
        }

        // Give the process a chance to write more.
        try {
          TimeUnit.MILLISECONDS.sleep(EOF_SPIN_WAIT_MILLIS);
        } catch (InterruptedException e) {
          throw (IOException) new InterruptedIOException().initCause(e);
        }
      }

      if (!process.isAlive()) {
        return -1;
      }
      throw new IOException("timed out while waiting for process output");
    }

    @Override
    public void close() throws IOException {
      in.close();
    }
  }
}
