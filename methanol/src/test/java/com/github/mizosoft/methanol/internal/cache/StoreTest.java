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

package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.testing.TestUtils.awaitUnchecked;
import static com.github.mizosoft.methanol.testing.store.StoreTesting.assertEntryEquals;
import static com.github.mizosoft.methanol.testing.store.StoreTesting.assertUnreadable;
import static com.github.mizosoft.methanol.testing.store.StoreTesting.commit;
import static com.github.mizosoft.methanol.testing.store.StoreTesting.edit;
import static com.github.mizosoft.methanol.testing.store.StoreTesting.sizeOf;
import static com.github.mizosoft.methanol.testing.store.StoreTesting.view;
import static com.github.mizosoft.methanol.testing.store.StoreTesting.write;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.function.Unchecked;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorSpec;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.Logging;
import com.github.mizosoft.methanol.testing.store.StoreConfig.StoreType;
import com.github.mizosoft.methanol.testing.store.StoreContext;
import com.github.mizosoft.methanol.testing.store.StoreExtension;
import com.github.mizosoft.methanol.testing.store.StoreExtension.StoreParameterizedTest;
import com.github.mizosoft.methanol.testing.store.StoreSpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({StoreExtension.class, ExecutorExtension.class})
class StoreTest {
  static {
    Logging.disable(DiskStore.class);
  }

  @StoreParameterizedTest
  void writeThenRead(Store store) throws IOException {
    write(store, "e1", "Lucario", "Jynx");
    assertEntryEquals(store, "e1", "Lucario", "Jynx");
    assertThat(store.size()).isEqualTo(sizeOf("Lucario", "Jynx"));
  }

  @StoreParameterizedTest
  void writeThenReadTwice(Store store) throws IOException {
    write(store, "e1", "Lucario", "Jynx");
    assertEntryEquals(store, "e1", "Lucario", "Jynx");

    write(store, "e2", "Mew", "Mewtwo");
    assertEntryEquals(store, "e2", "Mew", "Mewtwo");
    assertThat(store.size()).isEqualTo(sizeOf("Lucario", "Jynx", "Mew", "Mewtwo"));
  }

  @StoreParameterizedTest
  @ExecutorSpec(ExecutorType.CACHED_POOL)
  void concurrentViewers(Store store, Executor executor) throws IOException {
    write(store, "e1", "Pokemon", "Charmander");

    int viewerCount = 10;
    var arrival = new CyclicBarrier(viewerCount);
    var assertionTasks = new ArrayList<CompletableFuture<Void>>();
    for (int i = 0; i < viewerCount; i++) {
      assertionTasks.add(
          Unchecked.runAsync(
              () -> {
                arrival.await();
                assertEntryEquals(store, "e1", "Pokemon", "Charmander");
              },
              executor));
    }
    assertAll(assertionTasks.stream().map(cf -> cf::get));
  }

  @StoreParameterizedTest
  void writeMetadataWithoutData(Store store) throws IOException {
    try (var editor = edit(store, "e1")) {
      commit(editor, "abc");
    }
    assertEntryEquals(store, "e1", "abc", "");
  }

  @StoreParameterizedTest
  void writeNothingOnDiscardedFirstEdit(Store store) throws IOException {
    edit(store, "e1").close();
    assertUnreadable(store, "e1");
    assertThat(store.size()).isZero();
  }

  @StoreParameterizedTest
  void updateMetadataOnSecondEdit(Store store) throws IOException {
    write(store, "e1", "Mew", "Pickachu");
    try (var editor = edit(store, "e1")) {
      commit(editor, "Mewtwo");
    }
    assertEntryEquals(store, "e1", "Mewtwo", "Pickachu");
    assertThat(store.size()).isEqualTo(sizeOf("Mewtwo", "Pickachu"));
  }

  @StoreParameterizedTest
  void clearMetadataOnSecondEdit(Store store) throws IOException {
    write(store, "e1", "Mr Mime", "Ditto");
    try (var editor = edit(store, "e1")) {
      commit(editor, "");
    }
    assertEntryEquals(store, "e1", "", "Ditto");
    assertThat(store.size()).isEqualTo(sizeOf("", "Ditto"));
  }

