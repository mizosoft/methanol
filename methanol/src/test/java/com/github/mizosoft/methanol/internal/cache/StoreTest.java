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
import static com.github.mizosoft.methanol.testing.TestUtils.awaitUninterruptibly;
import static com.github.mizosoft.methanol.testing.junit.ExecutorExtension.ExecutorType.CACHED_POOL;
import static com.github.mizosoft.methanol.testing.junit.StoreConfig.Execution.SAME_THREAD;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.function.Unchecked;
import com.github.mizosoft.methanol.testing.Logging;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension.ExecutorConfig;
import com.github.mizosoft.methanol.testing.junit.StoreConfig;
import com.github.mizosoft.methanol.testing.junit.StoreContext;
import com.github.mizosoft.methanol.testing.junit.StoreExtension;
import com.github.mizosoft.methanol.testing.junit.StoreExtension.StoreParameterizedTest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@Timeout(60)
@ExtendWith({StoreExtension.class, ExecutorExtension.class})
class StoreTest {
  static {
    Logging.disable(DiskStore.class);
  }

  @StoreParameterizedTest
  void writeThenRead(Store store) throws IOException {
    writeEntry(store, "e1", "Lucario", "Jynx");
    assertEntryEquals(store, "e1", "Lucario", "Jynx");
    assertThat(store.size()).isEqualTo(sizeOf("Lucario", "Jynx"));
  }

  @StoreParameterizedTest
  void writeThenReadTwice(Store store) throws IOException {
    writeEntry(store, "e1", "Lucario", "Jynx");
    assertEntryEquals(store, "e1", "Lucario", "Jynx");

    writeEntry(store, "e2", "Mew", "Mewtwo");
    assertEntryEquals(store, "e2", "Mew", "Mewtwo");
    assertThat(store.size()).isEqualTo(sizeOf("Lucario", "Jynx", "Mew", "Mewtwo"));
  }

  @StoreParameterizedTest
  @ExecutorConfig(CACHED_POOL)
  void concurrentViewers(Store store, Executor threadPool) throws IOException {
    writeEntry(store, "e1", "Pokemon", "Charmander");

    // Create viewerCount concurrent viewers
    int viewerCount = 10;
    var arrival = new CyclicBarrier(viewerCount);
    var assertionTasks = new ArrayList<CompletableFuture<Void>>();
    for (int i = 0; i < viewerCount; i++) {
      var task = Unchecked.runAsync(() -> {
        awaitUninterruptibly(arrival);
        assertEntryEquals(store, "e1", "Pokemon", "Charmander");
      }, threadPool);

      assertionTasks.add(task);
    }

    assertAll(assertionTasks.stream().map(cf -> cf::join));
  }

  @StoreParameterizedTest
  void writeMetadataWithoutData(Store store) throws IOException {
    try (var editor = edit(store, "e1")) {
      editor.metadata(UTF_8.encode("Light"));
      editor.commitOnClose();
    }
    assertEntryEquals(store, "e1", "Light", "");
  }

  @StoreParameterizedTest
  void writeDataWithoutMetadata(Store store) throws IOException {
    try (var editor = edit(store, "e1")) {
      writeData(editor, "I'm in the dark here!");
      editor.commitOnClose();
    }
    assertEntryEquals(store, "e1", "", "I'm in the dark here!");
  }

  /** An entry must be discarded if its first edit wrote nothing. */
  @StoreParameterizedTest
  void writeNothingOnFirstEdit(Store store, StoreContext context) throws IOException {
    try (var editor = edit(store, "e1")) {
      editor.commitOnClose();
    }
    assertAbsent(store, context, "e1");
    assertThat(store.size()).isZero();
  }

  @StoreParameterizedTest
  void updateMetadataOnSecondEdit(Store store) throws IOException {
    writeEntry(store, "e1", "Mew", "Pickachu");

    try (var editor = edit(store, "e1")) {
      setMetadata(editor, "Mewtwo");
      editor.commitOnClose();
    }
    assertEntryEquals(store, "e1", "Mewtwo", "Pickachu");
    assertThat(store.size()).isEqualTo(sizeOf("Mewtwo", "Pickachu"));
  }

