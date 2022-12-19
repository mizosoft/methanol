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

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisReadOnlyException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode.NodeFlag;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class LocalRedisCluster implements RedisSession {
  private static final Logger logger = System.getLogger(LocalRedisCluster.class.getName());

  private static final int HEALTH_CHECK_MAX_RETRIES = 10;
  private static final int CLUSTER_JOIN_TIMEOUT_SECONDS = 20;

  private final List<LocalRedisServer> nodes;

  private LocalRedisCluster(List<LocalRedisServer> nodes) {
    this.nodes = List.copyOf(nodes);
  }

  public List<RedisURI> uris() {
    return nodes.stream().map(LocalRedisServer::uri).collect(Collectors.toUnmodifiableList());
  }

  public final List<Path> logFiles() {
    return nodes.stream().map(LocalRedisServer::logFile).collect(Collectors.toUnmodifiableList());
  }

  @Override
  public boolean isHealthy() {
    try (var client = RedisClusterClient.create(uris());
        var connection = client.connect()) {
      checkClusterRouting(connection, connection.getPartitions().size());
      return true;
    } catch (RedisException e) {
      logger.log(Level.WARNING, "unhealthy redis cluster", e);
      return false;
    }
  }

  @Override
  public void reset() {
    try (var client = RedisClusterClient.create(uris());
        var connection = client.connect()) {
      var masters =
          connection.getPartitions().stream()
              .filter(node -> node.is(NodeFlag.UPSTREAM))
              .collect(Collectors.toUnmodifiableList());
      for (var node : masters) {
        try {
          connection.getConnection(node.getNodeId()).sync().flushall();
        } catch (RedisReadOnlyException ignored) {
          // This will be thrown in case the command is sent to a replica, which happens if the
          // connection doesn't have an up-to-date view of the cluster topology and some replicas
          // are still flagged as masters.
        }
      }
    }
  }

  @Override
  public void close() throws IOException {
    closeNodes(nodes);
  }

  private static void closeNodes(List<LocalRedisServer> nodes) throws IOException {
    IOException closeException = null;
    for (var node : nodes) {
      try {
        node.close();
      } catch (IOException e) {
        if (closeException != null) {
          closeException.addSuppressed(e);
        } else {
          closeException = e;
        }
      }
    }

    if (closeException != null) {
      throw closeException;
    }
  }

  public static LocalRedisCluster start(int masterCount, int replicasPerMaster) throws IOException {
    int nodeCount = masterCount + masterCount * replicasPerMaster;
    var nodes = new ArrayList<LocalRedisServer>(nodeCount);
    for (int i = 0; i < nodeCount; i++) {
      try {
        var node =
            LocalRedisServer.start(
                Map.of(
                    "cluster-enabled", "yes",
                    "cluster-node-timeout", "10000",
                    "save", "", // Disable rdb snapshotting.

                    // Having no delay in disk-less sync makes the cluster operable sooner than
                    // otherwise (see checkHealth).
                    "repl-diskless-sync-delay", "0",
                    "loglevel", "verbose"));
        nodes.add(node);
      } catch (IOException e) {
        try {
          closeNodes(nodes);
        } catch (IOException closeEx) {
          e.addSuppressed(closeEx);
        }
        throw e;
      }
    }

    // Join the cluster with redis-cli.
    var command = new ArrayList<String>();
    Collections.addAll(command, RedisSupport.CLI_CMD, "--cluster", "create");
    Collections.addAll(
        command,
        nodes.stream()
            .map(node -> node.uri().getHost() + ":" + node.uri().getPort())
            .toArray(String[]::new));
    Collections.addAll(
        command, "--cluster-replicas", Integer.toString(replicasPerMaster), "--cluster-yes");
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    try {
      if (!process.waitFor(CLUSTER_JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new IOException(
            "redis-cli timed out. Command output: "
                + RedisSupport.dumpRemaining(process.inputReader(UTF_8)));
      }
      if (process.exitValue() != 0) {
        throw new IOException(
            "Non-zero exit code: "
                + process.exitValue()
                + ". Command output: "
                + RedisSupport.dumpRemaining(process.inputReader(UTF_8)));
      }
    } catch (IOException | InterruptedException e) {
      process.destroyForcibly();
      try {
        closeNodes(nodes);
      } catch (IOException closeEx) {
        e.addSuppressed(closeEx);
      }
      if (e instanceof InterruptedException) {
        throw (IOException) new InterruptedIOException().initCause(e);
      }
      throw (IOException) e;
    }

    var cluster = new LocalRedisCluster(nodes);
    try {
      checkHealth(cluster, masterCount);
      return cluster;
    } catch (RedisException | InterruptedException e) {
      try {
        cluster.close();
      } catch (IOException closeEx) {
        e.addSuppressed(closeEx);
      }
      throw new IOException("Can't operate on started cluster", e);
    }
  }

  /**
   * Checks that a newly created cluster operates properly. There's a chance that a newly created
   * cluster responds to key-related commands with CLUSTERDOWN even through the cluster is,
   * apparently, not really down (nodes respond to PINGs & the cluster operates properly after some
   * time). There seems to be a correlation between this temporary downtime and delayed disk-less
   * replication. In any case, we keep retrying key-related commands till the cluster is operable
   * before we return it.
   */
  private static void checkHealth(LocalRedisCluster cluster, int masterCount)
      throws InterruptedException {
    try (var client = RedisClusterClient.create(cluster.uris());
        var connection = client.connect()) {
      int retriesLeft = HEALTH_CHECK_MAX_RETRIES;
      int retryWaitMillis = 200;
      while (true) {
        try {
          checkClusterRouting(connection, masterCount);
          break;
        } catch (RedisCommandExecutionException e) {
          retriesLeft--;
          if (!e.getMessage().contains("CLUSTERDOWN") || retriesLeft <= 0) {
            throw e;
          }

          int lambdaRetriesLeft = retriesLeft;
          logger.log(
              Level.WARNING,
              () ->
                  "Newly created cluster is inoperable ("
                      + lambdaRetriesLeft
                      + " retries left)"
                      + ": "
                      + e.getMessage());
        }

        TimeUnit.MILLISECONDS.sleep(retryWaitMillis);
        retryWaitMillis += 200;
      }
    }
  }

  private static void checkClusterRouting(
      StatefulRedisClusterConnection<String, String> connection, int keyCount) {
    // Make sure we have good routing to (probabilistically) most of the nodes.
    for (int i = 0; i < keyCount; i++) {
      var key = "k" + ThreadLocalRandom.current().nextInt();
      connection.sync().set(key, "v");
      connection.sync().del(key);
    }
  }
}