  @StoreParameterizedTest
  void clearDataOnSecondEdit(Store store) throws IOException {
    write(store, "e1", "Jynx", "Charmander");
    try (var editor = edit(store, "e1")) {
      commit(editor, "Jynx", "");
    }
    assertEntryEquals(store, "e1", "Jynx", "");
    assertThat(store.size()).isEqualTo(sizeOf("Jynx"));
  }

  @StoreParameterizedTest
  void writeThenRemove(Store store) throws IOException {
    write(store, "e1", "Jigglypuff", "Pickachu");
    assertThat(store.remove("e1")).isTrue();
    assertUnreadable(store, "e1");
    assertThat(store.size()).isZero();
  }

  @StoreParameterizedTest
  void writeThenClear(Store store) throws IOException {
    write(store, "e1", "methanol", "CH3OH");
    write(store, "e2", "ethanol", "C2H5OH");

    store.clear();
    assertUnreadable(store, "e1");
    assertUnreadable(store, "e2");
    assertThat(store.iterator().hasNext()).isFalse();
    assertThat(store.size()).isZero();
  }

  @StoreParameterizedTest
  void writeTwice(Store store) throws IOException {
    write(store, "e1", "Mew", "Pickachu");
    write(store, "e1", "Mewtwo", "Eevee");
    assertEntryEquals(store, "e1", "Mewtwo", "Eevee");
    assertThat(store.size()).isEqualTo(sizeOf("Mewtwo", "Eevee"));
  }

  @StoreParameterizedTest
  void discardEdit(Store store) throws IOException {
    try (var editor = edit(store, "e1")) {
      write(editor, "Eevee");
    }
    assertUnreadable(store, "e1");
    assertThat(store.size()).isZero();
  }

  @StoreParameterizedTest
  void discardSecondEdit(Store store) throws IOException {
    write(store, "e1", "Mew", "Mewtwo");
    try (var editor = edit(store, "e1")) {
      write(editor, "Eevee");
    }
    assertEntryEquals(store, "e1", "Mew", "Mewtwo");
    assertThat(store.size()).isEqualTo(sizeOf("Mew", "Mewtwo"));
  }

  @StoreParameterizedTest
  void commitAfterRemove(Store store, StoreContext context) throws IOException {
    try (var editor = edit(store, "e1")) {
      write(editor, "Mew");
      assertThat(store.remove("e1")).isTrue();
      if (context.config().storeType() == StoreType.MEMORY) {
        commit(editor, "Ditto");
      } else {
        assertThatIllegalStateException().isThrownBy((() -> commit(editor, "Ditto")));
      }
    }
    assertUnreadable(store, "e1");
  }

  @StoreParameterizedTest
  void commitAfterRemoveOnSecondEdit(Store store, StoreContext context) throws IOException {
    write(store, "e1", "Pikachu", "Mewtwo");
    try (var editor = edit(store, "e1")) {
      write(editor, "Mew");
      assertThat(store.remove("e1")).isTrue();
      if (context.config().storeType() == StoreType.MEMORY) {
        commit(editor, "Ditto");
      } else {
        assertThatIllegalStateException().isThrownBy((() -> commit(editor, "Ditto")));
      }
    }
    assertUnreadable(store, "e1");
  }

  @StoreParameterizedTest
  void commitFromViewerAfterRemove(Store store, StoreContext context) throws IOException {
    write(store, "e1", "Pikachu", "Mewtwo");
    try (var viewer = view(store, "e1")) {
      try (var editor = edit(viewer)) {
        write(editor, "Mew");
        assertThat(store.remove("e1")).isTrue();
        if (context.config().storeType() == StoreType.MEMORY) {
          commit(editor, "Ditto");
        } else {
          assertThatIllegalStateException().isThrownBy(() -> commit(editor, "Ditto"));
        }
      }
    }
    assertUnreadable(store, "e1");
  }

