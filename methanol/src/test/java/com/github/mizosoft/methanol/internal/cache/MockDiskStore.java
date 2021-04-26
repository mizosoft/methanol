/*
 * Copyright (c) 2019-2021 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static com.github.mizosoft.methanol.internal.cache.DiskStore.ENTRY_DESCRIPTOR_SIZE;
import static com.github.mizosoft.methanol.internal.cache.DiskStore.ENTRY_FILE_SUFFIX;
import static com.github.mizosoft.methanol.internal.cache.DiskStore.ENTRY_MAGIC;
import static com.github.mizosoft.methanol.internal.cache.DiskStore.ENTRY_TRAILER_SIZE;
import static com.github.mizosoft.methanol.internal.cache.DiskStore.INDEX_FILENAME;
import static com.github.mizosoft.methanol.internal.cache.DiskStore.INDEX_HEADER_SIZE;
import static com.github.mizosoft.methanol.internal.cache.DiskStore.INDEX_MAGIC;
import static com.github.mizosoft.methanol.internal.cache.DiskStore.LOCK_FILENAME;
import static com.github.mizosoft.methanol.internal.cache.DiskStore.RIP_FILE_PREFIX;
import static com.github.mizosoft.methanol.internal.cache.DiskStore.STORE_VERSION;
import static com.github.mizosoft.methanol.internal.cache.DiskStore.TEMP_ENTRY_FILE_SUFFIX;
import static com.github.mizosoft.methanol.internal.cache.DiskStore.TEMP_INDEX_FILENAME;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.sizeOf;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.function.Predicate.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.mizosoft.methanol.internal.cache.DiskStore.Hash;
import com.github.mizosoft.methanol.internal.cache.DiskStore.Hasher;
import com.github.mizosoft.methanol.testing.StoreContext;
import com.github.mizosoft.methanol.testutils.MockClock;
import com.github.mizosoft.methanol.testutils.TestUtils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Allows reading or writing DiskStore entries directly from/to disk. */
final class MockDiskStore {
  private final Path directory;
  private final Path indexFile;
  private final Path tempIndexFile;
  private final Path lockFile;
  private final Hasher hasher;
  private final int appVersion;
  private final MockClock clock;
  private final Index workIndex;

  MockDiskStore(StoreContext context) {
    directory = context.directory();
    indexFile = directory.resolve(INDEX_FILENAME);
    tempIndexFile = directory.resolve(TEMP_INDEX_FILENAME);
    lockFile = directory.resolve(LOCK_FILENAME);
    hasher = context.hasher();
    appVersion = context.config().appVersion();
    workIndex = new Index(appVersion);
    clock = context.clock();
  }

  boolean indexFileExists() {
    return Files.exists(indexFile);
  }

  boolean lockFileExists() {
    return Files.exists(lockFile);
  }

  boolean entryFileExists(String key) {
    return Files.exists(entryFile(key));
  }

  boolean dirtyEntryFileExists(String key) {
    return Files.exists(tempEntryFile(key));
  }

  Index copyWorkIndex() {
    return new Index(workIndex.encode());
  }

  Index readIndex() throws IOException {
    assertTrue(Files.exists(indexFile), "the index file doesn't exist");
    return new Index(ByteBuffer.wrap(Files.readAllBytes(indexFile)));
  }

  void writeIndex(Index index) throws IOException {
    Files.write(indexFile, TestUtils.toByteArray(index.encode()));
  }

  void writeIndex(Index index, IndexCorruptionMode corruptionMode) throws IOException {
    Files.write(indexFile, TestUtils.toByteArray(corruptionMode.corrupt(index, appVersion)));
  }

  void writeWorkIndex() throws IOException {
    writeIndex(workIndex);
  }

  DiskEntry readEntry(String key) throws IOException {
    return new DiskEntry(ByteBuffer.wrap(Files.readAllBytes(entryFile(key))));
  }

  void write(DiskEntry entry, Instant lastUsed) throws IOException {
    Files.write(entryFile(entry.key), TestUtils.toByteArray(entry.encode()));
    workIndex.put(entry.toIndexEntry(hasher, lastUsed));
  }

  void write(String key, String data, String metadata, Instant lastUsed) throws IOException {
    write(new DiskEntry(key, metadata, data, appVersion), lastUsed);
  }

  void write(String key, String data, String metadata) throws IOException {
    write(key, metadata, data, clock.instant());
  }

  void write(String key, String data, String metadata, EntryCorruptionMode corruptionMode)
      throws IOException {
    var entry = new DiskEntry(key, metadata, data, appVersion);
    Files.write(entryFile(key), TestUtils.toByteArray(corruptionMode.corrupt(entry, appVersion)));
    workIndex.put(entry.toIndexEntry(hasher, clock.instant()));
  }

