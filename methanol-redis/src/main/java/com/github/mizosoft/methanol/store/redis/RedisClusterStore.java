/*
 * Copyright (c) 2023 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.store.redis;

import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static com.github.mizosoft.methanol.internal.Validate.requireState;

import io.lettuce.core.RedisException;
import io.lettuce.core.api.sync.RedisHashCommands;
import io.lettuce.core.api.sync.RedisKeyCommands;
import io.lettuce.core.api.sync.RedisScriptingCommands;
import io.lettuce.core.api.sync.RedisStringCommands;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.models.partitions.Partitions;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode.NodeFlag;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** A {@code Store} implementation backed by a Redis Cluster. */
class RedisClusterStore
    extends AbstractRedisStore<StatefulRedisClusterConnection<String, ByteBuffer>> {
  RedisClusterStore(
      StatefulRedisClusterConnection<String, ByteBuffer> connection,
      RedisConnectionProvider<StatefulRedisClusterConnection<String, ByteBuffer>>
          connectionProvider,
      int editorLockTtlSeconds,
      int staleEntryTtlSeconds,
      int appVersion) {
    super(
        connection, connectionProvider, editorLockTtlSeconds, staleEntryTtlSeconds, appVersion, "");
  }

  @SuppressWarnings("unchecked")
  @Override
  <
          CMD extends
              RedisHashCommands<String, ByteBuffer> & RedisScriptingCommands<String, ByteBuffer>
                  & RedisKeyCommands<String, ByteBuffer> & RedisStringCommands<String, ByteBuffer>>
      CMD commands() {
    return (CMD) connection.sync();
  }

  @Override
  boolean removeAllEntries(List<String> entryKeys) {
    boolean removedAny = false;
    for (var entryKey : entryKeys) {
      removedAny |= removeEntry(entryKey, ANY_ENTRY_VERSION);
    }
    return removedAny;
  }

  @Override
  public Iterator<Viewer> iterator() {
    return new ClusterScanningIterator(connection.getPartitions());
  }

  /** An iterator that scans for entries on all master cluster nodes. */
  // TODO this can be more sophisticated. We can exploit replicas for scanning, and never scan more
  //      than one replica related to the same master to avoid useless duplication.
  private final class ClusterScanningIterator implements Iterator<Viewer> {
    /**
     * The set of keys seen so far, tracked to avoid returning duplicate entries. In addition to the
     * possibility of getting duplicate entries from the same node (see {@link
     * ScanningViewerIterator}), multiple nodes related to the same keyspace (master & replicas) can
     * be scanned if the client's topology isn't up-to-date.
     */
    private final Set<String> seenKeys = new HashSet<>();

    private final Iterator<RedisClusterNode> nodeIterator;

    /** The ScanningViewerIterator for the current cluster node. */
    private @MonotonicNonNull ScanningViewerIterator currentScanningIterator;

    private boolean finished;

    ClusterScanningIterator(Partitions partitions) {
      // Make sure to get an unmodifiable snapshot (of masters) as partitions can be mutated by
      // topology refreshes.
      this.nodeIterator =
          partitions.stream()
              .filter(node -> node.is(NodeFlag.UPSTREAM))
              .collect(Collectors.toUnmodifiableList())
              .iterator();
    }

    @Override
    @EnsuresNonNullIf(expression = "currentScanningIterator", result = true)
    public boolean hasNext() {
      var scanningIter = currentScanningIterator;
      return (scanningIter != null && scanningIter.hasNext()) || findNext();
    }

    @Override
    public Viewer next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      var scanningIter = castNonNull(currentScanningIterator);
      return scanningIter.next();
    }

    @Override
    public void remove() {
      requireState(currentScanningIterator != null, "next() must be called before remove()");
      var scanningIter = castNonNull(currentScanningIterator);
      scanningIter.remove(); // Fails if not preceded by a call to next().
    }

    @EnsuresNonNullIf(expression = "currentScanningIterator", result = true)
    private boolean findNext() {
      while (true) {
        if (finished) {
          return false;
        }

        var scanningIter = currentScanningIterator;
        if (scanningIter != null && scanningIter.hasNext()) {
          return true;
        } else if (nodeIterator.hasNext()) {
          var partition = nodeIterator.next();
          RedisScriptingCommands<String, ByteBuffer> nodeCommands;
          try {
            nodeCommands = connection.sync().getConnection(partition.getNodeId());
          } catch (RedisException e) {
            logger.log(Level.WARNING, "Exception thrown when connecting to cluster node", e);
            finished = true;
            return false;
          }

          var nextScanningIter =
              new ScanningViewerIterator(toEntryKey("*"), seenKeys, nodeCommands);
          if (nextScanningIter.hasNext()) {
            currentScanningIterator = nextScanningIter;
            return true;
          }
        } else {
          finished = true;
          return false;
        }
      }
    }
  }
}