  @StoreParameterizedTest
  void commitFromViewerAfterRemoveFromViewer(Store store, StoreContext context) throws IOException {
    write(store, "e1", "Pikachu", "Mewtwo");
    try (var viewer = view(store, "e1")) {
      try (var editor = edit(viewer)) {
        write(editor, "Mew");
        assertThat(viewer.removeEntry()).isTrue();
        if (context.config().storeType() == StoreType.MEMORY) {
          commit(editor, "Ditto");
        } else {
          assertThatIllegalStateException().isThrownBy(() -> commit(editor, "Ditto"));
        }
      }
    }
    assertUnreadable(store, "e1");
  }

  @StoreParameterizedTest
  @ExecutorSpec(ExecutorType.CACHED_POOL)
  void contendedEdit(Store store, Executor executor) throws IOException {
    int threadCount = 10;
    var arrival = new CyclicBarrier(threadCount);
    var endLatch = new CountDownLatch(threadCount);
    var acquiredEdit = new AtomicBoolean();
    var assertionTasks = new ArrayList<CompletableFuture<Void>>();
    for (int i = 0; i < threadCount; i++) {
      assertionTasks.add(
          Unchecked.runAsync(
              () -> {
                arrival.await();

                Optional<Editor> editor;
                try {
                  editor = Utils.get(store.edit("e1", FlowSupport.SYNC_EXECUTOR));
                  assertThat(editor.isEmpty() || acquiredEdit.compareAndSet(false, true))
                      .withFailMessage("more than one thread got an editor!")
                      .isTrue();
                  editor.ifPresent(
                      Unchecked.consumer(localEditor -> write(localEditor, "Psyduck")));
                } finally {
                  endLatch.countDown();
                }

                editor.ifPresent(
                    localEditor -> {
                      try (localEditor) {
                        // Keep ownership of the editor (if owned) till all threads finish.
                        awaitUnchecked(endLatch);
                        commit(localEditor, "Jigglypuff");
                      } catch (IOException e) {
                        throw new UncheckedIOException(e);
                      }
                    });
              },
              executor));
    }

    assertAll(assertionTasks.stream().map(cf -> cf::get));
    assertEntryEquals(store, "e1", "Jigglypuff", "Psyduck");
  }

  @StoreParameterizedTest
  void entryRemainsUnreadableTillFirstEditCompletes(Store store) throws IOException {
    try (var editor = edit(store, "e1")) {
      assertUnreadable(store, "e1");
      write(editor, "Squirtle");
      assertUnreadable(store, "e1");
      commit(editor, "Snorlax");
    }
    assertEntryEquals(store, "e1", "Snorlax", "Squirtle");
    assertThat(store.size()).isEqualTo(sizeOf("Snorlax", "Squirtle"));
  }

  @StoreParameterizedTest
  void entryRemainsUnchangedTillSecondEditCompletes(Store store) throws IOException {
    write(store, "e1", "Mew", "Eevee");
    try (var editor = edit(store, "e1")) {
      assertEntryEquals(store, "e1", "Mew", "Eevee");
      write(editor, "Meowth");
      assertEntryEquals(store, "e1", "Mew", "Eevee");
      commit(editor, "Mewtwo");

      // commit(...) takes effect before closing the editor.
      assertEntryEquals(store, "e1", "Mewtwo", "Meowth");
      assertThat(store.size()).isEqualTo(sizeOf("Mewtwo", "Meowth"));
    }
    assertEntryEquals(store, "e1", "Mewtwo", "Meowth");
    assertThat(store.size()).isEqualTo(sizeOf("Mewtwo", "Meowth"));
  }

