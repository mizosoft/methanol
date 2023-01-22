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

package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.internal.cache.StoreTesting.assertAbsent;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.assertEntryEquals;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.assertUnreadable;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.commit;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.edit;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.setMetadata;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.sizeOf;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.view;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.write;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.github.mizosoft.methanol.internal.cache.MockDiskStore.DiskEntry;
import com.github.mizosoft.methanol.internal.cache.MockDiskStore.EntryCorruptionMode;
import com.github.mizosoft.methanol.internal.cache.MockDiskStore.Index;
import com.github.mizosoft.methanol.internal.cache.MockDiskStore.IndexCorruptionMode;
import com.github.mizosoft.methanol.internal.cache.MockDiskStore.IndexEntry;
import com.github.mizosoft.methanol.internal.function.Unchecked;
import com.github.mizosoft.methanol.testing.Logging;
import com.github.mizosoft.methanol.testing.junit.DiskStoreContext;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension.ExecutorConfig;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.junit.StoreConfig.Execution;
import com.github.mizosoft.methanol.testing.junit.StoreConfig.FileSystemType;
import com.github.mizosoft.methanol.testing.junit.StoreConfig.StoreType;
import com.github.mizosoft.methanol.testing.junit.StoreExtension;
import com.github.mizosoft.methanol.testing.junit.StoreExtension.StoreParameterizedTest;
import com.github.mizosoft.methanol.testing.junit.StoreSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** DiskStore specific tests that are complementary to {@link StoreTest}. */
@Timeout(30)
@ExtendWith({StoreExtension.class, ExecutorExtension.class})
class DiskStoreTest {
  static {
    Logging.disable(DiskStore.class);
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void createWithNonExistentDirectory(DiskStoreContext context) throws IOException {
    Files.delete(context.directory());

    var store = context.createAndRegisterStore();
    assertThat(context.directory()).exists();
    store.flush();
    new MockDiskStore(context).assertEmptyIndex();
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void persistenceOnCreation(DiskStoreContext context) throws IOException, InterruptedException {
    var mockStore = new MockDiskStore(context);
    mockStore.write("e1", "Ditto", "Eevee");
    mockStore.write("e2", "Mew", "Mewtwo");
    mockStore.writeIndex();

    var store = context.createAndRegisterStore();
    assertEntryEquals(store, "e1", "Ditto", "Eevee");
    assertEntryEquals(store, "e2", "Mew", "Mewtwo");
    assertThat(store.size()).isEqualTo(sizeOf("Ditto", "Eevee", "Mew", "Mewtwo"));
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void persistenceAcrossSessions(DiskStoreContext context)
      throws IOException, InterruptedException {
    try (var store1 = context.createAndRegisterStore()) {
      write(store1, "e1", "Mewtwo", "Charmander");
      write(store1, "e2", "Psyduck", "Pikachu");
      context.drainQueuedTasksIfNeeded();
    }

    var store2 = context.createAndRegisterStore();
    assertEntryEquals(store2, "e1", "Mewtwo", "Charmander");
    assertEntryEquals(store2, "e2", "Psyduck", "Pikachu");
    assertThat(store2.size()).isEqualTo(sizeOf("Mewtwo", "Charmander", "Psyduck", "Pikachu"));
  }

  /** Dirty entry files of untracked entries found during initialization are deleted. */
  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void createWithIncompleteEditsForUntrackedEntries(DiskStoreContext context)
      throws IOException, InterruptedException {
    var mockStore = new MockDiskStore(context);

    // Write an empty index.
    mockStore.writeIndex();

    // Write two loose dirty entry files.
    mockStore.writeDirty("e2", "Jynx", "Mew");
    mockStore.writeDirtyTruncated("e3", "Raichu", "Ditto");

    var store = context.createAndRegisterStore();
    mockStore.assertDirtyEntryFileDoesNotExist("e2");
    mockStore.assertDirtyEntryFileDoesNotExist("e3");
    assertAbsent(store, context, "e2", "e3");
    assertThat(store.size()).isZero();
  }

  /** Dirty entry files of tracked entries found during initialization are deleted. */
  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void createWithIncompleteEditsForTrackedEntries(DiskStoreContext context)
      throws IOException, InterruptedException {
    var mockStore = new MockDiskStore(context);
    mockStore.write("e1", "Eevee", "Jigglypuff");
    mockStore.write("e2", "Jynx", "Mew");
    mockStore.writeIndex();

    // Simulate incomplete edits.
    mockStore.writeDirty("e1", "Pikachu", "Charmander");
    mockStore.writeDirtyTruncated("e2", "Mewtwo", "Squirtle");

    var store = context.createAndRegisterStore();
    mockStore.assertDirtyEntryFileDoesNotExist("e1");
    mockStore.assertDirtyEntryFileDoesNotExist("e2");
    assertEntryEquals(store, "e1", "Eevee", "Jigglypuff");
    assertEntryEquals(store, "e2", "Jynx", "Mew");
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void createWithDirtyFileForTrackedEntry(DiskStoreContext context)
      throws IOException, InterruptedException {
    // Write a tracked entry with only its dirty file on disk.
    var index = new Index(context.config().appVersion());
    var entry = new DiskEntry("e1", "Eevee", "Mew", context.config().appVersion());
    index.put(entry.toIndexEntry(context.hasher(), 0));

    var mockStore = new MockDiskStore(context);
    mockStore.writeIndex(index);
    mockStore.writeDirty(entry, false);

    var store = context.createAndRegisterStore();
    mockStore.assertDirtyEntryFileDoesNotExist("e1");
    assertAbsent(store, context, "e1");
    assertThat(store.size()).isZero();
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void untrackedEntriesFoundOnDiskAreDeleted(DiskStoreContext context)
      throws IOException, InterruptedException {
    var mockStore = new MockDiskStore(context);
    mockStore.writeIndex();

    // Both clean and dirty files of untracked entries are deleted.
    mockStore.write("e2", "Eevee", "Ditto");
    mockStore.writeDirty("e3", "Jynx", "Mew");

    var store = context.createAndRegisterStore();
    mockStore.assertEntryFileDoesNotExist("e2");
    mockStore.assertDirtyEntryFileDoesNotExist("e3");
    assertAbsent(store, context, "e2", "e3");
    assertThat(store.size()).isZero();
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void createWithDeletedTrackedEntries(DiskStoreContext context)
      throws IOException, InterruptedException {
    var mockStore = new MockDiskStore(context);
    mockStore.write("e1", "Ditto", "Psyduck");
    mockStore.writeIndex();

    mockStore.deleteEntry("e1");

    var store = context.createAndRegisterStore();
    assertAbsent(store, context, "e1");
    assertThat(store.size()).isZero();
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void entryInitCombinations(DiskStoreContext context) throws IOException, InterruptedException {
    var mockStore = new MockDiskStore(context);
    mockStore.write("e1", "a", "a"); // Tracked clean entry.
    mockStore.writeDirty("e1", "b", "b"); // Incomplete second edit for tracked entry.
    mockStore.writeDirty("e2", "c", "c"); // Incomplete first edit for tracked entry.
    mockStore.index().put(new IndexEntry(context.hasher().hash("e2"), 99, 2));
    mockStore.writeIndex();

    mockStore.write("e3", "d", "d"); // Clean file for untracked entry.

    // Incomplete edits for untracked entry.
    mockStore.writeDirty("e3", "e", "e");
    mockStore.writeDirtyTruncated("e4", "f", "g");

    // Index entry for absent entry.
    mockStore.index().put(new IndexEntry(context.hasher().hash("e5"), 100, 2));

    var store = context.createAndRegisterStore();
    assertAbsent(store, context, "e2", "e3", "e4", "e5");
    assertEntryEquals(store, "e1", "a", "a");
    assertThat(store.size()).isEqualTo(2);
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void storeContentIsDroppedOnCorruptIndex(DiskStoreContext context)
      throws IOException, InterruptedException {
    for (var corruptionMode : IndexCorruptionMode.values()) {
      try {
        assertStoreContentIsDroppedOnCorruptIndex(context, corruptionMode);
        new MockDiskStore(context).delete();
      } catch (AssertionError e) {
        fail(corruptionMode.toString(), e);
      }
    }
  }

  private void assertStoreContentIsDroppedOnCorruptIndex(
      DiskStoreContext context, IndexCorruptionMode corruptionMode)
      throws IOException, InterruptedException {
    var mockStore = new MockDiskStore(context);
    mockStore.write("e1", "Ditto", "Eevee");
    mockStore.write("e2", "Jynx", "Snorlax");
    mockStore.writeIndex(mockStore.index().copy(), corruptionMode);

    var store = context.createAndRegisterStore();
    mockStore.assertHasNoEntriesOnDisk();
    assertAbsent(store, context, "e1", "e2");
    assertThat(store.size()).isZero();

    // The corrupt index is overwritten with an empty index.
    assertThat(mockStore.indexFile()).doesNotExist();
    store.flush();
    mockStore.assertEmptyIndex();

    // Make sure the lock file is not deleted with store content.
    assertThat(mockStore.lockFile()).exists();

    // Clean workspace for next mode.
    store.close();
    mockStore.delete();
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void entryCorruption(DiskStoreContext context) throws IOException {
    for (var corruptionMode : EntryCorruptionMode.values()) {
      try {
        assertEntryCorruption(context, corruptionMode);
      } catch (AssertionError e) {
        fail(corruptionMode.toString(), e);
      }
    }
  }

  private void assertEntryCorruption(DiskStoreContext context, EntryCorruptionMode corruptionMode)
      throws IOException {
    var mockStore = new MockDiskStore(context);
    mockStore.write("e1", "Pikachu", "Mew", corruptionMode);
    mockStore.writeIndex();

    var store = context.createAndRegisterStore();
    assertThatExceptionOfType(StoreCorruptionException.class).isThrownBy(() -> view(store, "e1"));

    // The current implementation doesn't automatically remove the entry.
    mockStore.assertEntryFileExists("e1");

    // Clean workspace for next mode.
    store.close();
    mockStore.delete();
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void unreadableEntriesAreNotTrackedByTheIndex(Store store, DiskStoreContext context)
      throws IOException, InterruptedException {
    var editor = edit(store, "e1");
    write(editor, "Ditto");

    assertUnreadable(store, "e1");
    store.flush();

    var mockStore = new MockDiskStore(context);
    assertThat(mockStore.readIndex().contains(context.hasher().hash("e1"))).isFalse();

    // Committing the edit makes the entry readable.
    commit(editor, "Jynx");
    store.flush();
    assertThat(mockStore.readIndex().contains(context.hasher().hash("e1"))).isTrue();
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void removeBeforeFlush(Store store, DiskStoreContext context)
      throws IOException, InterruptedException {
    write(store, "e1", "Mew", "Mewtwo");
    assertThat(store.remove("e1")).isTrue();
    store.flush();

    var mockStore = new MockDiskStore(context);
    var index = mockStore.readIndex();
    assertThat(index.contains(context.hasher().hash("e1"))).isFalse();
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void clearBeforeFlush(Store store, DiskStoreContext context)
      throws IOException, InterruptedException {
    write(store, "e1", "Mew", "Mewtwo");
    write(store, "e2", "Jynx", "Ditto");
    store.clear();
    store.flush();

    var mockStore = new MockDiskStore(context);
    mockStore.assertHasNoEntriesOnDisk();

    var index = mockStore.readIndex();
    assertThat(index.contains(context.hasher().hash("e1"))).isFalse();
    assertThat(index.contains(context.hasher().hash("e2"))).isFalse();
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK, maxSize = 4, execution = Execution.SAME_THREAD)
  void lruEvictionBeforeFlush(Store store, DiskStoreContext context)
      throws IOException, InterruptedException {
    write(store, "e1", "aa", "bb"); // Grow size to 4 bytes.
    write(store, "e2", "cc", "dd"); // Grow size to 8 bytes, causing e1 to be evicted.
    assertAbsent(store, context, "e1");
    assertThat(store.size()).isEqualTo(4);
    store.flush();

    var mockStore = new MockDiskStore(context);
    var index = mockStore.readIndex();
    assertThat(index.contains(context.hasher().hash("e1"))).isFalse();
    assertThat(index.contains(context.hasher().hash("e2"))).isTrue();
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void closingTheStoreDiscardsIncompleteFirstEdit(Store store, DiskStoreContext context)
      throws IOException, InterruptedException {
    var editor = edit(store, "e1");
    write(editor, "Ditto");

    store.close();

    // Closing the store deletes the editor's file.
    var mockStore = new MockDiskStore(context);
    mockStore.assertDirtyEntryFileDoesNotExist("e1");

    // The entry isn't tracked by the index as it wasn't readable before closing.
    var index = mockStore.readIndex();
    assertThat(index.contains(context.hasher().hash("e1"))).isFalse();

    // The editor prohibits writes & commits.
    assertThatIllegalStateException().isThrownBy(() -> write(editor, "a"));
    assertThatIllegalStateException().isThrownBy(() -> commit(editor, "a"));
    mockStore.assertEntryFileDoesNotExist("e1");
    mockStore.assertDirtyEntryFileDoesNotExist("e1");
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void closingTheStoreDiscardsIncompleteSecondEdit(Store store, DiskStoreContext context)
      throws IOException, InterruptedException {
    write(store, "e1", "Pikachu", "Eevee");

    var editor = edit(store, "e1");
    write(editor, "Ditto");

    store.close();

    // Closing the store deletes the editor's file.
    var mockStore = new MockDiskStore(context);
    mockStore.assertDirtyEntryFileDoesNotExist("e1");

    // The editor prohibits writes & commits.
    assertThatIllegalStateException().isThrownBy(() -> write(editor, "a"));
    assertThatIllegalStateException().isThrownBy(() -> commit(editor, "a"));
    mockStore.assertDirtyEntryFileDoesNotExist("e1");
    mockStore.assertEntryEquals("e1", "Pikachu", "Eevee");
    assertThat(store.size()).isEqualTo(sizeOf("Pikachu", "Eevee"));
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void viewerDisallowsEditsAfterClosingTheStore(Store store)
      throws IOException, InterruptedException {
    write(store, "e1", "Mew", "Mewtwo");
    try (var viewer = view(store, "e1")) {
      store.close();

      // Viewer keeps operating normally.
      assertEntryEquals(viewer, "Mew", "Mewtwo");

      // No edits are allowed.
      assertThat(viewer.editAsync().join()).isEmpty();
    }
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  @ExecutorConfig(ExecutorType.CACHED_POOL)
  void concurrentRemovals(Store store, Executor executor) throws IOException, InterruptedException {
    write(store, "e1", "Ditto", "Eevee");

    int removalTriesCount = 10;
    var arrival = new CyclicBarrier(removalTriesCount);
    var assertionTasks = new ArrayList<CompletableFuture<Void>>();
    var removed = new AtomicBoolean();
    for (int i = 0; i < removalTriesCount; i++) {
      assertionTasks.add(
          Unchecked.runAsync(
              () -> {
                arrival.await();
                assertThat(!store.remove("e1") || removed.compareAndSet(false, true))
                    .withFailMessage("more than one removal succeeded")
                    .isTrue();
              },
              executor));
    }
    assertAll(assertionTasks.stream().map(cf -> cf::join));
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void removeIsAppliedOnDisk(Store store, DiskStoreContext context)
      throws IOException, InterruptedException {
    write(store, "e1", "Jynx", "Ditto");
    assertThat(store.remove("e1")).isTrue();
    new MockDiskStore(context).assertEntryFileDoesNotExist("e1");
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void removeFromViewerIsAppliedOnDisk(Store store, DiskStoreContext context)
      throws IOException, InterruptedException {
    write(store, "e1", "Jynx", "Ditto");
    try (var viewer = view(store, "e1")) {
      assertThat(viewer.removeEntry()).isTrue();
      new MockDiskStore(context).assertEntryFileDoesNotExist("e1");
    }
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void clearIsAppliedOnDisk(Store store, DiskStoreContext context)
      throws IOException, InterruptedException {
    write(store, "e1", "Jynx", "Ditto");
    write(store, "e2", "Mew", "Charmander");
    write(store, "e3", "Eevee", "Mewtwo");
    store.clear();
    new MockDiskStore(context).assertHasNoEntriesOnDisk();
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void discardedEditIsAppliedOnDisk(Store store, DiskStoreContext context)
      throws IOException, InterruptedException {
    var mockStore = new MockDiskStore(context);
    try (var editor = edit(store, "e1")) {
      // Don't commit edit.
      write(editor, "Pikachu");
      mockStore.assertDirtyEntryFileExists("e1");
    }
    mockStore.assertDirtyEntryFileDoesNotExist("e1");
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK, maxSize = 4, execution = Execution.SAME_THREAD)
  @ExecutorConfig(ExecutorType.CACHED_POOL)
  void evictionRaces(Store store, Executor executor) throws Exception {
    int writerCount = 16;
    var arrival = new CyclicBarrier(writerCount);
    var writers = new ArrayList<CompletableFuture<Void>>();
    for (int i = 0; i < writerCount; i++) {
      int j = i;
      writers.add(
          CompletableFuture.runAsync(
              Unchecked.runnable(
                  () -> {
                    arrival.await();
                    write(store, "e" + j, "12", "ab");
                  }),
              executor));
    }

    assertAll(writers.stream().map(cf -> cf::join));

    var iter = store.iterator();
    assertThat(iter.hasNext()).isTrue();
    try (var viewer = iter.next()) {
      assertEntryEquals(viewer, "12", "ab");
    }
    assertThat(iter.hasNext()).isFalse();
    assertThat(store.size()).isEqualTo(4);
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK, maxSize = 4, execution = Execution.SAME_THREAD)
  void createdStoreStartsWithinBounds(DiskStoreContext context)
      throws IOException, InterruptedException {
    var mockStore = new MockDiskStore(context);
    mockStore.write("e1", "aa", "bb"); // Grow size to 4 bytes.
    mockStore.write("e2", "cc", "dd"); // Grow size to 8 bytes, evicting e1.
    mockStore.writeIndex();

    var store = context.createAndRegisterStore();
    assertAbsent(store, context, "e1");
    assertEntryEquals(store, "e2", "cc", "dd");
    assertThat(store.size()).isEqualTo(4);
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK, maxSize = 8, execution = Execution.SAME_THREAD)
  void lruOrderIsPersisted(DiskStoreContext context) throws IOException, InterruptedException {
    // LRU queue: e1, e2, e2
    var store1 = (DiskStore) context.createAndRegisterStore();
    write(store1, "e1", "a", "b"); // Grow size to 2 bytes.
    write(store1, "e2", "c", "d"); // Grow size to 4 bytes.
    write(store1, "e3", "e", "f"); // Grow size to 6 bytes.
    assertThat(store1.size()).isEqualTo(6);

    // LRU queue: e2, e3, e1.
    view(store1, "e1").close();

    // LRU queue: e2, e3, e1, e4.
    write(store1, "e4", "h", "i"); // Grow size to 8 bytes (max).
    assertThat(store1.size()).isEqualTo(8);

    // LRU queue: e3, e1, e4, e2.
    view(store1, "e2").close();

    long lruTimeBeforeClosure = store1.lruTime();
    context.drainQueuedTasksIfNeeded();
    store1.close();

    var store2 = (DiskStore) context.createAndRegisterStore();
    assertThat(store2.lruTime()).isEqualTo(lruTimeBeforeClosure);

    // Evict older entries. Eviction occurs in LRU order before closure: e3, e1, e4, e2.
    var expectedEvictionOrder = List.of("e3", "e1", "e4", "e2").iterator();
    for (int i = 0; i < 4; i++) {
      write(store2, "e" + (5 + i), "a", "b");
      assertAbsent(store2, context, expectedEvictionOrder.next());
    }
    assertThat(store2.size()).isEqualTo(8);
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void missingEntryFile(Store store, DiskStoreContext context)
      throws IOException, InterruptedException {
    write(store, "e1", "Ditto", "Jynx");

    var mockStore = new MockDiskStore(context);
    mockStore.deleteEntry("e1");
    assertAbsent(store, context, "e1");
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void missingEntryFileWhileIterating(Store store, DiskStoreContext context)
      throws IOException, InterruptedException {
    write(store, "e1", "Ditto", "Jynx");
    var iter = store.iterator();
    var mockStore = new MockDiskStore(context);
    mockStore.deleteEntry("e1");
    assertThat(iter.hasNext()).isFalse();
  }

  @StoreParameterizedTest
  @StoreSpec(
      store = StoreType.DISK,
      maxSize = 5,
      execution = Execution.SAME_THREAD,
      indexUpdateDelaySeconds = 0,
      autoAdvanceClock = false,
      dispatchEagerly = false)
  void indexUpdateEvents(DiskStore store, DiskStoreContext context)
      throws IOException, InterruptedException {
    var delayer = context.mockDelayer();
    var clock = context.mockClock();
    var mockStore = new MockDiskStore(context);

    // Opening an editor doesn't issue an index write.
    var editor = edit(store, "e1");
    assertThat(delayer.taskCount()).isZero();

    int indexWriteCount = 0;

    // Committing an edit issues an index write.
    commit(editor, "a"); // 1 byte.
    assertThat(delayer.taskCount()).isOne();
    clock.advanceSeconds(0);
    assertThat(store.indexWriteCount()).isEqualTo(++indexWriteCount);

    // Viewing an existing entry issues an index write.
    view(store, "e1").close();
    assertThat(delayer.taskCount()).isOne();
    clock.advanceSeconds(0);
    assertThat(store.indexWriteCount()).isEqualTo(++indexWriteCount);

    // Attempting to view a non-existent entry doesn't issue an index write.
    assertThat(store.view("e2")).isEmpty();
    assertThat(delayer.taskCount()).isZero();

    // Removing an existing entry issues an index write.
    assertThat(store.remove("e1")).isTrue();
    assertThat(delayer.taskCount()).isOne();
    clock.advanceSeconds(0);
    assertThat(store.indexWriteCount()).isEqualTo(++indexWriteCount);

    write(store, "e1", "1", "a");
    assertThat(delayer.taskCount()).isOne();
    clock.advanceSeconds(0);
    assertThat(store.indexWriteCount()).isEqualTo(++indexWriteCount);

    // Removing an existing entry through a Viewer issues an index write.
    try (var viewer = view(store, "e1")) {
      // Consume index write caused by viewing.
      assertThat(delayer.taskCount()).isOne();
      clock.advanceSeconds(0);
      assertThat(store.indexWriteCount()).isEqualTo(++indexWriteCount);

      viewer.removeEntry();
      assertThat(delayer.taskCount()).isOne();
      clock.advanceSeconds(0);
      assertThat(store.indexWriteCount()).isEqualTo(++indexWriteCount);
    }

    // Removing a non-existent entry doesn't issue an index write.
    assertThat(store.remove("e2")).isFalse();
    assertThat(delayer.taskCount()).isZero();

    write(store, "e1", "1", "a"); // 2 bytes.
    assertThat(delayer.taskCount()).isOne();
    clock.advanceSeconds(0);
    assertThat(store.indexWriteCount()).isEqualTo(++indexWriteCount);

    // Opening Viewers from iterators issues an index write.
    var iter = store.iterator();
    assertThat(delayer.taskCount()).isZero();
    assertThat(iter.hasNext()).isTrue();
    iter.next().close();
    assertThat(delayer.taskCount()).isOne();
    clock.advanceSeconds(0);
    assertThat(store.indexWriteCount()).isEqualTo(++indexWriteCount);

    // Removing an entry with Iterator::remove issues an index write.
    iter.remove();
    assertThat(delayer.taskCount()).isOne();
    clock.advanceSeconds(0);
    assertThat(store.indexWriteCount()).isEqualTo(++indexWriteCount);

    write(store, "e3", "", "abc"); // 3 bytes.
    write(store, "e4", "", "xy"); // + 2 bytes.
    assertThat(delayer.taskCount()).isEqualTo(2);
    clock.advanceSeconds(0);
    assertThat(store.indexWriteCount()).isEqualTo(indexWriteCount += 2);

    assertThat(store.size()).isEqualTo(5);

    // Eviction due to exceeding the size bound issues an index write.
    write(store, "e4", "", "xyz"); // Growing e4 to 3 bytes causes e3 to get evicted.
    assertThat(delayer.taskCount()).isEqualTo(2); // 1 for editing + 1 for eviction.
    clock.advanceSeconds(0);
    assertThat(store.indexWriteCount()).isEqualTo(indexWriteCount += 2);
    assertAbsent(store, context, "e3");

    // Clearing the store issues an index write.
    store.clear();
    assertThat(delayer.taskCount()).isOne();
    clock.advanceSeconds(0);
    assertThat(store.indexWriteCount()).isEqualTo(++indexWriteCount);
    mockStore.assertEmptyIndex();
  }

  @StoreParameterizedTest
  @StoreSpec(
      store = StoreType.DISK,
      execution = Execution.SAME_THREAD,
      autoAdvanceClock = false,
      dispatchEagerly = false,
      indexUpdateDelaySeconds = 1)
  void indexUpdatesAreTimeLimited(DiskStore store, DiskStoreContext context)
      throws IOException, InterruptedException {
    var delayer = context.mockDelayer();
    var clock = context.mockClock();
    int indexWriteCount = 0;

    // t = 0
    // First write is dispatched immediately.
    write(store, "e1", "1", "a");
    assertThat(delayer.taskCount()).isOne();
    assertThat(delayer.peekEarliestFuture().delay()).isZero();

    clock.advanceSeconds(0); // Dispatch index write tasks.
    assertThat(store.indexWriteCount()).isEqualTo(++indexWriteCount);

    // t = 0
    // An index write is scheduled to run in 1 second as one has just run.
    view(store, "e1").close();
    assertThat(delayer.taskCount()).isOne();
    assertThat(delayer.peekEarliestFuture().delay()).hasSeconds(1);

    clock.advanceSeconds(1);
    assertThat(store.indexWriteCount()).isEqualTo(++indexWriteCount);

    // t = 1
    // An index write is scheduled to run in 1 second as one has just run.
    view(store, "e1").close();
    assertThat(delayer.taskCount()).isOne();
    assertThat(delayer.peekEarliestFuture().delay()).hasSeconds(1);

    // t = 1
    // An index write is not scheduled since one is still going to run in 1 second.
    view(store, "e1").close();
    assertThat(delayer.taskCount()).isOne();

    clock.advanceSeconds(1);
    assertThat(store.indexWriteCount()).isEqualTo(++indexWriteCount);

    // t = 2
    // A new write is scheduled to run in 1 second as one has just run.
    view(store, "e1").close();
    assertThat(delayer.taskCount()).isOne();

    clock.advanceSeconds(1);
    assertThat(store.indexWriteCount()).isEqualTo(++indexWriteCount);
    clock.advanceSeconds(1);

    // t = 4
    // An index write is dispatched immediately as 1 second has passed since the last write.
    view(store, "e1").close();
    assertThat(delayer.taskCount()).isOne();

    clock.advanceSeconds(0);
    assertThat(store.indexWriteCount()).isEqualTo(++indexWriteCount);
  }

  @StoreParameterizedTest
  @StoreSpec(
      store = StoreType.DISK,
      indexUpdateDelaySeconds = 1, // Stall time-limited index writes.
      autoAdvanceClock = false)
  void indexIsFlushedOnClosure(DiskStore store, DiskStoreContext context)
      throws IOException, InterruptedException {
    write(store, "e1", "12", "ab");
    store.close();

    var mockStore = new MockDiskStore(context);
    mockStore.assertIndexEquals("e1", store.lruTime() - 1, 4L);
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void entryFileIsTruncatedWhenMetadataShrinks(Store store, DiskStoreContext context)
      throws IOException, InterruptedException {
    var mockStore = new MockDiskStore(context);

    write(store, "e1", "123", "abc");
    long sizeBeforeShrinking = Files.size(mockStore.entryFile("e1"));

    // Shrink metadata by 1 byte.
    setMetadata(store, "e1", "12");
    long sizeAfterShrinking = Files.size(mockStore.entryFile("e1"));
    assertThat(sizeBeforeShrinking)
        .withFailMessage("%d -> %d", sizeBeforeShrinking, sizeAfterShrinking)
        .isEqualTo(sizeAfterShrinking + 1);
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK, maxSize = 5, execution = Execution.SAME_THREAD)
  void entryExceedingMaxSize(Store store, DiskStoreContext context)
      throws IOException, InterruptedException {
    var mockStore = new MockDiskStore(context);

    write(store, "e1", "12", "ab"); // 4 bytes.
    write(store, "e2", "12", "abc"); // 5 bytes -> e1 is evicted to accommodate e2.
    assertAbsent(store, context, "e1");
    assertEntryEquals(store, "e2", "12", "abc");
    assertThat(store.size()).isEqualTo(5);

    // 6 bytes -> e3 is ignored & e2 remains untouched.
    try (var editor = edit(store, "e3")) {
      assertThat(commit(editor, "123", "abc")).isFalse();
    }
    assertAbsent(store, context, "e3");
    assertEntryEquals(store, "e2", "12", "abc");
    assertThat(store.size()).isEqualTo(5);
    mockStore.assertDirtyEntryFileDoesNotExist("e3");
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK, maxSize = 4)
  void exceedMaxSizeByExpandingData(Store store, DiskStoreContext context)
      throws IOException, InterruptedException {
    write(store, "e1", "12", "ab");
    try (var editor = edit(store, "e1")) {
      assertThat(commit(editor, "12", "abc")).isFalse();
    }
    assertAbsent(store, context, "e1");
    assertThat(store.size()).isZero();
    new MockDiskStore(context).assertDirtyEntryFileDoesNotExist("e1");
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK, maxSize = 4)
  void exceedMaxSizeByExpandingMetadata(Store store, DiskStoreContext context)
      throws IOException, InterruptedException {
    write(store, "e1", "12", "ab");
    try (var editor = edit(store, "e1")) {
      assertThat(commit(editor, "123", "ab")).isFalse();
    }
    assertAbsent(store, context, "e1");
    assertThat(store.size()).isZero();
    new MockDiskStore(context).assertDirtyEntryFileDoesNotExist("e1");
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void closedStoreIsInoperable(DiskStoreContext context) throws IOException {
    var store1 = context.createAndRegisterStore();
    context.drainQueuedTasksIfNeeded();
    store1.close();
    assertInoperable(store1);

    var store2 = context.createAndRegisterStore();
    store2.dispose();
    assertInoperable(store2);
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void removalFromViewerIsIgnoredAfterClosingTheStore(Store store, DiskStoreContext context)
      throws IOException, InterruptedException {
    write(store, "e1", "Eevee", "Jynx");
    try (var viewer = view(store, "e1")) {
      store.close();
      assertThat(viewer.removeEntry()).isFalse();

      var mockStore = new MockDiskStore(context);
      mockStore.assertEntryEquals("e1", "Eevee", "Jynx");
      assertThat(mockStore.readIndex().contains(context.hasher().hash("e1"))).isTrue();
    }
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void removalFromViewerIsIgnoredAfterDisposingTheStore(Store store)
      throws IOException, InterruptedException {
    write(store, "e1", "Eevee", "Jynx");
    try (var viewer = view(store, "e1")) {
      store.dispose();
      assertThat(viewer.removeEntry()).isFalse();
    }
  }

  /** Closing the store while iterating silently terminates iteration. */
  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void closeWhileIterating(Store store) throws IOException, InterruptedException {
    write(store, "e1", "Ditto", "Charmander");
    write(store, "e2", "Eevee", "Jynx");

    var iter = store.iterator();
    assertThat(iter.hasNext()).isTrue();
    iter.next().close(); // Consume next.
    store.close();
    assertThat(iter.hasNext()).isFalse();
    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(iter::next);
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void closeStoreWhileReading(Store store) throws IOException, InterruptedException {
    write(store, "e1", "Jynx", "Ditto");
    try (var viewer = view(store, "e1")) {
      store.close();
      assertEntryEquals(viewer, "Jynx", "Ditto");
    }
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void disposeClearsStoreContent(Store store, DiskStoreContext context)
      throws IOException, InterruptedException {
    write(store, "e1", "Jynx", "Eevee");
    store.dispose();
    assertInoperable(store);
    assertThat(store.iterator().hasNext()).isFalse();
    assertThat(store.size()).isZero();
    assertThat(context.directory()).isEmptyDirectory();
  }

  // Using two tests as JUnit 5 doesn't allow RepeatedTest + ParameterizedTest.

  @RepeatedTest(10)
  @StoreSpec(
      store = StoreType.DISK,
      fileSystem = FileSystemType.SYSTEM,
      indexUpdateDelaySeconds = 0,
      autoAdvanceClock = false,
      dispatchEagerly = false)
  @ExecutorConfig(ExecutorType.CACHED_POOL)
  void indexWriteDisposeRaces_systemFileSystem(
      DiskStore store, DiskStoreContext context, Executor executor)
      throws IOException, InterruptedException {
    testDisposeDuringIndexWrite(store, context, executor);
  }

  @RepeatedTest(10)
  @StoreSpec(
      store = StoreType.DISK,
      fileSystem = FileSystemType.EMULATED_WINDOWS,
      indexUpdateDelaySeconds = 0,
      autoAdvanceClock = false,
      dispatchEagerly = false)
  @ExecutorConfig(ExecutorType.CACHED_POOL)
  void indexWriteDisposeRaces_windowsEmulatingFilesystem(
      DiskStore store, DiskStoreContext context, Executor executor)
      throws IOException, InterruptedException {
    testDisposeDuringIndexWrite(store, context, executor);
  }

  private void testDisposeDuringIndexWrite(
      DiskStore store, DiskStoreContext context, Executor executor)
      throws IOException, InterruptedException {
    // Submit index write tasks (queued by the delayer).
    int indexWriteCount = 10;
    write(store, "e1", "", "a");
    for (int i = 0; i < indexWriteCount - 1; i++) {
      view(store, "e1").close();
    }

    var arrival = new CyclicBarrier(2);
    var triggerIndexWrites =
        Unchecked.runAsync(
            () -> {
              arrival.await();
              try {
                context.mockClock().advanceSeconds(0); // Trigger 'delayed' index writes.
              } catch (RejectedExecutionException ignored) {
                // This is fine. DiskStore::dispose closes the SerialExecutor used for index writes.
              }
            },
            executor);
    var invokeDispose =
        Unchecked.runAsync(
            () -> {
              arrival.await();
              //noinspection StatementWithEmptyBody
              while (store.indexWriteCount()
                  < 0.3 * indexWriteCount) {} // Spin till some writes are completed.
              store.dispose();
            },
            executor);

    CompletableFuture.allOf(triggerIndexWrites, invokeDispose).join();
    assertThat(context.directory()).isEmptyDirectory();
    assertThat(store.indexWriteCount()).isNotZero();
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void disposeStoreWhileReading(Store store, DiskStoreContext context)
      throws IOException, InterruptedException {
    write(store, "e1", "Jynx", "Ditto");
    try (var viewer = view(store, "e1")) {
      store.dispose();
      new MockDiskStore(context).assertHasNoEntriesOnDisk();
      assertEntryEquals(viewer, "Jynx", "Ditto");
    }
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void hashCollisionOnViewing(Store store, DiskStoreContext context)
      throws IOException, InterruptedException {
    context.hasher().setHash("e1", 1);
    context.hasher().setHash("e2", 1);

    write(store, "e1", "Jynx", "Psyduck");
    assertThat(store.view("e2")).isEmpty();
    assertEntryEquals(store, "e1", "Jynx", "Psyduck");
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void hashCollisionOnViewingRecoveredEntry(DiskStoreContext context)
      throws IOException, InterruptedException {
    context.hasher().setHash("e1", 1);
    context.hasher().setHash("e2", 1);

    var mockStore = new MockDiskStore(context);
    mockStore.write("e1", "Jynx", "Psyduck");
    mockStore.writeIndex();

    var store = context.createAndRegisterStore();
    assertThat(store.view("e2")).isEmpty();
    assertEntryEquals(store, "e1", "Jynx", "Psyduck");
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void hashCollisionOnRemoval(DiskStoreContext context) throws IOException, InterruptedException {
    context.hasher().setHash("e1", 1);
    context.hasher().setHash("e2", 1);

    var mockStore = new MockDiskStore(context);
    mockStore.write("e1", "Jynx", "Psyduck");
    mockStore.writeIndex();

    var store = context.createAndRegisterStore();
    assertThat(store.remove("e2")).isFalse();
    assertThat(store.remove("e1")).isTrue();
  }

  @StoreParameterizedTest
  @StoreSpec(store = StoreType.DISK)
  void hashCollisionOnCompletedEdit(DiskStoreContext context)
      throws IOException, InterruptedException {
    context.hasher().setHash("e1", 1);
    context.hasher().setHash("e2", 1);

    var store = context.createAndRegisterStore();
    write(store, "e1", "Jynx", "Psyduck");
    write(store, "e2", "Eevee", "Mewtwo");

    // e2 replaces e1 as they collide.
    assertAbsent(store, context, "e1");
    assertEntryEquals(store, "e2", "Eevee", "Mewtwo");
    assertThat(store.remove("e1")).isFalse();
    assertThat(store.remove("e2")).isTrue();
  }

  private static void assertInoperable(Store store) {
    assertThatIllegalStateException().isThrownBy(() -> store.view("e1"));
    assertThatIllegalStateException().isThrownBy(() -> store.edit("e1"));
    assertThatIllegalStateException().isThrownBy(() -> store.remove("e1"));
    assertThatIllegalStateException().isThrownBy(store::clear);
  }
}