  @StoreParameterizedTest
  void clearMetadataOnSecondEdit(Store store) throws IOException {
    writeEntry(store, "e1", "Mr Mime", "Ditto");

    try (var editor = edit(store, "e1")) {
      setMetadata(editor, "");
      editor.commitOnClose();
    }
    assertEntryEquals(store, "e1", "", "Ditto");
    assertThat(store.size()).isEqualTo(sizeOf("", "Ditto"));
  }

  @StoreParameterizedTest
  void updateDataOnSecondEdit(Store store) throws IOException {
    writeEntry(store, "e1", "Meowth", "Mew");

    try (var editor = edit(store, "e1")) {
      writeData(editor, "Mewtwo");
      editor.commitOnClose();
    }
    assertEntryEquals(store, "e1", "Meowth", "Mewtwo");
    assertThat(store.size()).isEqualTo(sizeOf("Meowth", "Mewtwo"));
  }

  @StoreParameterizedTest
  void clearDataOnSecondEdit(Store store) throws IOException {
    writeEntry(store, "e1", "Jynx", "Charmander");

    try (var editor = edit(store, "e1")) {
      writeData(editor, "");
      editor.commitOnClose();
    }
    assertEntryEquals(store, "e1", "Jynx", "");
    assertThat(store.size()).isEqualTo(sizeOf("Jynx"));
  }

  @StoreParameterizedTest
  void writeThenRemove(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "Jigglypuff", "Pickachu");