  /**
   * An entry removal should not bother (or be bothered by) an already open Viewer (isolation
   * property in ACID). This should also work for DiskStores under the notorious Windows feature
   * that prohibits open files from being deleted unless opened with the FILE_SHARE_DELETE flag,
   * with which files in NIO are normally opened.
   */
  @StoreParameterizedTest
  void removeWhileReading(Store store) throws IOException {
    write(store, "e1", "Ditto", "Eevee");
    try (var viewer = view(store, "e1")) {
      assertThat(store.remove("e1")).isTrue();
      assertUnreadable(store, "e1");
      assertThat(store.size()).isZero();

      // Viewer continues to read the entry it was opened for.
      assertEntryEquals(viewer, "Ditto", "Eevee");
    }
  }

  /**
   * An edit of a removed entry should not bother (or be bothered by) a Viewer opened for the entry
   * before removal.
   */
  @StoreParameterizedTest
  void removeThenWriteWhileReading(Store store) throws IOException {
    write(store, "e1", "Ditto", "Eevee");
    try (var viewer = view(store, "e1")) {
      assertThat(store.remove("e1")).isTrue();
      assertUnreadable(store, "e1");
      assertThat(store.size()).isZero();
      assertEntryEquals(viewer, "Ditto", "Eevee");

      write(store, "e1", "Jynx", "Psyduck");
      assertEntryEquals(store, "e1", "Jynx", "Psyduck");

      // Viewer continues to read the entry it was opened for.
      assertEntryEquals(viewer, "Ditto", "Eevee");
    }
  }

  @StoreParameterizedTest
  void updateMetadataWhileReading(Store store) throws IOException {
    write(store, "e1", "Pickachu", "Psyduck");
    try (var viewer = view(store, "e1")) {
      try (var editor = edit(store, "e1")) {
        commit(editor, "Raichu");
        assertEntryEquals(viewer, "Pickachu", "Psyduck");
      }
      assertEntryEquals(store, "e1", "Raichu", "Psyduck");
      assertEntryEquals(viewer, "Pickachu", "Psyduck");
    }
  }

  @StoreParameterizedTest
  void editFromViewer(Store store) throws IOException {
    write(store, "e1", "Pickachu", "Snorlax");
    try (var viewer = view(store, "e1")) {
      try (var editor = edit(viewer)) {
        commit(editor, "Mewtwo", "Squirtle");
        assertEntryEquals(viewer, "Pickachu", "Snorlax");
      }
      assertEntryEquals(store, "e1", "Mewtwo", "Squirtle");
      assertEntryEquals(viewer, "Pickachu", "Snorlax");
    }
  }

  @StoreParameterizedTest
  void discardEditFromViewer(Store store) throws IOException {
    write(store, "e1", "Ditto", "Eevee");
    try (var viewer = view(store, "e1")) {
      try (var editor = edit(viewer)) {
        write(editor, "Mew");
      }

      assertEntryEquals(viewer, "Ditto", "Eevee");
      assertEntryEquals(store, "e1", "Ditto", "Eevee");
    }
  }

  @StoreParameterizedTest
  void removeWhileEditingFromViewer(Store store) throws IOException {
    write(store, "e1", "Pickachu", "Mewtwo");
    try (var viewer = view(store, "e1")) {
      try (var editor = edit(viewer)) {
        commit(editor, "Jigglypuff", "Mew");
        assertThat(store.remove("e1")).isTrue();
      }

      assertUnreadable(store, "e1");
      assertThat(store.size()).isZero();

      assertEntryEquals(viewer, "Pickachu", "Mewtwo");
    }
  }

  @StoreParameterizedTest
  void removeFromViewer(Store store) throws IOException {
    write(store, "e1", "Mew", "Mewtwo");
    try (var viewer = view(store, "e1")) {
      assertThat(viewer.removeEntry()).isTrue();
      assertUnreadable(store, "e1");

      // The viewer keeps operating normally.
      assertEntryEquals(viewer, "Mew", "Mewtwo");
    }
    assertUnreadable(store, "e1");
  }

