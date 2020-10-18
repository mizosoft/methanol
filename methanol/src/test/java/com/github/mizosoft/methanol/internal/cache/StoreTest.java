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

package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.testutils.TestUtils.awaitUninterruptibly;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.testutils.BuffIterator;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

abstract class StoreTest {
  static final String METADATA_1 = "MetadataAlpha";
  static final String DATA_1 = "DataAlpha".repeat(10);

  static final String METADATA_2 = "MetadataBeta";
  static final String DATA_2 = "DataBeta".repeat(10);

  @MonotonicNonNull Store store;
  private final List<AutoCloseable> closeables = new ArrayList<>();

  abstract Store newStore(long maxSize);

  Store newManagedStore() {
    return newManagedStore(Long.MAX_VALUE);
  }

  Store newManagedStore(long maxSize) {
    return newManaged(() -> newStore(maxSize));
  }

  private <T extends AutoCloseable> T newManaged(Callable<T> supplier) {
    T managed;
    try {
      managed = supplier.call();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    closeables.add(managed);
    return managed;
  }

  @BeforeEach
  void setUp() {
    store = newManagedStore();
  }

  @AfterEach
  void tearDown() throws Exception {
    for (var iter = closeables.iterator(); iter.hasNext(); iter.remove()) {
      iter.next().close();
    }
  }

  @Test
  void openCreateRemove() {
    assertNull(store.view("e1"));
    assertNotNull(newManaged(() -> store.edit("e1")));
    assertNull(store.view("e2"));
    assertTrue(store.remove("e1"));
    assertNull(store.view("e1"));
    assertNotNull(newManaged(() -> store.edit("e1")));
    assertNotNull(newManaged(() -> store.edit("e2")));
    store.clear();
    assertNull(store.view("e1"));
    assertNull(store.view("e2"));
  }

  @Test
  void entryWriteRead() {
    writeEntry("e1", METADATA_1, DATA_1);
    assertEntryContains("e1", METADATA_1, DATA_1);
    assertEquals(utf8Length(METADATA_1, DATA_1), store.size());
  }

  @Test
  void entryWriteRead_concurrentViewers() {
    writeEntry("e1", METADATA_1, DATA_1);
    int viewerCount = 10;
    var begin = new CountDownLatch(1);
    var cfs = IntStream.range(0, viewerCount)
        .mapToObj(__ -> CompletableFuture.runAsync(() -> {
          awaitUninterruptibly(begin);
          assertEntryContains("e1", METADATA_1, DATA_1);
        }))
        .collect(Collectors.toUnmodifiableList());
    begin.countDown();
    assertAll(cfs.stream().map(cf -> cf::join));
  }

  @Test
  void writeDataWithoutMetadata() {
    writeEntry("e1", "", DATA_1);
    assertEntryContains("e1", "", DATA_1);
  }

  @Test
  void writeMetadataWithoutData() {
    writeEntry("e1", METADATA_1, "");
    assertEntryContains("e1", METADATA_1, "");
  }

  @Test
  void writeThenRemove() {
    writeEntry("e1", METADATA_1, DATA_1);
    assertEquals(utf8Length(METADATA_1, DATA_1), store.size());
    assertTrue(store.remove("e1"));
    assertEquals(0, store.size());
  }

  @Test
  void writeTwoEntriesThenClear() {
    writeEntry("e1", METADATA_1, DATA_1);
    writeEntry("e2", METADATA_2, DATA_2);
    assertEquals(utf8Length(METADATA_1, DATA_1, METADATA_2, DATA_2), store.size());
    assertTrue(store.remove("e1"));
    assertEquals(utf8Length(METADATA_2, DATA_2), store.size());
    assertTrue(store.remove("e2"));
    assertEquals(0, store.size());
  }

  @Test
  void mutateMetadataBufferAfterPassingToEditor() {
    var metadata = ByteBuffer.wrap("420".getBytes(UTF_8));
    try (var editor = notNull(store.edit("e1"))) {
      editor.metadata(metadata);
      metadata.rewind().put(new byte[] {'6', '9'});
    }
    assertEntryContains("e1", "420", "");
  }

  @Test
  void metadataBufferReturnedFromViewerIsReadOnly() {
    writeEntry("e1", "555", "");
    try (var viewer = notNull(store.view("e1"))) {
      assertThrows(
          ReadOnlyBufferException.class, () -> viewer.metadata().put(new byte[] {'6', '9'}));
    }
  }

  @Test
  void editTwice() {
    writeEntry("e1", METADATA_1, DATA_1);
    writeEntry("e1", METADATA_2, DATA_2);
    assertEntryContains("e1", METADATA_2, DATA_2);
  }

  @Test
  void discardEdit() {
    try (var editor = notNull(store.edit("e1"))) {
      writeEntry(editor, METADATA_1, DATA_1);
      editor.discard();
    }
    assertNull(store.view("e1"));
    assertEquals(0, store.size());
    assertFalse(store.remove("e1")); // Entry shouldn't be present
  }

  @Test
  void discardSecondEdit() {
    writeEntry("e1", METADATA_1, DATA_1);
    try (var editor = notNull(store.edit("e1"))) {
      writeEntry(editor, METADATA_2, DATA_2);
      editor.discard();
    }
    // Second edit data is discarded
    assertEntryContains("e1", METADATA_1, DATA_1);
    assertEquals(utf8Length(METADATA_1, DATA_1), store.size());
  }

  @Test
  void contendedEdit() {
    int threadCount = 10;
    var gotEditor = new AtomicBoolean();
    var begin = new CountDownLatch(1);
    var end = new CountDownLatch(threadCount);
    var executor = Executors.newCachedThreadPool();
    var cfs = IntStream.range(0, threadCount)
        .mapToObj(__ -> CompletableFuture.runAsync(() -> {
          awaitUninterruptibly(begin);
          var editor = store.edit("e1");
          try {
            assertTrue(editor == null || gotEditor.compareAndSet(false, true));
            if (editor != null) {
              writeEntry(editor, METADATA_1, DATA_1);
            }
          } finally {
            // Keep ownership of the editor (if owned) till all threads reach here
            end.countDown();
            awaitUninterruptibly(end);
            if (editor != null) {
              editor.close();
            }
          }
        }, executor))
        .collect(Collectors.toUnmodifiableList());
    executor.shutdown();
    begin.countDown();
    assertAll(cfs.stream().map(cf -> cf::join));
    assertEntryContains("e1", METADATA_1, DATA_1);
  }

  @Test
  void iterateEntries() {
    int entryCount = 9;
    for (int i = 1; i <= entryCount; i++) {
      writeEntry("e" + i, "metadata" + i, "data" + i);
    }
    assertEquals(entryCount * (utf8Length("metadata$", "data$")), store.size());
    assertEquals(entryCount, count(store));
    for (var viewer : iterable(store)) {
      try (viewer) {
        var index = viewer.key().charAt(1) - '0';
        assertEntryContains(viewer, "metadata" + index, "data" + index);
      }
    }
    store.clear();
    assertEquals(0, store.size());
    assertEquals(0, count(store));
  }

  /**
   * Iterator doesn't throw ConcurrentModificationException when the store is "concurrently"
   * mutated (fail-safe). The iterator is typically implemented as a weakly-consistent iterator but
   * no strong guarantees are made.
   */
  @Test
  void iteratorNoCMEThrown() {
    writeEntry("e1", "metadata1", "data1");
    writeEntry("e2", "metadata2", "data2");
    writeEntry("e3", "metadata3", "data3");
    var iter = store.viewAll();
    try (var viewer = iter.next()) {
      int index = viewer.key().charAt(1) - '0';
      assertEntryContains(viewer, "metadata" + index, "data" + index);
    }
    writeEntry("e4", "metadata4", "data4");
    writeEntry("e5", "metadata5", "data5");
    while (iter.hasNext()) {
      try (var viewer = iter.next()) {
        int index = viewer.key().charAt(1) - '0';
        assertEntryContains(viewer, "metadata" + index, "data" + index);
      }
    }
  }

  @Test
  void iteratorRemove() {
    writeEntry("e1", "metadata1", "data1");
    writeEntry("e2", "metadata2", "data2");
    writeEntry("e3", "metadata3", "data3");
    var iter = store.viewAll();
    boolean removedE2 = false;
    while (iter.hasNext()) {
      try (var viewer = iter.next()) {
        if ("e2".equals(viewer.key())) {
          assertFalse(removedE2);
          iter.remove();
          removedE2 = true;
        }
      }
    }
    assertNull(store.view("e2"));
    assertEquals(2, count(store));
  }

  /** Closing an editor of an evicted entry discards the edit. */
  @Test
  void removeBeforeClosingEditorDiscardsData() {
    try (var editor = notNull(store.edit("e1"))) {
      writeEntry(editor, METADATA_1, DATA_1);
      assertTrue(store.remove("e1"));
    }
    assertNull(store.view("e1"));
    assertEquals(0, store.size());
    assertEquals(0, count(store));
  }

  @Test
  void removeNonExistingEntry() {
    assertFalse(store.remove("e1"));
  }

  @Test
  void writeMaxSize() {
    store = newManagedStore(10);
    assertEquals(10, store.maxSize());
    writeEntry("e1", "a".repeat(4), "b".repeat(6));
    assertEquals(10, store.size());
  }

  @Test
  void writeBeyondMaxSize() {
    store = newManagedStore(6);
    writeEntry("e1", "aa", "bbb");
    assertEquals(5, store.size());
    writeEntry("e2", "ccc", "ddd");
    assertNull(store.view("e1"));
    assertEquals(6, store.size());
    assertEquals(1, count(store));
    assertEntryContains("e2", "ccc", "ddd");
  }

  @Test
  void writeBeyondMaxSizeTwice() {
    store = newManagedStore(6);
    writeEntry("e1", "aa", "bbb");
    assertEquals(5, store.size());
    writeEntry("e2", "ccc", "ddd");
    assertEntryContains("e2", "ccc", "ddd");
    assertNull(store.view("e1"));
    assertEquals(6, store.size());
    assertEquals(1, count(store));
    writeEntry("e3", "22", "333");
    assertEntryContains("e3", "22", "333");
    assertNull(store.view("e2"));
    assertEquals(5, store.size());
    assertEquals(1, count(store));
  }

  @Test
  void keepWritingBeyondMaxSize() {
    store = newManagedStore(12);
    for (int i = 1; i <= 11; i += 2) {
      var key1 = "e" + i;
      var key2 = "e" + (i + 1);
      writeEntry(key1, "aa", "bbb");
      assertEquals(i == 1 ? 5 : 11, store.size());
      writeEntry(key2, "ccc", "ddd");
      assertEquals(11, store.size());
      assertEntryContains(key1, "aa", "bbb");
      assertEntryContains(key2, "ccc", "ddd");
      if (i >= 3) {
        assertNull(store.view("e" + (i - 2)));
        assertNull(store.view("e" + (i - 1)));
      }
    }
    assertEquals(11, store.size());
    assertEquals(2, count(store));
  }

  @Test
  void evictionIsInLruOrder() {
    store = newManagedStore(18);
    writeEntry("e1", "aaa", "bbb");          // e1
    assertEquals(6, store.size());

    writeEntry("e2", "ccc", "ddd");          // e1, e2
    assertEquals(12, store.size());

    assertEntryContains("e1", "aaa", "bbb"); // e2, e1
    writeEntry("e3", "eee", "fff");          // e2, e1, e3
    assertEquals(18, store.size());

    assertEntryContains("e1", "aaa", "bbb"); // e2, e3, e1
    writeEntry("e4", "ggg", "hhh");          // e3, e1, e4
    assertEquals(18, store.size());
    assertNull(store.view("e2"));

    assertEntryContains("e3", "eee", "fff"); // e1, e4, e3
    writeEntry("e5", "iii", "jjj");          // e4, e3, e5
    assertEquals(18, store.size());
    assertNull(store.view("e1"));

    writeEntry("e6", "lll", "mmm");          // e3, e5, e6
    assertEquals(18, store.size());
    assertNull(store.view("e4"));

    writeEntry("e7", "nnn", "opqrstuvw");    // e6, e7
    assertEquals(18, store.size());
    assertNull(store.view("e3"));
    assertNull(store.view("e5"));

    assertEntryContains("e6", "lll", "mmm"); // e7, e6
    writeEntry("e8", "xxx", "yyy");          // e6, e8
    assertEquals(12, store.size());
    writeEntry("e9", "zzz", "~!@");          // e6, e8, e9
    assertEquals(18, store.size());

    var expectedRemaining = new HashSet<>(Set.of("e6", "e8", "e9"));
    for (var viewer : iterable(store)) {
      try (viewer) {
        assertTrue(expectedRemaining.remove(viewer.key()), expectedRemaining.toString());
      }
    }
    assertTrue(expectedRemaining.isEmpty(), expectedRemaining.toString());
  }

  @Test
  void discardWriteBeyondBound() {
    store = newManagedStore(12);
    writeEntry("e1", "aaa", "bbb");
    writeEntry("e2", "ccc", "ddd");
    try (var editor = notNull(store.edit("e3"))) {
      writeEntry(editor, "eee", "fff");
      editor.discard();
    }
    assertEquals(12, store.size());
    assertEntryContains("e1", "aaa", "bbb");
    assertEntryContains("e2", "ccc", "ddd");
  }

  @Test
  @Disabled("not yet implemented")
  void resetMaxSize_expanding() {
    store = newManagedStore(12);
    writeEntry("e1", "aaa", "ccc");
    writeEntry("e2", "ccc", "ddd");
    assertEquals(12, store.size());
    store.resetMaxSize(18);
    writeEntry("e3", "eee", "fff");
    assertEquals(18, store.size());
    assertEntryContains("e1", "aaa", "ccc");
    assertEntryContains("e2", "ccc", "ddd");
    assertEntryContains("e3", "eee", "fff");
  }

  @Test
  @Disabled("not yet implemented")
  void resetMaxSize_truncating() {
    store = newManagedStore(12);
    writeEntry("e1", "aaa", "ccc");          // e1
    writeEntry("e2", "ccc", "ddd");          // e1, e2
    assertEntryContains("e1", "aaa", "ccc"); // e2, e1
    store.resetMaxSize(6);
    assertNull(store.view("e2"));
    assertEquals(6, store.size());
  }

  @Test
  void viewDataBeingWritten() {
    try (var editor = notNull(store.edit("e1")); var viewer = editor.view()) {
      assertEntryContains(viewer, "", "");
      editor.metadata(UTF_8.encode(METADATA_1));
      assertEntryContains(viewer, METADATA_1, "");
      var dataBytes = UTF_8.encode(DATA_1);
      editor.writeAsync(0, dataBytes);
      assertEntryContains(viewer, METADATA_1, DATA_1);
      editor.writeAsync(viewer.dataSize(), dataBytes.rewind());
      assertEntryContains(viewer, METADATA_1, DATA_1.repeat(2));
    }
  }

  void writeEntry(String key, String metadata, String data) {
    try (var editor = notNull(store.edit(key))) {
      writeEntry(editor, metadata, data);
    }
  }

  void assertEntryContains(String key, String metadata, String data) {
    try (var viewer = notNull(store.view(key))) {
      assertEntryContains(viewer, metadata, data);
    }
  }

  static void assertEntryContains(Viewer viewer, String metadata, String data) {
    assertEquals(metadata, UTF_8.decode(viewer.metadata()).toString());
    assertEquals(data, readString(viewer));
    assertEquals(utf8Length(data), viewer.dataSize());
    assertEquals(utf8Length(metadata, data), viewer.entrySize());
  }

  static int utf8Length(String... values) {
    return Stream.of(values)
        .map(UTF_8::encode)
        .mapToInt(ByteBuffer::remaining)
        .sum();
  }

  static int count(Store store) {
    int count = 0;
    for (var viewer : iterable(store)) {
      viewer.close();
      count++;
    }
    return count;
  }

  static Iterable<Viewer> iterable(Store store) {
    return store::viewAll;
  }

  static <T> @NonNull T notNull(@Nullable T value) {
    assertNotNull(value);
    return value;
  }

  static void writeEntry(Editor editor, String metadata, String data) {
    editor.metadata(UTF_8.encode(metadata));
    writeString(editor, data);
  }

  static void writeString(Editor editor, String data) {
    writeBytes(editor, UTF_8.encode(data));
  }

  static String readString(Viewer viewer) {
    return UTF_8.decode(readBytes(viewer)).toString();
  }

  static void writeBytes(Editor editor, ByteBuffer data) {
    writeBytes(editor, data, 16);
  }

  static void writeBytes(Editor editor, ByteBuffer data, int buffSize) {
    int pos = 0;
    for (var buffer : tokenize(data, buffSize)) {
      int toWrite = buffer.remaining();
      int written = editor.writeAsync(pos, buffer).join();
      assertEquals(toWrite, written);
      assertFalse(buffer.hasRemaining()); // assert all is written
      pos += written;
    }
  }

  static List<ByteBuffer> tokenize(ByteBuffer data, int buffSize) {
    var iter = new BuffIterator(data, Math.max(1, buffSize));
    var list = new ArrayList<ByteBuffer>();
    iter.forEachRemaining(list::add);
    return List.copyOf(list);
  }

  static ByteBuffer readBytes(Viewer viewer) {
    return readBytes(viewer, 16);
  }

  static ByteBuffer readBytes(Viewer viewer, int buffSize) {
    var output = new ByteArrayOutputStream() {
      void write(ByteBuffer buffer) {
        var array = new byte[buffer.remaining()];
        buffer.get(array);
        write(array, 0, array.length);
      }
    };
    var buffer = ByteBuffer.allocate(buffSize);
    int totalRead = 0;
    int read;
    while ((read = viewer.readAsync(totalRead, buffer.clear()).join()) > 0) {
      buffer.flip();
      assertEquals(read, buffer.remaining());
      output.write(buffer);
      totalRead += read;
    }
    return ByteBuffer.wrap(output.toByteArray());
  }
}
