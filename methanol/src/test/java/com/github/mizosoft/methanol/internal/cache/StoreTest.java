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
import org.junit.jupiter.api.Disabled;
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
  void concurrentViewers(Store store, Executor executor) throws IOException {
    writeEntry(store, "e1", "Pokemon", "Charmander");

    int viewerCount = 10;
    var arrival = new CyclicBarrier(viewerCount);
    var assertionTasks = new ArrayList<CompletableFuture<Void>>();
    for (int i = 0; i < viewerCount; i++) {
      assertionTasks.add(
          Unchecked.runAsync(
              () -> {
                awaitUninterruptibly(arrival);
                assertEntryEquals(store, "e1", "Pokemon", "Charmander");
              },
              executor));
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
    }
    assertAbsent(store, context, "e1");
    assertThat(store.size()).isZero();
  }

  @StoreParameterizedTest
  void discardSecondEdit(Store store) throws IOException {
    writeEntry(store, "e1", "Mew", "Mewtwo");
    try (var editor = edit(store, "e1")) {
      writeEntry(editor, "Jynx", "Eevee");
    }
    assertEntryEquals(store, "e1", "Mew", "Mewtwo");
    assertThat(store.size()).isEqualTo(sizeOf("Mew", "Mewtwo"));
  }

  @Disabled("Till spec change")
  @StoreParameterizedTest
  void discardEditAfterRemove(Store store, StoreContext context) throws IOException {
    try (var editor = edit(store, "e1")) {
      writeEntry(editor, "Jynx", "Mew");
      assertThat(store.remove("e1")).isTrue();
    }
    assertAbsent(store, context, "e1");
  }

  @StoreParameterizedTest
  @ExecutorConfig(CACHED_POOL)
  void contendedEdit(Store store, Executor executor) throws IOException {
    int threadCount = 10;
    var arrival = new CyclicBarrier(threadCount);
    var endLatch = new CountDownLatch(threadCount);
    var acquired = new AtomicBoolean();
    var assertionTasks = new ArrayList<CompletableFuture<Void>>();
    for (int i = 0; i < threadCount; i++) {
      assertionTasks.add(
          Unchecked.runAsync(
              () -> {
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
              },
              executor));
    }

    assertAll(assertionTasks.stream().map(cf -> cf::join));
    assertEntryEquals(store, "e1", "Jigglypuff", "Psyduck");
  }

  @StoreParameterizedTest
  void entryRemainsUnreadableTillFirstEditCompletes(Store store) throws IOException {
    try (var editor = edit(store, "e1")) {
      setMetadata(editor, "Snorlax");
      assertUnreadable(store, "e1");

      writeData(editor, "Squirtle");
      editor.commitOnClose();

      assertUnreadable(store, "e1");
    }
    assertEntryEquals(store, "e1", "Snorlax", "Squirtle");
    assertThat(store.size()).isEqualTo(sizeOf("Snorlax", "Squirtle"));
  }

  @StoreParameterizedTest
  void entryRemainsUnchangedTillSecondEditCompletes(Store store) throws IOException {
    writeEntry(store, "e1", "Mew", "Eevee");
    try (var editor = edit(store, "e1")) {
      setMetadata(editor, "Mewtwo");

      assertEntryEquals(store, "e1", "Mew", "Eevee");

      writeData(editor, "Meowth");
      editor.commitOnClose();

      assertEntryEquals(store, "e1", "Mew", "Eevee");
    }
    assertEntryEquals(store, "e1", "Mewtwo", "Meowth");
    assertThat(store.size()).isEqualTo(sizeOf("Mewtwo", "Meowth"));
  }

  /** Removing an entry discards any ongoing edit for this entry. */
  @Disabled("Till spec change")
  @StoreParameterizedTest
  void removeBeforeCommittingFirstEdit(Store store, StoreContext context) throws IOException {
    try (var editor = edit(store, "e1")) {
      writeEntry(editor, "Pickachu", "Jigglypuff");
      assertThat(store.remove("e1")).isTrue();
      assertAbsent(store, context, "e1");
    }

    // Closing silently discards the edit.
    assertAbsent(store, context, "e1");
    assertThat(store.size()).isZero();
  }

  @Disabled("Till spec change")
  @StoreParameterizedTest
  void clearWhileEditing(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "Jynx", "Raichu");
    writeEntry(store, "e2", "Eevee", "Ditto");
    try (var editor1 = edit(store, "e1");
        var editor2 = edit(store, "e2");
        var editor3 = edit(store, "e3");
        var editor4 = edit(store, "e4")) {
      // Write to first & second edits before clearing.
      writeEntry(editor1, "Pichachu", "Snorlax");
      editor1.commitOnClose();
      writeEntry(editor3, "Gengar", "Raichu");
      editor3.commitOnClose();

      store.clear();

      // Write to first & second edits after clearing.
      writeEntry(editor2, "Squirtle", "Charmander");
      editor2.commitOnClose();
      writeEntry(editor4, "Mew", "Mewtwo");
      editor4.commitOnClose();
    }

    // Neither existing nor new entries receive committed values.
    assertAbsent(store, context, "e1");
    assertAbsent(store, context, "e2");
    assertAbsent(store, context, "e3");
    assertAbsent(store, context, "e4");
    assertThat(store.size()).isZero();
  }

  /**
   * An entry removal should not bother (or be bothered by) an already open Viewer (isolation
   * property in ACID). This should also work for DiskStores under the notorious Windows feature
   * that prohibits open files from being deleted unless opened with the FILE_SHARE_DELETE flag,
   * with which files in NIO are normally opened.
   */
  @StoreParameterizedTest
  void removeWhileReading(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "Ditto", "Eevee");
    try (var viewer = view(store, "e1")) {
      assertThat(store.remove("e1")).isTrue();
      assertAbsent(store, context, "e1");
      assertThat(store.size()).isZero();

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

      assertEntryEquals(viewer, "Ditto", "Eevee");

      writeEntry(store, "e1", "Jynx", "Psyduck");
      assertEntryEquals(store, "e1", "Jynx", "Psyduck");

      assertEntryEquals(viewer, "Ditto", "Eevee");
    }
  }

  @StoreParameterizedTest
  void updateMetadataWhileReading(Store store) throws IOException {
    writeEntry(store, "e1", "Pickachu", "Psyduck");
    try (var viewer = view(store, "e1")) {
      try (var editor = edit(store, "e1")) {
        setMetadata(editor, "Raichu");
        editor.commitOnClose();

        assertEntryEquals(viewer, "Pickachu", "Psyduck");
      }
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

      assertEntryEquals(viewer, "Pickachu", "Snorlax");
    }
  }

  @StoreParameterizedTest
  void discardEditFromViewer(Store store) throws IOException {
    writeEntry(store, "e1", "Ditto", "Eevee");
    try (var viewer = view(store, "e1")) {
      try (var editor = edit(viewer)) {
        writeEntry(editor, "Jigglypuff", "Mew");
      }

      assertEntryEquals(viewer, "Ditto", "Eevee");
      assertEntryEquals(store, "e1", "Ditto", "Eevee");
    }
  }

  @Disabled("Till spec change")
  @StoreParameterizedTest
  void removeWhileEditingFromViewer(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "Pickachu", "Mewtwo");
    try (var viewer = view(store, "e1")) {
      try (var editor = edit(viewer)) {
        writeEntry(editor, "Jigglypuff", "Mew");
        editor.commitOnClose();

        assertThat(store.remove("e1")).isTrue();
      }

      assertAbsent(store, context, "e1");
      assertThat(store.size()).isZero();

      assertEntryEquals(viewer, "Pickachu", "Mewtwo");
    }
  }

  @StoreParameterizedTest
  void removeFromViewer(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "Mew", "Mewtwo");
    try (var viewer = view(store, "e1")) {
      assertThat(viewer.removeEntry()).isTrue();
      assertAbsent(store, context, "e1");
      assertEntryEquals(viewer, "Mew", "Mewtwo");
    }
    assertAbsent(store, context, "e1");
  }

  @Disabled("Till spec change")
  @StoreParameterizedTest
  void removeFromViewerWhileEditingFromViewer(Store store, StoreContext context)
      throws IOException {
    writeEntry(store, "e1", "Pickachu", "Mewtwo");
    try (var viewer = view(store, "e1")) {
      try (var editor = edit(viewer)) {
        writeEntry(editor, "Jigglypuff", "Mew");
        editor.commitOnClose();

        assertThat(viewer.removeEntry()).isTrue();
      }
      assertAbsent(store, context, "e1");
      assertThat(store.size()).isZero();
      assertEntryEquals(viewer, "Pickachu", "Mewtwo");
    }
  }

  @StoreParameterizedTest
  void removeFromViewerAfterRemovingFromStore(Store store, StoreContext context)
      throws IOException {
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
      // Rewrite the entry, making the viewer stale.
      writeEntry(store, "e1", "Snorlax", "Eevee");

      // A stale viewer can't remove its entry.
      assertThat(viewer.removeEntry()).isFalse();
      assertEntryEquals(store, "e1", "Snorlax", "Eevee");
    }
  }

  @StoreParameterizedTest
  void canNotEditFromStaleViewer(Store store) throws IOException {
    writeEntry(store, "e1", "Eevee", "Ditto");
    try (var viewer = view(store, "e1")) {
      // Make viewer stale by writing new values.
      writeEntry(store, "e1", "Jynx", "Psyduck");
      assertEntryEquals(store, "e1", "Jynx", "Psyduck");

      assertThat(viewer.edit()).isNull(); // Uneditable.
      assertEntryEquals(viewer, "Eevee", "Ditto");
    }
  }

  @StoreParameterizedTest
  void canNotEditFromStaleViewerDueToRemoval(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "Eevee", "Ditto");
    try (var viewer = view(store, "e1")) {
      // Make viewer stale by removing the entry.
      assertThat(store.remove("e1")).isTrue();
      assertAbsent(store, context, "e1");

      assertThat(viewer.edit()).isNull(); // Uneditable.
      assertEntryEquals(viewer, "Eevee", "Ditto");
    }
  }

  @StoreParameterizedTest
  void canNotEditFromViewerDuringAnOngoingEdit(Store store) throws IOException {
    writeEntry(store, "e1", "Eevee", "Ditto");
    try (var viewer = view(store, "e1")) {
      try (var ignored = edit(store, "e1")) {
        assertThat(viewer.edit()).isNull(); // Uneditable.
      }
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
    var entries =
        Map.of(
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
        assertThat(entry).withFailMessage("entry came from nowhere: %s", viewer.key()).isNotNull();
        assertEntryEquals(store, viewer.key(), entry.get(0), entry.get(1));
      }
    }
    assertThat(iter.hasNext()).isFalse();
  }

  @StoreParameterizedTest
  void removeFromIterator(Store store, StoreContext context) throws IOException {
    writeEntry(store, "e1", "Mew", "Mewtwo");
    writeEntry(store, "e2", "Charmander", "Pickachu");

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
      // Rewrite the entry, making the viewer stale.
      writeEntry(store, "e1", "Pikachu", "Psyduck");

      // Nothing is removed as the iterator is pointing to a stale viewer.
      iter.remove();
      assertEntryEquals(store, "e1", "Pikachu", "Psyduck");

      // Viewer continues operating normally with stale data.
      assertEntryEquals(viewer, "Ditto", "Jynx");
    }
  }

  /**
   * Test that the viewer only takes a snapshot of the passed metadata buffer such that mutations on
   * it do not affect the viewer.
   */
  @StoreParameterizedTest
  void mutateMetadataBufferAfterPassingToEditor(Store store) throws IOException {
    var metadata = UTF_8.encode("420");
    try (var editor = edit(store, "e1")) {
      editor.metadata(metadata);
      // Metadata is consumed.
      assertThat(metadata.hasRemaining()).isFalse();

      metadata.rewind().put(new byte[] {'6', '9'});
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
      metadata.position(metadata.limit()); // Consume.
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

  @Disabled("Till spec change")
  @StoreParameterizedTest
  void writeWithIllegalPosition(Store store) throws IOException {
    try (var editor = edit(store, "e1")) {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> editor.writeAsync(-1, ByteBuffer.allocate(1)));

      // Editor prohibits gabs between writes.
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
      assertThatIllegalStateException().isThrownBy(() -> editor.metadata(ByteBuffer.allocate(0)));
      assertThatIllegalStateException()
          .isThrownBy(() -> editor.writeAsync(0, ByteBuffer.allocate(0)));
    }
  }

  @Disabled("Till spec change")
  @StoreParameterizedTest
  void editorDiscardsWritesAfterClosure(Store store, StoreContext context) throws IOException {
    var editor = edit(store, "e1");
    try (editor) {
      writeEntry(editor, "Ditto", "Jynx");
    }

    // This write goes into oblivion.
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