  @StoreParameterizedTest
  void removeFromViewerWhileEditingFromViewer(Store store, StoreContext context)
      throws IOException {
    write(store, "e1", "Pickachu", "Mewtwo");
    try (var viewer = view(store, "e1")) {
      try (var editor = edit(viewer)) {
        write(editor, "Mew");
        assertThat(viewer.removeEntry()).isTrue();
        if (context.config().storeType() == StoreType.MEMORY) {
          commit(editor, "Ditto");
        } else {
          assertThatIllegalStateException().isThrownBy((() -> commit(editor, "Ditto")));
        }
      }
      assertUnreadable(store, "e1");
      assertThat(store.size()).isZero();
      assertEntryEquals(viewer, "Pickachu", "Mewtwo");
    }
  }

  @StoreParameterizedTest
  void removeFromViewerAfterRemovingFromStore(Store store) throws IOException {
    write(store, "e1", "Eevee", "Ditto");
    try (var viewer = view(store, "e1")) {
      assertThat(store.remove("e1")).isTrue();
      assertThat(viewer.removeEntry()).isFalse();
      assertUnreadable(store, "e1");
    }
  }

  @StoreParameterizedTest
  void removeFromStaleViewer(Store store) throws IOException {
    write(store, "e1", "Pikachu", "Ditto");
    try (var viewer = view(store, "e1")) {
      // Rewrite the entry, making the viewer stale.
      write(store, "e1", "Snorlax", "Eevee");

      // A stale viewer can't remove its entry.
      assertThat(viewer.removeEntry()).isFalse();
      assertEntryEquals(store, "e1", "Snorlax", "Eevee");
    }
  }

  @StoreParameterizedTest
  void editFromStaleViewer(Store store) throws IOException {
    write(store, "e1", "Eevee", "Ditto");
    try (var viewer = view(store, "e1")) {
      // Make viewer stale by writing new values.
      write(store, "e1", "Jynx", "Psyduck");
      assertEntryEquals(store, "e1", "Jynx", "Psyduck");

      assertThat(viewer.edit()).isEmpty(); // Uneditable.
      assertEntryEquals(viewer, "Eevee", "Ditto");
    }
  }

  @StoreParameterizedTest
  void editFromStaleViewerDueToRemoval(Store store) throws IOException {
    write(store, "e1", "Eevee", "Ditto");
    try (var viewer = view(store, "e1")) {
      // Make viewer stale by removing the entry.
      assertThat(store.remove("e1")).isTrue();
      assertUnreadable(store, "e1");

      assertThat(viewer.edit()).isEmpty(); // Uneditable.
      assertEntryEquals(viewer, "Eevee", "Ditto");
    }
  }

  @StoreParameterizedTest
  void editFromViewerDuringAnOngoingEdit(Store store) throws IOException {
    write(store, "e1", "Eevee", "Ditto");
    try (var viewer = view(store, "e1")) {
      try (var ignored = edit(store, "e1")) {
        assertThat(viewer.edit()).isEmpty(); // Uneditable.
      }
    }
  }

  @StoreParameterizedTest
  void removeNonExistingEntry(Store store) throws IOException {
    assertThat(store.remove("e1")).isFalse();
    write(store, "e1", "Raichu", "Eevee");
    assertThat(store.remove("e1")).isTrue();
    assertThat(store.remove("e1")).isFalse();
  }

  @StoreParameterizedTest
  void iterateOverEntries(Store store) throws IOException {
    var entries =
        Map.of(
            "e1", List.of("Pickachu", "Raichu"),
            "e2", List.of("Mew", "Mewtwo"),
            "e3", List.of("Jigglypuff", "Charmander"));
    for (var entry : entries.entrySet()) {
      write(store, entry.getKey(), entry.getValue().get(0), entry.getValue().get(1));
    }

    var iter = store.iterator();
    for (int i = 0; i < entries.size(); i++) {
      assertThat(iter.hasNext()).isTrue();
      try (var viewer = iter.next()) {
        var entry = entries.get(viewer.key());
        assertThat(entry).withFailMessage("entry <%s> came from nowhere", viewer.key()).isNotNull();
        assertEntryEquals(store, viewer.key(), entry.get(0), entry.get(1));
      }
    }
    assertThat(iter.hasNext()).isFalse();
  }

