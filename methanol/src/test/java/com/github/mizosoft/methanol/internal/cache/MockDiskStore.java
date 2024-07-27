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

package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static com.github.mizosoft.methanol.internal.cache.DiskStore.ENTRY_FILE_SUFFIX;
import static com.github.mizosoft.methanol.internal.cache.DiskStore.ENTRY_MAGIC;
import static com.github.mizosoft.methanol.internal.cache.DiskStore.ENTRY_TRAILER_SIZE;
import static com.github.mizosoft.methanol.internal.cache.DiskStore.INDEX_ENTRY_SIZE;
import static com.github.mizosoft.methanol.internal.cache.DiskStore.INDEX_FILENAME;
import static com.github.mizosoft.methanol.internal.cache.DiskStore.INDEX_HEADER_SIZE;
import static com.github.mizosoft.methanol.internal.cache.DiskStore.INDEX_MAGIC;
import static com.github.mizosoft.methanol.internal.cache.DiskStore.ISOLATED_FILE_PREFIX;
import static com.github.mizosoft.methanol.internal.cache.DiskStore.LOCK_FILENAME;
import static com.github.mizosoft.methanol.internal.cache.DiskStore.STORE_VERSION;
import static com.github.mizosoft.methanol.internal.cache.DiskStore.TEMP_ENTRY_FILE_SUFFIX;
import static com.github.mizosoft.methanol.internal.cache.DiskStore.TEMP_INDEX_FILENAME;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.sizeOf;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.function.Predicate.not;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.internal.cache.DiskStore.Hash;
import com.github.mizosoft.methanol.internal.cache.DiskStore.Hasher;
import com.github.mizosoft.methanol.testing.TestUtils;
import com.github.mizosoft.methanol.testing.store.DiskStoreContext;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32C;

/** An object that provides direct access to the underlying storage of a {@link DiskStore}. */
final class MockDiskStore {
  private final Path directory;
  private final Path indexFile;
  private final Path tempIndexFile;
  private final Path lockFile;
  private final Hasher hasher;
  private final int appVersion;
  private final Index index;
  private final AtomicLong lruClock = new AtomicLong();

  MockDiskStore(DiskStoreContext context) {
    directory = context.directory();
    indexFile = directory.resolve(INDEX_FILENAME);
    tempIndexFile = directory.resolve(TEMP_INDEX_FILENAME);
    lockFile = directory.resolve(LOCK_FILENAME);
    hasher = context.hasher();
    appVersion = context.config().appVersion();
    index = new Index(appVersion);
  }

  Index index() {
    return index;
  }

  Index readIndex() throws IOException {
    return new Index(ByteBuffer.wrap(Files.readAllBytes(indexFile)));
  }

  void writeIndex(Index index) throws IOException {
    Files.write(indexFile, TestUtils.toByteArray(index.encode()));
  }

  void writeIndex(Index index, IndexCorruptionMode corruptionMode) throws IOException {
    Files.write(indexFile, TestUtils.toByteArray(corruptionMode.corrupt(index.copy())));
  }

  void writeIndex() throws IOException {
    writeIndex(index);
  }

  DiskEntry readEntry(String key) throws IOException {
    return new DiskEntry(toEntryPath(key));
  }

  void write(DiskEntry entry, long lastUsed) throws IOException {
    Files.write(toEntryPath(entry.key), TestUtils.toByteArray(entry.encode()));
    index.put(entry.toIndexEntry(hasher, lastUsed));
  }

  void write(String key, String data, String metadata, long lastUsed) throws IOException {
    write(new DiskEntry(key, metadata, data, appVersion), lastUsed);
  }

  void write(String key, String data, String metadata) throws IOException {
    write(key, metadata, data, lruClock.getAndIncrement());
  }