  void writeDirtyTruncated(String key, String metadata, String data) throws IOException {
    writeDirty(new DiskEntry(key, metadata, data, appVersion), true);
  }

  void writeDirty(String key, String metadata, String data) throws IOException {
    writeDirty(new DiskEntry(key, metadata, data, appVersion), false);
  }

  void writeDirty(DiskEntry entry, boolean truncate) throws IOException {
    Files.write(
        tempEntryFile(entry.key),
        TestUtils.toByteArray(truncate ? truncate(entry.encode()) : entry.encode()));
  }

  void delete(String key) throws IOException {
    assertTrue(Files.exists(entryFile(key)));
    Files.delete(entryFile(key));
  }

  void delete() throws IOException {
    try (var stream = Files.newDirectoryStream(directory)) {
      for (var file : stream) {
        Files.delete(file);
      }
    }
  }

  void assertEntryEquals(String key, String metadata, String data) throws IOException {
    assertTrue(entryFileExists(key), "entry file for <" + key + "> doesn't exist");

    var entry = readEntry(key);
    assertEquals(key, entry.key);
    assertEquals(metadata, entry.metadata);
    assertEquals(data, entry.data);
  }

  // <key, last-used, size> tuples
  void assertIndexEquals(Object... content) throws IOException {
    requireArgument(content.length % 3 == 0, "invalid content");

    var index = readIndex();
    assertEquals(INDEX_MAGIC, index.magic);
    assertEquals(STORE_VERSION, index.storeVersion);
    assertEquals(appVersion, index.appVersion);

    var coveredHashes = new HashSet<Hash>();
    for (int i = 0; i < content.length; i += 3) {
      var key = content[i].toString();
      var hash = hasher.hash(key);
      var indexEntry = index.get(hasher.hash(key));
      assertNotNull(indexEntry, "entry with key <" + key + "> is missing from the index");
      assertEquals(content[i + 1], indexEntry.lastUsed, key);
      assertEquals(content[i + 2], indexEntry.size, key);

      coveredHashes.add(hash);
    }

    var remainingHashes = new HashSet<>(index.entries.keySet());
    remainingHashes.removeAll(coveredHashes);
    assertTrue(remainingHashes.isEmpty(), "index contains unexpected entries: " + remainingHashes);
  }

  void assertEmptyIndex() throws IOException {
    assertIndexEquals(/* no entries */ );
  }

  void assertHasNoEntriesOnDisk() throws IOException {
    try (var stream = Files.list(directory)) {
      var entryFiles = stream.filter(not(this::isNonEntryFile)).collect(Collectors.toSet());
      assertTrue(entryFiles.isEmpty(), entryFiles::toString);
    }
  }

  private boolean isNonEntryFile(Path file) {
    return file.equals(indexFile)
        || file.equals(tempIndexFile)
        || file.equals(lockFile)
        || file.getFileName().toString().startsWith(RIP_FILE_PREFIX);
  }

  Path entryFile(String key) {
    return directory.resolve(hasher.hash(key).toHexString() + ENTRY_FILE_SUFFIX);
  }

  Path tempEntryFile(String key) {
    return directory.resolve(hasher.hash(key).toHexString() + TEMP_ENTRY_FILE_SUFFIX);
  }

  private static ByteBuffer truncate(ByteBuffer buffer) {
    int truncatedLimit = (int) (buffer.limit() - buffer.limit() * 0.2);
    return buffer.limit(
        Math.min(truncatedLimit, buffer.limit() - 1)); // Make sure something is truncated
  }

  static final class Index {
    final Map<Hash, IndexEntry> entries = new LinkedHashMap<>();

    long magic;
    int storeVersion;
    int appVersion;

    Index(int appVersion) {
      this.appVersion = appVersion;
      magic = INDEX_MAGIC;
      storeVersion = STORE_VERSION;
    }

    Index(ByteBuffer buffer) {
      magic = buffer.getLong();
      storeVersion = buffer.getInt();
      appVersion = buffer.getInt();
      long entryCount = buffer.getLong();
      for (int i = 0; i < entryCount; i++) {
        var entry = new IndexEntry(buffer);
        entries.put(entry.hash, entry);
      }
    }

    ByteBuffer encode() {
      var buffer =
          ByteBuffer.allocate(INDEX_HEADER_SIZE + entries.size() * ENTRY_DESCRIPTOR_SIZE)
              .putLong(magic)
              .putInt(storeVersion)
              .putInt(appVersion)
              .putLong(entries.size());
      entries.values().forEach(entry -> entry.writeTo(buffer));
      return buffer.flip();
    }

    IndexEntry get(Hash hash) {
      return entries.get(hash);
    }

    void put(IndexEntry entry) {
      entries.put(entry.hash, entry);
    }