  @StoreParameterizedTest
  void multipleReadersReadFromIndependentPositions(Store store) throws IOException {
    write(store, "e1", "a", "abcd");
    try (var viewer = view(store, "e1")) {
      var firstReader = viewer.newReader();
      var buffer = ByteBuffer.allocate(2);
      assertThat(firstReader.read(buffer)).isEqualTo(2);
      assertThat(UTF_8.decode(buffer.flip()).toString()).isEqualTo("ab");

      var secondReader = viewer.newReader();
      assertThat(secondReader.read(buffer.clear())).isEqualTo(2);
      assertThat(UTF_8.decode(buffer.flip()).toString()).isEqualTo("ab");
    }
  }

  @StoreParameterizedTest
  void removeFromIterator(Store store) throws IOException {
    write(store, "e1", "Mew", "Mewtwo");
    write(store, "e2", "Charmander", "Pickachu");

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

    assertUnreadable(store, "e2");
    assertThat(store.size()).isEqualTo(sizeOf("Mew", "Mewtwo"));
  }

  @StoreParameterizedTest
  void removeFromIteratorPointingAtStaleViewer(Store store) throws IOException {
    write(store, "e1", "Ditto", "Jynx");

    var iter = store.iterator();
    assertThat(iter.hasNext()).isTrue();
    try (var viewer = iter.next()) {
      // Rewrite the entry, making the viewer stale.
      write(store, "e1", "Pikachu", "Psyduck");

      // Nothing is removed as the iterator is pointing to a stale viewer.
      iter.remove();
      assertEntryEquals(store, "e1", "Pikachu", "Psyduck");

      // Viewer continues operating normally with stale data.
      assertEntryEquals(viewer, "Ditto", "Jynx");
    }
  }

  /** Ensure Viewers return an independent duplicate of the metadata buffer. */
  @StoreParameterizedTest
  void mutatePositionOfMetadataBufferReturnedFromViewer(Store store) throws IOException {
    write(store, "e1", "555", "");
    try (var viewer = view(store, "e1")) {
      var metadata = viewer.metadata();
      metadata.position(metadata.limit()); // Consume.
      assertThat(viewer.metadata().position()).isZero();
      assertEntryEquals(viewer, "555", "");
    }
  }

  @StoreParameterizedTest
  void metadataBufferReturnedFromViewerIsReadOnly(Store store) throws IOException {
    write(store, "e1", "555", "");
    try (var viewer = view(store, "e1")) {
      assertThat(viewer.metadata().isReadOnly()).isTrue();
    }
  }

  @StoreParameterizedTest
  void editingIsAvailableWhenEditorIsClosed(Store store) throws IOException {
    var editor = edit(store, "e1");
    assertThat(store.edit("e1")).isEmpty();
    editor.close();

    // Releasing the editor lock in redis stores is done asynchronously so we must retry.
    //noinspection OptionalGetWithoutIsPresent
    await()
        .pollDelay(Duration.ZERO)
        .until(() -> store.edit("e1"), Optional::isPresent)
        .get()
        .close();
  }

  @StoreParameterizedTest
  @StoreSpec(skipped = StoreType.MEMORY)
  void writesAfterCommittingAreProhibited(Store store) throws IOException {
    try (var editor = edit(store, "e1")) {
      write(editor, "Jynx");
      editor.commit(UTF_8.encode("Ditto"));
      assertThatIllegalStateException()
          .isThrownBy(() -> editor.writer().write(ByteBuffer.allocate(0)));
      assertThatIllegalStateException().isThrownBy(() -> editor.commit(ByteBuffer.allocate(1)));
    }
  }

  @StoreParameterizedTest
  @StoreSpec(skipped = StoreType.MEMORY)
  void editorProhibitsWritesAfterClosure(Store store) throws IOException {
    var editor = edit(store, "e1");
    try (editor) {
      commit(editor, "Ditto", "Jynx");
    }
    assertThatIllegalStateException().isThrownBy(() -> write(editor, "Mewtwo"));
  }
}