  void write(String key, String data, String metadata, EntryCorruptionMode corruptionMode)
      throws IOException {
    var entry = new DiskEntry(key, metadata, data, appVersion);
    Files.write(toEntryPath(key), TestUtils.toByteArray(corruptionMode.corrupt(entry)));
    index.put(entry.toIndexEntry(hasher, lruClock.getAndIncrement()));
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

  void deleteEntry(String key) throws IOException {
    Files.delete(toEntryPath(key));
  }

  void delete() throws IOException {
    try (var stream = Files.newDirectoryStream(directory)) {
      for (var file : stream) {
        Files.delete(file);
      }
    }
  }

  void assertEntryFileExists(String key) {
    assertThat(toEntryPath(key))
        .withFailMessage("expected entry file for <%s> to exist", key)
        .exists();
  }

  void assertEntryFileDoesNotExist(String key) {
    assertThat(toEntryPath(key))
        .withFailMessage("expected entry file for <%s> to not exist", key)
        .doesNotExist();
  }

  void assertDirtyEntryFileExists(String key) {
    assertThat(tempEntryFile(key))
        .withFailMessage("expected dirty entry file for <%s> to exist", key)
        .exists();
  }

  void assertDirtyEntryFileDoesNotExist(String key) {
    assertThat(tempEntryFile(key))
        .withFailMessage("expected dirty entry file for <%s> to not exist", key)
        .doesNotExist();
  }

  void assertEntryEquals(String key, String metadata, String data) throws IOException {
    assertEntryFileExists(key);

    var entry = readEntry(key);
    assertThat(entry.key).isEqualTo(key);
    assertThat(entry.metadata).isEqualTo(metadata);
    assertThat(entry.data).isEqualTo(data);
  }

  // <key, last-used, size> tuples
  void assertIndexEquals(Object... content) throws IOException {
    requireArgument(content.length % 3 == 0, "invalid content");

    var index = readIndex();
    assertThat(index.magic).isEqualTo(INDEX_MAGIC);
    assertThat(index.storeVersion).isEqualTo(STORE_VERSION);
    assertThat(index.appVersion).isEqualTo(appVersion);

    var coveredHashes = new HashSet<Hash>();
    for (int i = 0; i < content.length; i += 3) {
      var key = content[i].toString();
      var hash = hasher.hash(key);
      var indexEntry = index.get(hasher.hash(key));
      assertThat(indexEntry)
          .withFailMessage("entry with key <%s> is missing from the index", key)
          .isNotNull();
      assertThat(indexEntry.lastUsed).as(key).isEqualTo(content[i + 1]);
      assertThat(indexEntry.size).as(key).isEqualTo(content[i + 2]);

      coveredHashes.add(hash);
    }

    var remainingHashes = new HashSet<>(index.entries.keySet());
    remainingHashes.removeAll(coveredHashes);
    assertThat(remainingHashes).withFailMessage("index contains unexpected entries").isEmpty();
  }

  void assertEmptyIndex() throws IOException {
    assertIndexEquals(/* no entries */ );
  }

  void assertHasNoEntriesOnDisk() throws IOException {
    try (var stream = Files.list(directory)) {
      assertThat(stream).filteredOn(not(this::isNonEntryFile)).isEmpty();
    }
  }

  private boolean isNonEntryFile(Path file) {
    return file.equals(indexFile)
        || file.equals(tempIndexFile)
        || file.equals(lockFile)
        || file.getFileName().toString().startsWith(ISOLATED_FILE_PREFIX);
  }

  Path indexFile() {
    return indexFile;
  }

  Path lockFile() {
    return lockFile;
  }

  Path toEntryPath(String key) {
    return directory.resolve(hasher.hash(key).toHexString() + ENTRY_FILE_SUFFIX);
  }

  Path tempEntryFile(String key) {
    return directory.resolve(hasher.hash(key).toHexString() + TEMP_ENTRY_FILE_SUFFIX);
  }

  private static ByteBuffer truncate(ByteBuffer buffer) {
    int truncatedLimit = (int) (buffer.limit() - buffer.limit() * 0.2);
    return buffer.limit(
        Math.min(truncatedLimit, buffer.limit() - 1)); // Make sure something is truncated.
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
          ByteBuffer.allocate(INDEX_HEADER_SIZE + entries.size() * INDEX_ENTRY_SIZE)
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

    Index copy() {
      return new Index(encode());
    }
  }

  static final class IndexEntry {
    final Hash hash;
    final long lastUsed;
    final long size;

    IndexEntry(Hash hash, long lastUsed, long size) {
      this.hash = hash;
      this.lastUsed = lastUsed;
      this.size = size;
    }

    IndexEntry(ByteBuffer buffer) {
      hash = new Hash(buffer);
      lastUsed = buffer.getLong();
      size = buffer.getLong();
    }

    void writeTo(ByteBuffer buffer) {
      hash.writeTo(buffer);
      buffer.putLong(lastUsed);
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
    int keySize;
    int metadataSize;
    int dataSize;

    DiskEntry(String key, String metadata, String data, int appVersion) {
      this.key = key;
      this.metadata = metadata;
      this.data = data;
      this.magic = ENTRY_MAGIC;
      this.storeVersion = STORE_VERSION;
      this.appVersion = appVersion;
      this.keySize = key.getBytes(UTF_8).length;
      this.metadataSize = metadata.getBytes(UTF_8).length;
      this.dataSize = data.getBytes(UTF_8).length;
    }

    DiskEntry(Path path) throws IOException {
      // <data>
      // <key>
      // <metadata>
      // <magic>
      // <storeVersion>
      // <appVersion>
      // <key-size>
      // <metadata-size>
      // <data-size>
      // <data-crc32c>
      // <epilogue-crc32c>

      var content = ByteBuffer.wrap(Files.readAllBytes(path));
      content.position(content.limit() - ENTRY_TRAILER_SIZE);
      magic = content.getLong();
      storeVersion = content.getInt();
      appVersion = content.getInt();
      keySize = content.getInt();
      metadataSize = content.getInt();
      dataSize = (int) content.getLong();
      int dataCrc32c = content.getInt();
      int epilogueCrc32c = content.getInt();
      key = UTF_8.decode(content.position(dataSize).limit(dataSize + keySize)).toString();
      metadata = UTF_8.decode(content.limit(dataSize + keySize + metadataSize)).toString();
      var dataBytes = TestUtils.toByteArray(content.rewind().limit(dataSize));
      data = new String(dataBytes, UTF_8);

      var crc32c = new CRC32C();
      crc32c.update(dataBytes);
      assertThat(dataCrc32c)
          .withFailMessage(() -> "Mismatching data CRC32C")
          .isEqualTo((int) crc32c.getValue());

      crc32c.reset();
      crc32c.update(content.limit(content.capacity() - Integer.BYTES).position(dataSize));
      assertThat(epilogueCrc32c)
          .withFailMessage(() -> "Mismatching epilogue CRC32C")
          .isEqualTo((int) crc32c.getValue());
    }

    IndexEntry toIndexEntry(Hasher hasher, long lastUsed) {
      return new IndexEntry(hasher.hash(key), lastUsed, sizeOf(metadata, data));
    }

    ByteBuffer encode() {
      var dataBytes = data.getBytes(UTF_8);
      var keyBytes = key.getBytes(UTF_8);
      var metadataBytes = metadata.getBytes(UTF_8);
      var crc32c = new CRC32C();
      crc32c.update(dataBytes);
      var buffer =
          ByteBuffer.allocate(
                  dataBytes.length + keyBytes.length + metadataBytes.length + ENTRY_TRAILER_SIZE)
              .put(dataBytes)
              .put(keyBytes)
              .put(metadataBytes)
              .putLong(magic)
              .putInt(storeVersion)
              .putInt(appVersion)
              .putInt(keyBytes.length)
              .putInt(metadataBytes.length)
              .putLong(dataBytes.length)
              .putInt((int) crc32c.getValue());
      crc32c.reset();
      crc32c.update(buffer.flip().position(dataBytes.length));
      return buffer.limit(buffer.capacity()).putInt((int) crc32c.getValue()).flip();
    }
  }

  enum IndexCorruptionMode {
    MAGIC {
      @Override
      ByteBuffer corrupt(Index index) {
        index.magic = 1;
        return index.encode();
      }
    },
    STORE_VERSION {
      @Override
      ByteBuffer corrupt(Index index) {
        index.storeVersion++;
        return index.encode();
      }
    },
    APP_VERSION {
      @Override
      ByteBuffer corrupt(Index index) {
        index.appVersion++;
        return index.encode();
      }
    },
    TRUNCATED {
      @Override
      ByteBuffer corrupt(Index index) {
        return truncate(index.encode());
      }
    };

    abstract ByteBuffer corrupt(Index index);
  }

  enum EntryCorruptionMode {
    MAGIC {
      @Override
      ByteBuffer corrupt(DiskEntry entry) {
        entry.magic = 1;
        return entry.encode();
      }
    },
    STORE_VERSION {
      @Override
      ByteBuffer corrupt(DiskEntry entry) {
        entry.storeVersion++;
        return entry.encode();
      }
    },
    APP_VERSION {
      @Override
      ByteBuffer corrupt(DiskEntry entry) {
        entry.appVersion++;
        return entry.encode();
      }
    },
    TRUNCATE {
      @Override
      ByteBuffer corrupt(DiskEntry entry) {
        return truncate(entry.encode());
      }
    },
    KEY {
      @Override
      ByteBuffer corrupt(DiskEntry entry) {
        var content = entry.encode();
        var random = TestUtils.newRandom();
        content.put(entry.dataSize + random.nextInt(entry.keySize), (byte) random.nextInt());
        return content;
      }
    },
    METADATA {
      @Override
      ByteBuffer corrupt(DiskEntry entry) {
        var content = entry.encode();
        var random = TestUtils.newRandom();
        content.put(
            entry.dataSize + entry.keySize + random.nextInt(entry.metadataSize),
            (byte) random.nextInt());
        return content;
      }
    },
    DATA {
      @Override
      ByteBuffer corrupt(DiskEntry entry) {
        var content = entry.encode();
        var random = TestUtils.newRandom();
        content.put(random.nextInt(entry.dataSize), (byte) random.nextInt());
        return content;
      }
    };

    abstract ByteBuffer corrupt(DiskEntry entry);
  }
}
