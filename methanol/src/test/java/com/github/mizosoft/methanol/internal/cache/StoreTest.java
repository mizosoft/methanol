package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.internal.cache.StoreTesting.assertAbsent;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.assertEntryEquals;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.assertUnreadable;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.edit;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.setMetadata;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.sizeOf;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.view;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.writeData;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.writeEntry;
import static com.github.mizosoft.methanol.testing.extensions.StoreProvider.StoreConfig.Execution.SAME_THREAD;
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
import com.github.mizosoft.methanol.internal.function.Unchecked;
import com.github.mizosoft.methanol.testing.extensions.StoreProvider;
import com.github.mizosoft.methanol.testing.extensions.StoreProvider.StoreConfig;
import com.github.mizosoft.methanol.testing.extensions.StoreProvider.StoreContext;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;

@ExtendWith(StoreProvider.class)
@Timeout(60)
class StoreTest {
  @ParameterizedTest
  @StoreConfig
  void writeThenRead(Store store) throws IOException {
    writeEntry(store, "e1", "Lucario", "Jynx");
    assertEntryEquals(store, "e1", "Lucario", "Jynx");
    assertEquals(sizeOf("Lucario", "Jynx"), store.size());
  }

  @ParameterizedTest
  @StoreConfig
  void writeThenReadTwice(Store store) throws IOException {
    writeEntry(store, "e1", "Lucario", "Jynx");
    assertEntryEquals(store, "e1", "Lucario", "Jynx");

    writeEntry(store, "e2", "Mew", "Mewtwo");
    assertEntryEquals(store, "e2", "Mew", "Mewtwo");
    assertEquals(sizeOf("Lucario", "Jynx", "Mew", "Mewtwo"), store.size());
  }

  @ParameterizedTest
  @StoreConfig
  void concurrentViewers(Store store) throws IOException {
    writeEntry(store, "e1", "Pockemon", "Charmander");

    // Create viewerCount concurrent viewers
    int viewerCount = 10;
    var threadPool = Executors.newFixedThreadPool(viewerCount);
    var arrival = new CyclicBarrier(viewerCount);
    var tasks = new ArrayList<CompletableFuture<Void>>();
    for (int i = 0; i < viewerCount; i++) {
      var task = Unchecked.runAsync(() -> {
        awaitUninterruptibly(arrival);
        assertEntryEquals(store, "e1", "Pockemon", "Charmander");
      }, threadPool);

      tasks.add(task);
    }

    threadPool.shutdown();
    assertAll(tasks.stream().map(cf -> cf::join));
  }

  @ParameterizedTest
  @StoreConfig
  void writeMetadataWithoutData(Store store) throws IOException {
    try (var editor = edit(store, "e1")) {
      editor.metadata(UTF_8.encode("Light"));
      editor.commitOnClose();
    }
    assertEntryEquals(store, "e1", "Light", "");
  }

  @ParameterizedTest
  @StoreConfig
  void writeDataWithoutMetadata(Store store) throws IOException {
    try (var editor = edit(store, "e1")) {
      writeData(editor, "I'm in the dark here!");
      editor.commitOnClose();
    }
    assertEntryEquals(store, "e1", "", "I'm in the dark here!");
  }

  /** An entry must be discarded if its first edit wrote nothing. */
  @ParameterizedTest
  @StoreConfig
  void writeNothingOnFirstEdit(Store store, StoreContext context) throws IOException {
    try (var editor = edit(store, "e1")) {
      editor.commitOnClose();
    }
    assertAbsent(store, context, "e1");
    assertEquals(0, store.size());
  }

  @ParameterizedTest
  @StoreConfig
  void updateMetadataOnSecondEdit(Store store) throws IOException {
    writeEntry(store, "e1", "Mew", "Pickachu");

    try (var editor = edit(store, "e1")) {
      setMetadata(editor, "Mewtwo");
      editor.commitOnClose();
    }
    assertEntryEquals(store, "e1", "Mewtwo", "Pickachu");
    assertEquals(sizeOf("Mewtwo", "Pickachu"), store.size());
  }

  @ParameterizedTest
  @StoreConfig
  void clearMetadataOnSecondEdit(Store store) throws IOException {
    writeEntry(store, "e1", "Mr Mime", "Ditto");

    try (var editor = edit(store, "e1")) {
      setMetadata(editor, "");
      editor.commitOnClose();
    }
    assertEntryEquals(store, "e1", "", "Ditto");
    assertEquals(sizeOf("", "Ditto"), store.size());
  }