    assertThat(store.remove("e1")).isTrue();
    assertAbsent(store, context, "e1");
    assertThat(store.size()).isZero();
  }

  @StoreParameterizedTest
  void writeThenClear(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "methanol", "CH3OH");
    writeEntry(store, "e2", "ethanol", "C2H5OH");

    store.clear();
    assertAbsent(store, context, "e1");
    assertAbsent(store, context, "e2");
    assertThat(store.iterator().hasNext()).isFalse();
    assertThat(store.size()).isZero();
  }

  @StoreParameterizedTest
  void editSameEntryTwice(Store store) throws IOException {
    writeEntry(store, "e1", "Mew", "Pickachu");
    writeEntry(store, "e1", "Mewtwo", "Eevee");
    assertEntryEquals(store, "e1", "Mewtwo", "Eevee");
    assertThat(store.size()).isEqualTo(sizeOf("Mewtwo", "Eevee"));
  }

  @StoreParameterizedTest
  void discardEdit(Store store, StoreContext context) throws IOException {
    try (var editor = edit(store, "e1")) {
      writeEntry(editor, "Jynx", "Eevee");
      // Don't commit
    }
    assertAbsent(store, context, "e1");
    assertThat(store.size()).isZero();
  }

  @StoreParameterizedTest
  void discardSecondEdit(Store store) throws IOException {
    writeEntry(store, "e1", "Mew", "Mewtwo");
    try (var editor = edit(store, "e1")) {
      writeEntry(editor, "Jynx", "Eevee");
      // Don't commit
    }
    assertEntryEquals(store, "e1", "Mew", "Mewtwo");
    assertThat(store.size()).isEqualTo(sizeOf("Mew", "Mewtwo"));
  }

  @StoreParameterizedTest
  void discardEditAfterRemove(Store store, StoreContext context) throws IOException {
    var editor = edit(store, "e1");
    writeEntry(editor, "Jynx", "Mew");

    assertThat(store.remove("e1")).isTrue();

    // Don't commit
    editor.close();
    assertAbsent(store, context, "e1");
  }

  @StoreParameterizedTest
  @ExecutorConfig(CACHED_POOL)
  void contendedEdit(Store store, Executor threadPool) throws IOException {
    // Create 10 threads that contend over an edit
    int threadCount = 10;
    var arrival = new CyclicBarrier(threadCount);
    var endLatch = new CountDownLatch(threadCount);
    var acquired = new AtomicBoolean();
    var assertionTasks = new ArrayList<CompletableFuture<Void>>();
    for (int i = 0; i < threadCount; i++) {
      var task = Unchecked.runAsync(() -> {
        awaitUninterruptibly(arrival);

        Editor editor = null;
        try {
          editor = store.edit("e1");
          assertThat(editor == null || acquired.compareAndSet(false, true))
              .withFailMessage("more than one thread got an editor!")
              .isTrue();
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

      assertionTasks.add(task);
    }

    assertAll(assertionTasks.stream().map(cf -> cf::join));
    assertEntryEquals(store, "e1", "Jigglypuff", "Psyduck");
  }

  @StoreParameterizedTest
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
    assertThat(store.size()).isEqualTo(sizeOf("Snorlax", "Squirtle"));
  }

  @StoreParameterizedTest
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
    assertThat(store.size()).isEqualTo(sizeOf("Mewtwo", "Meowth"));
  }

  /** Removing an entry discards any ongoing edit for this entry. */
  @StoreParameterizedTest
  void removeBeforeCommittingFirstEdit(Store store, StoreContext context) throws IOException {
    var editor = edit(store, "e1");
    writeEntry(editor, "Pickachu", "Jigglypuff");
    editor.commitOnClose();

    assertThat(store.remove("e1")).isTrue();
    assertAbsent(store, context, "e1");

    // Close will silently discard the edit
    editor.close();
    assertAbsent(store, context, "e1");
    assertThat(store.size()).isZero();
  }

  @StoreParameterizedTest
  void clearWhileEditing(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "Jynx", "Raichu");
    writeEntry(store, "e2", "Eevee", "Ditto");

    // Edit existing entries
    var editor1 = edit(store, "e1");
    var editor2 = edit(store, "e2");

    // Edit new entries
    var editor3 = edit(store, "e3");
    var editor4 = edit(store, "e4");

    // Write to first & second edits before clearing
    writeEntry(editor1, "Pichachu", "Snorlax");
    editor1.commitOnClose();
    writeEntry(editor3, "Gengar", "Raichu");
    editor3.commitOnClose();

    store.clear();

    // Write to first & second edits after clearing
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
    assertThat(store.size()).isZero();
  }

  /**
   * An entry removal should not bother (or be bothered by) an already open Viewer. This should also
   * work for DiskStores under the notorious Windows feature that prohibits open files from being
   * deleted unless opened with the FILE_SHARE_DELETE flag, with which files in NIO are normally
   * opened.
   */
  @StoreParameterizedTest
  void removeWhileReading(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "Ditto", "Eevee");

    try (var viewer = view(store, "e1")) {
      assertThat(store.remove("e1")).isTrue();
      assertAbsent(store, context, "e1");
      assertThat(store.size()).isZero();

      // Viewer keeps operating normally
      assertEntryEquals(viewer, "Ditto", "Eevee");
    }
  }

  /**
   * An edit of a removed entry should not bother (or be bothered by) a Viewer opened for the entry
   * before removal.
   */
  @StoreParameterizedTest
  void removeThenWriteWhileReading(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "Ditto", "Eevee");

    try (var viewer = view(store, "e1")) {
      assertThat(store.remove("e1")).isTrue();
      assertAbsent(store, context, "e1");
      assertThat(store.size()).isZero();

      // Viewer keeps operating normally
      assertEntryEquals(viewer, "Ditto", "Eevee");

      // This write creates a new entry, completely unrelated to the removed one
      writeEntry(store, "e1", "Jynx", "Psyduck");
      assertEntryEquals(store, "e1", "Jynx", "Psyduck");

      // First viewer keeps operating normally & reads the entry value it was opened for
      assertEntryEquals(viewer, "Ditto", "Eevee");
    }
  }

  @StoreParameterizedTest
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

  @StoreParameterizedTest
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

  @StoreParameterizedTest
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

  @StoreParameterizedTest
  void removeWhileEditingFromViewer(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "Pickachu", "Mewtwo");

    try (var viewer = view(store, "e1")) {
      try (var editor = edit(viewer)) {
        writeEntry(editor, "Jigglypuff", "Mew");
        editor.commitOnClose();

        // Removal discards the edit
        assertThat(store.remove("e1")).isTrue();
      }
      assertAbsent(store, context, "e1");
      assertThat(store.size()).isZero();

      // Viewer still operates normally
      assertEntryEquals(viewer,  "Pickachu", "Mewtwo");
    }
  }

  @StoreParameterizedTest
  void removeFromViewer(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "Mew", "Mewtwo");

    try (var viewer = view(store, "e1")) {
      assertThat(viewer.removeEntry()).isTrue();
      assertAbsent(store, context, "e1");

      // Viewer still operates normally
      assertEntryEquals(viewer, "Mew", "Mewtwo");
    }
    assertAbsent(store, context, "e1");
  }

  @StoreParameterizedTest
  void removeFromViewerWhileEditingFromViewer(Store store, StoreContext context)
      throws IOException {
    writeEntry(store, "e1", "Pickachu", "Mewtwo");

    try (var viewer = view(store, "e1")) {
      try (var editor = edit(viewer)) {
        writeEntry(editor, "Jigglypuff", "Mew");
        editor.commitOnClose();

        // Removal discards the edit
        assertThat(viewer.removeEntry()).isTrue();
      }
      assertAbsent(store, context, "e1");
      assertThat(store.size()).isZero();

      // Viewer still operates normally
      assertEntryEquals(viewer,  "Pickachu", "Mewtwo");
    }
  }

  @StoreParameterizedTest
  void removeFromViewerAfterRemovingFromStore(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "Eevee", "Ditto");

    try (var viewer = view(store, "e1")) {
      assertThat(store.remove("e1")).isTrue();
      assertThat(viewer.removeEntry()).isFalse();
      assertAbsent(store, context, "e1");
    }
  }

  @StoreParameterizedTest
  void removeFromStaleViewer(Store store) throws IOException {
    writeEntry(store, "e1", "Pikachu", "Ditto");

    try (var viewer = view(store, "e1")) {
      // Rewrite the entry, making the viewer stale
      writeEntry(store, "e1", "Snorlax", "Eevee");

      // A stale viewer can't remove its entry
      assertThat(viewer.removeEntry()).isFalse();
      assertEntryEquals(store, "e1", "Snorlax", "Eevee");
    }
  }

  @StoreParameterizedTest
  void canNotEditFromStaleViewer(Store store) throws IOException {
    writeEntry(store, "e1", "Eevee", "Ditto");

    try (var viewer = view(store, "e1")) {
      // Make viewer stale by writing new values
      writeEntry(store, "e1", "Jynx", "Psyduck");
      assertEntryEquals(store, "e1", "Jynx", "Psyduck");

      assertThat(viewer.edit()).isNull(); // Uneditable
      assertEntryEquals(viewer, "Eevee", "Ditto");
    }
  }

  @StoreParameterizedTest
  void canNotEditFromStaleViewerDueToRemoval(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "Eevee", "Ditto");

    try (var viewer = view(store, "e1")) {
      // Make viewer stale by removing the entry
      assertThat(store.remove("e1")).isTrue();
      assertAbsent(store, context, "e1");

      assertThat(viewer.edit()).isNull(); // Uneditable
      assertEntryEquals(viewer, "Eevee", "Ditto");
    }
  }

  @StoreParameterizedTest
  void canNotEditFromViewerDuringAnOngoingEdit(Store store) throws IOException {
    writeEntry(store, "e1", "Eevee", "Ditto");

    try (var viewer = view(store, "e1")) {
      // Open an editor from the store
      try (var editor = edit(store, "e1")) {
        writeEntry(editor, "Jynx", "Psyduck");
        editor.commitOnClose();

        assertThat(viewer.edit()).isNull(); // Uneditable
      }
      assertEntryEquals(store, "e1", "Jynx", "Psyduck");
      assertEntryEquals(viewer, "Eevee", "Ditto");
    }
  }

  @StoreParameterizedTest
  void removeNonExistingEntry(Store store) throws IOException {
    assertThat(store.remove("e1")).isFalse();
    writeEntry(store, "e1", "Raichu", "Eevee");
    assertThat(store.remove("e1")).isTrue();
    assertThat(store.remove("e1")).isFalse();
  }

  @StoreParameterizedTest
  void iterateEntries(Store store) throws IOException {
    var entries = Map.of(
        "e1", List.of("Pickachu", "Raichu"),
        "e2", List.of("Mew", "Mewtwo"),
        "e3", List.of("Jigglypuff", "Charmander"));
    for (var entry : entries.entrySet()) {
      writeEntry(store, entry.getKey(), entry.getValue().get(0), entry.getValue().get(1));
    }

    var iter = store.iterator();
    for (int i = 0; i < entries.size(); i++) {
      assertThat(iter.hasNext()).isTrue();

      try (var viewer = iter.next()) {
        var entry = entries.get(viewer.key());
        assertThat(entry)
            .withFailMessage("entry came from nowhere: %s", viewer.key())
            .isNotNull();
        assertEntryEquals(store, viewer.key(), entry.get(0), entry.get(1));
      }
    }
    assertThat(iter.hasNext()).isFalse();
  }

  @StoreParameterizedTest
  void removeFromIterator(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "Mew", "Mewtwo");
    writeEntry(store, "e2", "Charmander", "Pickachu");

    // Remove e2 with Iterator::remove
    var iter = store.iterator();
    for (int i = 0; i < 2; i++) {
      assertThat(iter.hasNext()).isTrue();

      try (var viewer = iter.next()) {
        if (viewer.key().equals("e2")) {
          iter.remove();
        } else {
          assertThat(viewer.key()).isEqualTo("e1");
          assertEntryEquals(viewer, "Mew", "Mewtwo");
        }
      }
    }
    assertThat(iter.hasNext()).isFalse();

    assertAbsent(store, context, "e2");
    assertThat(store.size()).isEqualTo(sizeOf("Mew", "Mewtwo"));
  }

  @StoreParameterizedTest
  void removeFromIteratorPointingAtStaleViewer(Store store) throws IOException {
    writeEntry(store, "e1", "Ditto", "Jynx");

    var iter = store.iterator();
    assertThat(iter.hasNext()).isTrue();
    try (var viewer = iter.next()) {
      // Rewrite the entry, making the viewer stale
      writeEntry(store, "e1", "Pikachu", "Psyduck");

      // Nothing is removed as the iterator is pointing at a stale viewer
      iter.remove();
      assertEntryEquals(store, "e1", "Pikachu", "Psyduck");

      // Viewer continues operating normally with stale data
      assertEntryEquals(viewer, "Ditto", "Jynx");
    }
  }

  @StoreParameterizedTest
  @StoreConfig(maxSize = 10, execution = SAME_THREAD)
  void writeExactlyMaxSizeBytesByOneEntry(Store store) throws IOException {
    writeEntry(store, "e1", "12345", "abcde"); // Grow size to 10 bytes
    assertEntryEquals(store, "e1", "12345", "abcde");
    assertThat(store.size()).isEqualTo(10);
  }

  @StoreParameterizedTest
  @StoreConfig(maxSize = 10, execution = SAME_THREAD)
  void writeExactlyMaxSizeBytesByTwoEntries(Store store) throws IOException {
    writeEntry(store, "e1", "12", "abc"); // Grow size to 5 bytes
    writeEntry(store, "e2", "45", "def"); // Grow size to 10 bytes
    assertEntryEquals(store, "e1", "12", "abc");
    assertEntryEquals(store, "e2", "45", "def");
    assertThat(store.size()).isEqualTo(10);
  }

  @StoreParameterizedTest
  @StoreConfig(maxSize = 15, execution = SAME_THREAD)
  void writeBeyondMaxSize(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "12", "abc"); // Grow size to 5 bytes
    writeEntry(store, "e2", "34", "def"); // Grow size to 10 bytes
    assertThat(store.size()).isEqualTo(10);

    // LRU queue: e2, e1
    view(store, "e1").close();

    // Grow size to 16 bytes, causing e2 to be evicted
    writeEntry(store, "e3", "567", "ghi");

    // LRU queue: e1, e3
    assertAbsent(store, context, "e2");
    assertEntryEquals(store, "e1", "12", "abc");
    assertEntryEquals(store, "e3", "567", "ghi");
    assertThat(store.size()).isEqualTo(11);

    // Grows size to 11 + 14 bytes causing both e1 & e3 to be evicted
    writeEntry(store, "e4", "Jynx", "Charmander");

    assertAbsent(store, context, "e1");
    assertAbsent(store, context, "e3");
    assertEntryEquals(store, "e4", "Jynx", "Charmander");
    assertThat(store.size()).isEqualTo(14);
  }

  @StoreParameterizedTest
  @StoreConfig(maxSize = 15, execution = SAME_THREAD)
  void discardedWriteBeyondMaxSize(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "123", "abc"); // Grow size to 6 bytes
    writeEntry(store, "e2", "456", "def"); // Grow size to 12 bytes
    assertThat(store.size()).isEqualTo(12);

    try (var editor = edit(store, "e3")) {
      writeEntry(editor, "alpha", "beta");
      // Don't commit
    }
    assertAbsent(store, context, "e3");
    assertEntryEquals(store, "e1", "123", "abc");
    assertEntryEquals(store, "e2", "456", "def");
    assertThat(store.size()).isEqualTo(12);
  }

  @StoreParameterizedTest
  @StoreConfig(maxSize = 15, execution = SAME_THREAD)
  void discardedByRemovalWriteBeyondMaxSize(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "123", "abc"); // Grow size to 6 bytes
    writeEntry(store, "e2", "456", "def"); // Grow size to 12 bytes
    assertThat(store.size()).isEqualTo(12);

    try (var editor = edit(store, "e3")) {
      writeEntry(editor, "alpha", "beta");
      editor.commitOnClose();

      assertThat(store.remove("e3")).isTrue();
    }
    assertAbsent(store, context, "e3");
    assertEntryEquals(store, "e1", "123", "abc");
    assertEntryEquals(store, "e2", "456", "def");
    assertThat(store.size()).isEqualTo(12);
  }

  @StoreParameterizedTest
  @StoreConfig(maxSize = 14, execution = SAME_THREAD)
  void writeBeyondMaxSizeByMetadataUpdate(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "123", "abc"); // Grow size to 6 bytes
    writeEntry(store, "e2", "456", "def"); // Grow size to 12 bytes
    assertThat(store.size()).isEqualTo(12);

    try (var editor = edit(store, "e1")) {
      // Increase metadata by 3 bytes, causing size to grow to 15 bytes & e2 to be evicted
      setMetadata(editor, "123456");
      editor.commitOnClose();
    }
    assertAbsent(store, context, "e2");
    assertEntryEquals(store, "e1", "123456", "abc");
    assertThat(store.size()).isEqualTo(9);
  }

  @StoreParameterizedTest
  @StoreConfig(maxSize = 14, execution = SAME_THREAD)
  void writeBeyondMaxSizeByDataUpdate(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "123", "abc"); // Grow size to 6 bytes
    writeEntry(store, "e2", "456", "def"); // Grow size to 12 bytes
    assertThat(store.size()).isEqualTo(12);

    try (var editor = edit(store, "e1")) {
      // Increase data by 3 bytes, causing size to grow to 15 bytes & e2 to be evicted
      writeData(editor, "abcdef");
      editor.commitOnClose();
    }
    assertAbsent(store, context, "e2");
    assertEntryEquals(store, "e1", "123", "abcdef");
    assertThat(store.size()).isEqualTo(9);
  }

  @StoreParameterizedTest
  @StoreConfig(maxSize = 18, execution = SAME_THREAD)
  void evictionInLru(Store store, StoreContext context) throws IOException {
    // Grow size to 6 bytes
    // LRU queue: e1
    writeEntry(store, "e1", "aaa", "bbb");
    assertThat(store.size()).isEqualTo(6);

    // Grow size to 12 bytes
    // LRU queue: e1, e2
    writeEntry(store, "e2", "ccc", "ddd");
    assertThat(store.size()).isEqualTo(12);

    // LRU queue: e2, e1
    view(store, "e1").close();

    // Grow size to 18 bytes
    // LRU queue: e2, e1, e3
    writeEntry(store, "e3", "eee", "fff");
    assertThat(store.size()).isEqualTo(18);

    // LRU queue: e2, e3, e1
    view(store, "e1").close();

    // Grow size to 24 bytes, causing e2 to be evicted to get down to 18
    // LRU queue: e3, e1, e4
    writeEntry(store, "e4", "ggg", "hhh");
    assertAbsent(store, context, "e2");
    assertThat(store.size()).isEqualTo(18);

    // LRU queue: e1, e4, e3
    view(store, "e3").close();

    // Grow size to 24 bytes, causing e1 to be evicted to get down to 18 bytes
    // LRU queue: e4, e3, e5
    writeEntry(store, "e5", "iii", "jjj");
    assertAbsent(store, context, "e1");
    assertThat(store.size()).isEqualTo(18);

    // Grow size to 18 + 12 bytes, causing e4 & e3 to be evicted to get down to 18 bytes
    // LRU queue: e5, e6
    writeEntry(store, "e6", "kkk", "lmnopqrst");
    assertAbsent(store, context, "e4", "e3");
    assertThat(store.size()).isEqualTo(18);

    // LRU queue: e6, e5
    view(store, "e5").close();

    // Grow size to 24 bytes, causing e6 to be evicted to get down to 12
    // LRU queue: e5, e7
    writeEntry(store, "e7", "uuu", "vvv");
    assertAbsent(store, context, "e6");
    assertThat(store.size()).isEqualTo(12);

    // Grow size to 18 bytes, causing nothing to be evicted since size is within bounds
    // LRU queue: e5, e7, e8
    writeEntry(store, "e8", "xxx", "~!@");
    assertThat(store.size()).isEqualTo(18);

    // Write one 18 bytes entry, causing all other entries to be evicted
    writeEntry(store, "e9", "Ricardo", "all is mine");
    assertAbsent(store, context, "e5, e7", "e8");
    assertThat(store.size()).isEqualTo(18);
  }

  /**
   * Test that the store only takes a snapshot of the passed metadata buffer such that mutations on
   * it do not affect the store.
   */
  @StoreParameterizedTest
  void mutateMetadataBufferAfterPassingToEditor(Store store) throws IOException {
    var metadata = UTF_8.encode("420");
    try (var editor = edit(store, "e1")) {
      editor.metadata(metadata);
      // Metadata is consumed
      assertThat(metadata.hasRemaining()).isFalse();

      metadata.rewind().put(new byte[]{'6', '9'});
      editor.commitOnClose();
    }
    assertEntryEquals(store, "e1", "420", "");
  }

  /** Ensure the position of the metadata buffer a viewer returns can be changed independently. */
  @StoreParameterizedTest
  void mutatePositionOfMetadataBufferReturnedFromViewer(Store store) throws IOException {
    writeEntry(store, "e1", "555", "");
    try (var viewer = view(store, "e1")) {
      var metadata = viewer.metadata();
      metadata.position(metadata.limit()); // Consume
      assertThat(viewer.metadata().position()).isZero();
      assertEntryEquals(viewer, "555", "");
    }
  }

  @StoreParameterizedTest
  void metadataBufferReturnedFromViewerIsReadOnly(Store store) throws IOException {
    writeEntry(store, "e1", "555", "");
    try (var viewer = view(store, "e1")) {
      assertThat(viewer.metadata().isReadOnly()).isTrue();
    }
  }

  @StoreParameterizedTest
  void readBeyondDataSize(Store store) throws IOException {
    writeEntry(store, "e1", "Jynx", "Mew");
    try (var viewer = view(store, "e1")) {
      int read1 = viewer.readAsync(viewer.dataSize(), ByteBuffer.allocate(1)).join();
      assertThat(read1).isEqualTo(-1);

      int read2 = viewer.readAsync(viewer.dataSize(), ByteBuffer.allocate(1)).join();
      assertThat(read2).isEqualTo(-1);
    }
  }

  @StoreParameterizedTest
  void readWithNegativePosition(Store store) throws IOException {
    writeEntry(store, "e1", "Jynx", "Mew");
    try (var viewer = view(store, "e1")) {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> viewer.readAsync(-1, ByteBuffer.allocate(0)));
    }
  }

  @StoreParameterizedTest
  void writeWithIllegalPosition(Store store) throws IOException {
    try (var editor = edit(store, "e1")) {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> editor.writeAsync(-1, ByteBuffer.allocate(1)));

      // Editor prohibits gabs between writes
      editor.writeAsync(0, ByteBuffer.allocate(1)).join();
      assertThatIllegalArgumentException()
          .isThrownBy(() -> editor.writeAsync(2, ByteBuffer.allocate(1)));
    }
  }

  @StoreParameterizedTest
  void editorRefusesWritesAfterCommitOnClose(Store store) throws IOException {
    try (var editor = edit(store, "e1")) {
      writeEntry(editor, "Ditto", "Jynx");
      editor.commitOnClose();
      assertThatIllegalStateException()
          .isThrownBy(() -> editor.metadata(ByteBuffer.allocate(0)));
      assertThatIllegalStateException()
          .isThrownBy(() -> editor.writeAsync(0, ByteBuffer.allocate(0)));
    }
  }

  @StoreParameterizedTest
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

  @StoreParameterizedTest
  void randomAccess(Store store) throws IOException {
    writeEntry(store, "e1", "", "1234");
    try (var viewer = view(store, "e1")) {
      var buffer = ByteBuffer.allocate(1);
      for (int i = 3; i >= 0; i--) {
        assertThat(viewer.readAsync(i, buffer.clear()).join()).isEqualTo(1);
        assertThat(buffer.flip().get() - '0').isEqualTo(i + 1);
      }
    }
  }
}