    boolean contains(Hash hash) {
      return entries.containsKey(hash);
    }
  }

  static final class IndexEntry {
    final Hash hash;
    final Instant lastUsed;
    final long size;

    IndexEntry(Hash hash, Instant lastUsed, long size) {
      this.hash = hash;

      this.lastUsed = lastUsed;
      this.size = size;
    }

    IndexEntry(ByteBuffer buffer) {
      hash = new Hash(buffer);
      lastUsed = Instant.ofEpochMilli(buffer.getLong());
      size = buffer.getLong();
    }

    void writeTo(ByteBuffer buffer) {
      hash.writeTo(buffer);
      buffer.putLong(lastUsed.toEpochMilli());
      buffer.putLong(size);
    }
  }

  static final class DiskEntry {
    final String key;
    final String metadata;
    final String data;

    long magic;
    int storeVersion;
    int appVersion;

    DiskEntry(String key, String metadata, String data, int appVersion) {
      this.key = key;
      this.metadata = metadata;
      this.data = data;

      magic = ENTRY_MAGIC;
      storeVersion = STORE_VERSION;
      this.appVersion = appVersion;
    }

    DiskEntry(ByteBuffer buffer) {
      // <data>
      // <key>
      // <metadata>
      // <magic>
      // <storeVersion>
      // <appVersion>
      // <key-size>
      // <metadata-size>
      // <data-size>

      buffer.mark();
      buffer.position(buffer.limit() - ENTRY_TRAILER_SIZE);
      magic = buffer.getLong();
      storeVersion = buffer.getInt();
      appVersion = buffer.getInt();

      int keySize = buffer.getInt();
      int metadataSize = buffer.getInt();
      int dataSize = (int) buffer.getLong();

      buffer.reset();
      metadata =
          UTF_8
              .decode(buffer.position(dataSize + keySize).limit(dataSize + keySize + metadataSize))
              .toString();
      key = UTF_8.decode(buffer.position(dataSize).limit(dataSize + keySize)).toString();
      data = UTF_8.decode(buffer.position(0).limit(dataSize)).toString();
    }

    IndexEntry toIndexEntry(Hasher hasher, Instant lastUsed) {
      return new IndexEntry(hasher.hash(key), lastUsed, sizeOf(metadata, data));
    }

    ByteBuffer encode() {
      var dataBytes = UTF_8.encode(data);
      var keyBytes = UTF_8.encode(key);
      var metadataBytes = UTF_8.encode(metadata);
      return ByteBuffer.allocate(
              dataBytes.remaining()
                  + keyBytes.remaining()
                  + metadataBytes.remaining()
                  + ENTRY_TRAILER_SIZE)
          .put(dataBytes)
          .put(keyBytes)
          .put(metadataBytes)
          .putLong(magic)
          .putInt(storeVersion)
          .putInt(appVersion)
          .putInt(keyBytes.rewind().remaining())
          .putInt(metadataBytes.rewind().remaining())
          .putLong(dataBytes.rewind().remaining())
          .flip();
    }
  }

  enum IndexCorruptionMode {
    MAGIC {
      @Override
      ByteBuffer corrupt(Index index, int appVersion) {
        index.magic = 1;
        return index.encode();
      }
    },
    STORE_VERSION {
      @Override
      ByteBuffer corrupt(Index index, int appVersion) {
        index.storeVersion = DiskStore.STORE_VERSION + 1;
        return index.encode();
      }
    },
    APP_VERSION {
      @Override
      ByteBuffer corrupt(Index index, int appVersion) {
        index.appVersion = appVersion + 1;
        return index.encode();
      }
    },
    TRUNCATED {
      @Override
      ByteBuffer corrupt(Index index, int appVersion) {
        return truncate(index.encode());
      }
    };

    abstract ByteBuffer corrupt(Index index, int appVersion);
  }

  enum EntryCorruptionMode {
    MAGIC {
      @Override
      ByteBuffer corrupt(DiskEntry entry, int appVersion) {
        entry.magic = 1;
        return entry.encode();
      }
    },
    STORE_VERSION {
      @Override
      ByteBuffer corrupt(DiskEntry entry, int appVersion) {
        entry.storeVersion = DiskStore.STORE_VERSION + 1;
        return entry.encode();
      }
    },
    APP_VERSION {
      @Override
      ByteBuffer corrupt(DiskEntry entry, int appVersion) {
        entry.appVersion = appVersion + 1;
        return entry.encode();
      }
    },
    TRUNCATE {
      @Override
      ByteBuffer corrupt(DiskEntry entry, int appVersion) {
        return truncate(entry.encode());
      }
    };

    abstract ByteBuffer corrupt(DiskEntry entry, int appVersion);
  }
}