  @ParameterizedTest
  @StoreConfig
  void updateDataOnSecondEdit(Store store) throws IOException {
    writeEntry(store, "e1", "Meowth", "Mew");

    try (var editor = edit(store, "e1")) {
      writeData(editor, "Mewtwo");
      editor.commitOnClose();
    }
    assertEntryEquals(store, "e1", "Meowth", "Mewtwo");
    assertEquals(sizeOf("Meowth", "Mewtwo"), store.size());
  }

  @ParameterizedTest
  @StoreConfig
  void clearDataOnSecondEdit(Store store) throws IOException {
    writeEntry(store, "e1", "Jynx", "Charmander");

    try (var editor = edit(store, "e1")) {
      writeData(editor, "");
      editor.commitOnClose();
    }
    assertEntryEquals(store, "e1", "Jynx", "");
    assertEquals(sizeOf("Jynx"), store.size());
  }

  @ParameterizedTest
  @StoreConfig
  void writeThenRemove(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "Jigglypuff", "Pickachu");

    assertTrue(store.remove("e1"));
    assertAbsent(store, context, "e1");
    assertEquals(0, store.size());
  }

  @ParameterizedTest
  @StoreConfig
  void writeThenClear(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "methanol", "CH3OH");
    writeEntry(store, "e2", "ethanol", "C2H5OH");

    store.clear();
    assertAbsent(store, context, "e1");
    assertAbsent(store, context, "e2");
    assertEquals(0, store.size());
  }

  @ParameterizedTest
  @StoreConfig
  void editSameEntryTwice(Store store) throws IOException {
    writeEntry(store, "e1", "Mew", "Pickachu");
    writeEntry(store, "e1", "Mewtwo", "Eevee");
    assertEntryEquals(store, "e1", "Mewtwo", "Eevee");
    assertEquals(sizeOf("Mewtwo", "Eevee"), store.size());
  }

  @ParameterizedTest
  @StoreConfig
  void discardEdit(Store store, StoreContext context) throws IOException {
    try (var editor = edit(store, "e1")) {
      writeEntry(editor, "Jynx", "Eevee");
      // Don't commit
    }
    assertAbsent(store, context, "e1");
    assertEquals(0, store.size());
  }

  @ParameterizedTest
  @StoreConfig
  void discardSecondEdit(Store store) throws IOException {
    writeEntry(store, "e1", "Mew", "Mewtwo");

    try (var editor = edit(store, "e1")) {
      writeEntry(editor, "Jynx", "Eevee");
      // Don't commit
    }
    assertEntryEquals(store, "e1", "Mew", "Mewtwo");
    assertEquals(sizeOf("Mew", "Mewtwo"), store.size());
  }

  @ParameterizedTest
  @StoreConfig
  void discardEditAfterRemove(Store store, StoreContext context) throws IOException {
    var editor = edit(store, "e1");
    writeEntry(editor, "Jynx", "Mew");

    assertTrue(store.remove("e1"));

    // Don't commit
    editor.close();
    assertAbsent(store, context, "e1");
  }

  @ParameterizedTest
  @StoreConfig
  void contendedEdit(Store store) throws IOException {
    // Create 10 threads that contend over an edit
    int threadCount = 10;
    var arrival = new CyclicBarrier(threadCount);
    var endLatch = new CountDownLatch(threadCount);
    var threadPool = Executors.newCachedThreadPool();
    var acquired = new AtomicBoolean();
    var tasks = new ArrayList<CompletableFuture<Void>>();
    for (int i = 0; i < threadCount; i++) {
      var task = Unchecked.runAsync(() -> {
        awaitUninterruptibly(arrival);

        Editor editor = null;
        try {
          editor = store.edit("e1");
          assertTrue(
              editor == null || acquired.compareAndSet(false, true),
              "more than one thread got an editor!");
          if (editor != null) {
            writeEntry(editor, "Jigglypuff", "Psyduck");
            editor.commitOnClose();
          }
        } finally {
          endLatch.countDown();
          if (editor != null) {
            // Keep ownership of the editor (if owned) till all threads finish
            awaitUninterruptibly(endLatch);

            editor.close();
          }
        }
      }, threadPool);

      tasks.add(task);
    }

    threadPool.shutdown();
    assertAll(tasks.stream().map(cf -> cf::join));
    assertEntryEquals(store, "e1", "Jigglypuff", "Psyduck");
  }

  @ParameterizedTest
  @StoreConfig
  void entryRemainsUnreadableTillFirstEditCompletes(Store store) throws IOException {
    var editor = edit(store, "e1");
    setMetadata(editor, "Snorlax");

    // The entry doesn't have a committed value, so it's not readable
    assertUnreadable(store, "e1");

    writeData(editor, "Squirtle");
    editor.commitOnClose();

    assertUnreadable(store, "e1");

    // Closing the editor commits what's written
    editor.close();
    assertEntryEquals(store, "e1", "Snorlax", "Squirtle");
    assertEquals(sizeOf("Snorlax", "Squirtle"), store.size());
  }

  @ParameterizedTest
  @StoreConfig
  void  entryRemainsUnchangedTillSecondEditCompletes(Store store) throws IOException {
    writeEntry(store, "e1", "Mew", "Eevee");

    var editor = edit(store, "e1");
    setMetadata(editor, "Mewtwo");

    // Only the entry's last committed value is readable
    assertEntryEquals(store, "e1", "Mew", "Eevee");

    writeData(editor, "Meowth");
    editor.commitOnClose();

    assertEntryEquals(store, "e1", "Mew", "Eevee");

    // Closing the editor commits what's written
    editor.close();
    assertEntryEquals(store, "e1", "Mewtwo", "Meowth");
    assertEquals(sizeOf("Mewtwo", "Meowth"), store.size());
  }

  /** Removing an entry discards any ongoing edit for this entry. */
  @ParameterizedTest
  @StoreConfig
  void removeBeforeCommittingFirstEdit(Store store, StoreContext context) throws IOException {
    var editor = edit(store, "e1");
    writeEntry(editor, "Pickachu", "Jigglypuff");
    editor.commitOnClose();

    assertTrue(store.remove("e1"));
    assertAbsent(store, context, "e1");

    // Close will silently discard the edit
    editor.close();
    assertAbsent(store, context, "e1");
    assertEquals(0, store.size());
  }

  @ParameterizedTest
  @StoreConfig
  void clearWhileEditing(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "Jynx", "Raichu");
    writeEntry(store, "e2", "Eevee", "Ditto");

    // Edit existing entries
    var editor1 = edit(store, "e1");
    var editor2 = edit(store, "e2");

    // Edit new entries
    var editor3 = edit(store, "e3");
    var editor4 = edit(store, "e4");

    // Write to a first & second edit before clearing
    writeEntry(editor1, "Pichachu", "Snorlax");
    editor1.commitOnClose();
    writeEntry(editor3, "Gengar", "Raichu");
    editor3.commitOnClose();

    store.clear();

    // Write to a first & second edit after clearing
    writeEntry(editor2, "Squirtle", "Charmander");
    editor2.commitOnClose();
    writeEntry(editor4, "Mew", "Mewtwo");
    editor4.commitOnClose();

    // Neither existing nor new entries receive committed values
    editor1.close();
    editor2.close();
    editor3.close();
    editor4.close();
    assertAbsent(store, context, "e1");
    assertAbsent(store, context, "e2");
    assertAbsent(store, context, "e3");
    assertAbsent(store, context, "e4");
    assertEquals(0, store.size());
  }

  /**
   * An entry removal should not bother (or be bothered by) an already open Viewer. This should also
   * work for DiskStores under the notorious Windows feature that prohibits open files from being
   * deleted unless opened with the FILE_SHARE_DELETE flag, with which files in NIO are normally
   * opened.
   */
  @ParameterizedTest
  @StoreConfig
  void removeWhileReading(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "Ditto", "Eevee");

    try (var viewer = view(store, "e1")) {
      assertTrue(store.remove("e1"));
      assertAbsent(store, context, "e1");
      assertEquals(0, store.size());

      // Viewer keeps operating normally
      assertEntryEquals(viewer, "Ditto", "Eevee");
    }
  }

  /**
   * An edit of a removed entry should not bother (or be bothered by) a Viewer opened for the entry
   * before removal.
   */
  @ParameterizedTest
  @StoreConfig
  void removeThenWriteWhileReading(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "Ditto", "Eevee");

    try (var viewer = view(store, "e1")) {
      assertTrue(store.remove("e1"));
      assertAbsent(store, context, "e1");
      assertEquals(0, store.size());

      // Viewer keeps operating normally
      assertEntryEquals(viewer, "Ditto", "Eevee");

      // This write creates a new entry, completely unrelated to the removed one
      writeEntry(store, "e1", "Jynx", "Psyduck");
      assertEntryEquals(store, "e1", "Jynx", "Psyduck");

      // First viewer keeps operating normally & reads the entry value it was opened for
      assertEntryEquals(viewer, "Ditto", "Eevee");
    }
  }

  @ParameterizedTest
  @StoreConfig
  void updateMetadataWhileReading(Store store) throws IOException {
    writeEntry(store, "e1", "Pickachu", "Psyduck");

    try (var viewer = view(store, "e1")) {
      var editor = edit(store, "e1");
      setMetadata(editor, "Raichu");
      editor.commitOnClose();

      assertEntryEquals(viewer, "Pickachu", "Psyduck");

      editor.close();
      assertEntryEquals(store, "e1", "Raichu", "Psyduck");

      assertEntryEquals(viewer, "Pickachu", "Psyduck");
    }
  }

  @ParameterizedTest
  @StoreConfig
  void editFromViewer(Store store) throws IOException {
    writeEntry(store, "e1", "Pickachu", "Snorlax");

    try (var viewer = view(store, "e1")) {
      try (var editor = edit(viewer)) {
        writeEntry(editor, "Mewtwo", "Squirtle");
        editor.commitOnClose();

        assertEntryEquals(viewer, "Pickachu", "Snorlax");
        assertEntryEquals(store, "e1", "Pickachu", "Snorlax");
      }
      assertEntryEquals(store, "e1", "Mewtwo", "Squirtle");

      // The already open viewer doesn't reflect made changes
      assertEntryEquals(viewer, "Pickachu", "Snorlax");
    }
  }

  @ParameterizedTest
  @StoreConfig
  void discardEditFromViewer(Store store) throws IOException {
    writeEntry(store, "e1", "Ditto", "Eevee");

    try (var viewer = view(store, "e1")) {
      try (var editor = edit(viewer)) {
        writeEntry(editor, "Jigglypuff", "Mew");
        // Don't commit
      }
      assertEntryEquals(viewer, "Ditto", "Eevee");
      assertEntryEquals(store, "e1", "Ditto", "Eevee");
    }
  }

  @ParameterizedTest
  @StoreConfig
  void removeWhileEditingFromViewer(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "Pickachu", "Mewtwo");

    try (var viewer = view(store, "e1")) {
      try (var editor = edit(viewer)) {
        writeEntry(editor, "Jigglypuff", "Mew");
        editor.commitOnClose();

        // Removal discards the edit
        assertTrue(store.remove("e1"));
      }
      assertAbsent(store, context, "e1");
      assertEquals(0, store.size());

      // Viewer still operates normally
      assertEntryEquals(viewer,  "Pickachu", "Mewtwo");
    }
  }

  @ParameterizedTest
  @StoreConfig
  void canNotEditFromStaleViewer(Store store) throws IOException {
    writeEntry(store, "e1", "Eevee", "Ditto");

    try (var viewer = view(store, "e1")) {
      // Make viewer stale by writing new values
      writeEntry(store, "e1", "Jynx", "Psyduck");
      assertEntryEquals(store, "e1", "Jynx", "Psyduck");

      assertNull(viewer.edit()); // Uneditable
      assertEntryEquals(viewer, "Eevee", "Ditto");
    }
  }

  @ParameterizedTest
  @StoreConfig
  void canNotEditFromStaleViewerDueToRemoval(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "Eevee", "Ditto");

    try (var viewer = view(store, "e1")) {
      // Make viewer stale by removing the entry
      assertTrue(store.remove("e1"));
      assertAbsent(store, context, "e1");

      assertNull(viewer.edit()); // Uneditable
      assertEntryEquals(viewer, "Eevee", "Ditto");
    }
  }

  @ParameterizedTest
  @StoreConfig
  void canNotEditFromViewerDuringAnOngoingEdit(Store store) throws IOException {
    writeEntry(store, "e1", "Eevee", "Ditto");

    try (var viewer = view(store, "e1")) {
      // Open an editor from the store
      try (var editor = edit(store, "e1")) {
        writeEntry(editor, "Jynx", "Psyduck");
        editor.commitOnClose();

        assertNull(viewer.edit()); // Uneditable
      }
      assertEntryEquals(store, "e1", "Jynx", "Psyduck");
      assertEntryEquals(viewer, "Eevee", "Ditto");
    }
  }

  @ParameterizedTest
  @StoreConfig
  void removeNonExistingEntry(Store store) throws IOException {
    assertFalse(store.remove("e1"));
    writeEntry(store, "e1", "Raichu", "Eevee");
    assertTrue(store.remove("e1"));
    assertFalse(store.remove("e1"));
  }

  @ParameterizedTest
  @StoreConfig
  void iterateEntries(Store store) throws IOException {
    var entries = Map.of(
        "e1", List.of("Pickachu", "Raichu"),
        "e2", List.of("Mew", "Mewtwo"),
        "e3", List.of("Jigglypuff", "Charmander"));
    for (var entry : entries.entrySet()) {
      writeEntry(store, entry.getKey(), entry.getValue().get(0), entry.getValue().get(1));
    }

    var iter = store.viewAll();
    for (int i = 0; i < entries.size(); i++) {
      assertTrue(iter.hasNext());

      var viewer = iter.next();
      var entry = entries.get(viewer.key());
      assertNotNull(entry, "entry came from nowhere: " + viewer.key());
      assertEntryEquals(store, viewer.key(), entry.get(0), entry.get(1));
    }
    assertFalse(iter.hasNext());
  }

  @ParameterizedTest
  @StoreConfig
  void removeFromIterator(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "Mew", "Mewtwo");
    writeEntry(store, "e2", "Charmander", "Pickachu");

    // Remove e2 with Iterator::remove
    var iter = store.viewAll();
    for (int i = 0; i < 2; i++) {
      assertTrue(iter.hasNext());

      var viewer = iter.next();
      if (viewer.key().equals("e2")) {
        iter.remove();
      } else {
        assertEquals("e1", viewer.key());
        assertEntryEquals(viewer, "Mew", "Mewtwo");
      }
    }
    assertFalse(iter.hasNext());

    assertAbsent(store, context, "e2");
    assertEquals(sizeOf("Mew", "Mewtwo"), store.size());
  }

  @ParameterizedTest
  @StoreConfig(maxSize = 10, execution = SAME_THREAD)
  void writeExactlyMaxSizeBytesByOneEntry(Store store) throws IOException {
    writeEntry(store, "e1", "12345", "abcde"); // Grow size to 10 bytes
    assertEntryEquals(store, "e1", "12345", "abcde");
    assertEquals(10, store.size());
  }

  @ParameterizedTest
  @StoreConfig(maxSize = 10, execution = SAME_THREAD)
  void writeExactlyMaxSizeBytesByTwoEntries(Store store) throws IOException {
    writeEntry(store, "e1", "12", "abc"); // Grow size to 5 bytes
    writeEntry(store, "e2", "45", "def"); // Grow size to 10 bytes
    assertEntryEquals(store, "e1", "12", "abc");
    assertEntryEquals(store, "e2", "45", "def");
    assertEquals(10, store.size());
  }

  @ParameterizedTest
  @StoreConfig(maxSize = 15, execution = SAME_THREAD)
  void writeBeyondMaxSize(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "12", "abc"); // Grow size to 5 bytes
    writeEntry(store, "e2", "34", "def"); // Grow size to 10 bytes
    assertEquals(10, store.size());

    // LRU queue: e2, e1
    view(store, "e1").close();

    // Grow size to 16 bytes, causing e2 to be evicted
    writeEntry(store, "e3", "567", "ghi");

    // LRU queue: e1, e3
    assertAbsent(store, context, "e2");
    assertEntryEquals(store, "e1", "12", "abc");
    assertEntryEquals(store, "e3", "567", "ghi");
    assertEquals(11, store.size());

    // Grows size to 11 + 14 bytes causing both e1 & e3 to be evicted
    writeEntry(store, "e4", "Jynx", "Charmander");

    assertAbsent(store, context, "e1");
    assertAbsent(store, context, "e3");
    assertEntryEquals(store, "e4", "Jynx", "Charmander");
    assertEquals(14, store.size());
  }

  @ParameterizedTest
  @StoreConfig(maxSize = 15, execution = SAME_THREAD)
  void discardedWriteBeyondMaxSize(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "123", "abc"); // Grow size to 6 bytes
    writeEntry(store, "e2", "456", "def"); // Grow size to 12 bytes
    assertEquals(12, store.size());

    try (var editor = edit(store, "e3")) {
      writeEntry(editor, "alpha", "beta");
      // Don't commit
    }
    assertAbsent(store, context, "e3");
    assertEntryEquals(store, "e1", "123", "abc");
    assertEntryEquals(store, "e2", "456", "def");
    assertEquals(12, store.size());
  }

  @ParameterizedTest
  @StoreConfig(maxSize = 15, execution = SAME_THREAD)
  void discardedByRemovalWriteBeyondMaxSize(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "123", "abc"); // Grow size to 6 bytes
    writeEntry(store, "e2", "456", "def"); // Grow size to 12 bytes
    assertEquals(12, store.size());

    try (var editor = edit(store, "e3")) {
      writeEntry(editor, "alpha", "beta");
      editor.commitOnClose();

      assertTrue(store.remove("e3"));
    }
    assertAbsent(store, context, "e3");
    assertEntryEquals(store, "e1", "123", "abc");
    assertEntryEquals(store, "e2", "456", "def");
    assertEquals(12, store.size());
  }

  @ParameterizedTest
  @StoreConfig(maxSize = 14, execution = SAME_THREAD)
  void writeBeyondMaxSizeByMetadataUpdate(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "123", "abc"); // Grow size to 6 bytes
    writeEntry(store, "e2", "456", "def"); // Grow size to 12 bytes
    assertEquals(12, store.size());

    try (var editor = edit(store, "e1")) {
      // Increase metadata by 3 bytes, causing size to grow to 15 bytes & e2 to be evicted
      setMetadata(editor, "123456");
      editor.commitOnClose();
    }
    assertAbsent(store, context, "e2");
    assertEntryEquals(store, "e1", "123456", "abc");
    assertEquals(9, store.size());
  }

  @ParameterizedTest
  @StoreConfig(maxSize = 14, execution = SAME_THREAD)
  void writeBeyondMaxSizeByDataUpdate(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "123", "abc"); // Grow size to 6 bytes
    writeEntry(store, "e2", "456", "def"); // Grow size to 12 bytes
    assertEquals(12, store.size());

    try (var editor = edit(store, "e1")) {
      // Increase data by 3 bytes, causing size to grow to 15 bytes & e2 to be evicted
      writeData(editor, "abcdef");
      editor.commitOnClose();
    }
    assertAbsent(store, context, "e2");
    assertEntryEquals(store, "e1", "123", "abcdef");
    assertEquals(9, store.size());
  }

  @ParameterizedTest
  @StoreConfig(maxSize = 18, execution = SAME_THREAD)
  void evictionInLru(Store store, StoreContext context) throws IOException {
    // Grow size to 6 bytes
    // LRU queue: e1
    writeEntry(store, "e1", "aaa", "bbb");
    assertEquals(6, store.size());

    // Grow size to 12 bytes
    // LRU queue: e1, e2
    writeEntry(store, "e2", "ccc", "ddd");
    assertEquals(12, store.size());

    // LRU queue: e2, e1
    view(store, "e1").close();

    // Grow size to 18 bytes
    // LRU queue: e2, e1, e3
    writeEntry(store, "e3", "eee", "fff");
    assertEquals(18, store.size());

    // LRU queue: e2, e3, e1
    view(store, "e1").close();

    // Grow size to 24 bytes, causing e2 to be evicted to get down to 18
    // LRU queue: e3, e1, e4
    writeEntry(store, "e4", "ggg", "hhh");
    assertAbsent(store, context, "e2");
    assertEquals(18, store.size());

    // LRU queue: e1, e4, e3
    view(store, "e3").close();

    // Grow size to 24 bytes, causing e1 to be evicted to get down to 18 bytes
    // LRU queue: e4, e3, e5
    writeEntry(store, "e5", "iii", "jjj");
    assertAbsent(store, context, "e1");
    assertEquals(18, store.size());

    // Grow size to 18 + 12 bytes, causing e4 & e3 to be evicted to get down to 18 bytes
    // LRU queue: e5, e6
    writeEntry(store, "e6", "kkk", "lmnopqrst");
    assertAbsent(store, context, "e4", "e3");
    assertEquals(18, store.size());

    // LRU queue: e6, e5
    view(store, "e5").close();

    // Grow size to 24 bytes, causing e6 to be evicted to get down to 12
    // LRU queue: e5, e7
    writeEntry(store, "e7", "uuu", "vvv");
    assertAbsent(store, context, "e6");
    assertEquals(12, store.size());

    // Grow size to 18 bytes, causes nothing to be evicted since size is within bounds
    // LRU queue: e5, e7, e8
    writeEntry(store, "e8", "xxx", "~!@");
    assertEquals(18, store.size());

    // Write one 18 bytes entry, causing all other entries to be evicted
    writeEntry(store, "e9", "Ricardo", "all is mine");
    assertAbsent(store, context, "e5, e7", "e8");
    assertEquals(18, store.size());
  }

  /**
   * Test that the store only takes a snapshot of the passed metadata buffer such that mutations on
   * it do not affect the store.
   */
  @ParameterizedTest
  @StoreConfig
  void mutateMetadataBufferAfterPassingToEditor(Store store) throws IOException {
    var metadata = UTF_8.encode("420");
    try (var editor = edit(store, "e1")) {
      editor.metadata(metadata);
      // Metadata is consumed
      assertFalse(metadata.hasRemaining());

      metadata.rewind().put(new byte[]{'6', '9'});
      editor.commitOnClose();
    }
    assertEntryEquals(store, "e1", "420", "");
  }

  /** Ensure the position of the metadata buffer a viewer returns can be changed independently. */
  @ParameterizedTest
  @StoreConfig
  void mutatePositionOfMetadataBufferReturnedFromViewer(Store store) throws IOException {
    writeEntry(store, "e1", "555", "");
    try (var viewer = view(store, "e1")) {
      var metadata = viewer.metadata();
      metadata.position(metadata.limit()); // Consume
      assertEquals(0, viewer.metadata().position());
      assertEntryEquals(viewer, "555", "");
    }
  }

  @ParameterizedTest
  @StoreConfig
  void metadataBufferReturnedFromViewerIsReadOnly(Store store) throws IOException {
    writeEntry(store, "e1", "555", "");
    try (var viewer = view(store, "e1")) {
      assertThrows(
          ReadOnlyBufferException.class, () -> viewer.metadata().put(new byte[] {'6', '9'}));
    }
  }

  @ParameterizedTest
  @StoreConfig
  void readBeyondDataSize(Store store) throws IOException {
    writeEntry(store, "e1", "Jynx", "Mew");
    try (var viewer = view(store, "e1")) {
      int read1 = viewer.readAsync(viewer.dataSize(), ByteBuffer.allocate(1)).join();
      assertEquals(read1, -1);

      int read2 = viewer.readAsync(viewer.dataSize(), ByteBuffer.allocate(1)).join();
      assertEquals(read2, -1);
    }
  }

  @ParameterizedTest
  @StoreConfig
  void readWithNegativePosition(Store store) throws IOException {
    writeEntry(store, "e1", "Jynx", "Mew");
    try (var viewer = view(store, "e1")) {
      assertThrows(
          IllegalArgumentException.class, () -> viewer.readAsync(-1, ByteBuffer.allocate(0)));
    }
  }

  @ParameterizedTest
  @StoreConfig
  void writeWithIllegalPosition(Store store) throws IOException {
    try (var editor = edit(store, "e1")) {
      assertThrows(
          IllegalArgumentException.class, () -> editor.writeAsync(-1, ByteBuffer.allocate(1)));

      // Editor prohibits gabs between writes
      editor.writeAsync(0, ByteBuffer.allocate(1));
      assertThrows(
          IllegalArgumentException.class, () -> editor.writeAsync(2, ByteBuffer.allocate(1)));
    }
  }

  @ParameterizedTest
  @StoreConfig
  void editorRefusesWritesAfterCommitOnClose(Store store) throws IOException {
    try (var editor = edit(store, "e1")) {
      writeEntry(editor, "Ditto", "Jynx");
      editor.commitOnClose();
      assertThrows(IllegalStateException.class, () -> editor.metadata(ByteBuffer.allocate(0)));
      assertThrows(IllegalStateException.class, () -> editor.writeAsync(0, ByteBuffer.allocate(0)));
    }
  }

  @ParameterizedTest
  @StoreConfig
  void editorDiscardsWritesAfterClosure(Store store, StoreContext context) throws IOException {
    var editor = edit(store, "e1");
    try (editor) {
      writeEntry(editor, "Ditto", "Jynx");
      // Don't commit
    }

    // This write goes into oblivion
    writeData(editor, "Mewtwo");
    assertAbsent(store, context, "e1");
  }
}
