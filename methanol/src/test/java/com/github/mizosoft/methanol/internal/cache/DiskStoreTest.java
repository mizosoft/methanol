/*
 * Copyright (c) 2019, 2020 Moataz Abdelnasser
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
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
import static com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType.CACHED_POOL;
import static com.github.mizosoft.methanol.testing.StoreConfig.Execution.QUEUED;
import static com.github.mizosoft.methanol.testing.StoreConfig.Execution.SAME_THREAD;
import static com.github.mizosoft.methanol.testing.StoreConfig.FileSystemType.SYSTEM;
import static com.github.mizosoft.methanol.testing.StoreConfig.FileSystemType.WINDOWS;
import static com.github.mizosoft.methanol.testing.StoreConfig.StoreType.DISK;
import static com.github.mizosoft.methanol.testing.TestUtils.awaitUninterruptibly;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.github.mizosoft.methanol.internal.cache.MockDiskStore.DiskEntry;
import com.github.mizosoft.methanol.internal.cache.MockDiskStore.EntryCorruptionMode;
import com.github.mizosoft.methanol.internal.cache.MockDiskStore.Index;
import com.github.mizosoft.methanol.internal.cache.MockDiskStore.IndexCorruptionMode;
import com.github.mizosoft.methanol.internal.function.Unchecked;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorConfig;
import com.github.mizosoft.methanol.testing.Logging;
import com.github.mizosoft.methanol.testing.StoreConfig;
import com.github.mizosoft.methanol.testing.StoreContext;
import com.github.mizosoft.methanol.testing.StoreExtension;
import com.github.mizosoft.methanol.testing.StoreExtension.StoreParameterizedTest;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** DiskStore specific tests that are complementary to {@link StoreTest}. */
@Timeout(60)
@ExtendWith({StoreExtension.class, ExecutorExtension.class})
class DiskStoreTest {
  static {
    Logging.disable(DiskStore.class);
  }

  private @MonotonicNonNull MockDiskStore mockStore;

  private void setUp(StoreContext context) {
    mockStore = new MockDiskStore(context);
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK, autoInit = false)
  void initializeWithNonExistentDirectory(Store store, StoreContext context)
      throws IOException {
    setUp(context);
    Files.delete(context.directory());

    store.initialize();
    assertThat(context.directory()).exists();
    mockStore.assertEmptyIndex();
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK, autoInit = false)
  void persistenceOnInitialization(Store store, StoreContext context) throws IOException {
    setUp(context);
    mockStore.write("e1", "Ditto", "Eevee");
    mockStore.write("e2", "Mew", "Mewtwo");
    mockStore.writeWorkIndex();

    store.initialize();
    assertEntryEquals(store, "e1", "Ditto", "Eevee");
    assertEntryEquals(store, "e2", "Mew", "Mewtwo");
    assertThat(store.size()).isEqualTo(sizeOf("Ditto", "Eevee", "Mew", "Mewtwo"));
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void persistenceAcrossSessions(StoreContext context) throws IOException {
    setUp(context);
    var store1 = context.newStore();
    writeEntry(store1, "e1", "Mewtwo", "Charmander");
    writeEntry(store1, "e2", "Psyduck", "Pickachu");

    context.drainQueuedTasks();
    store1.close();

    var store2 = context.newStore();
    assertEntryEquals(store2, "e1", "Mewtwo", "Charmander");
    assertEntryEquals(store2, "e2", "Psyduck", "Pickachu");
    assertThat(store2.size()).isEqualTo(sizeOf("Mewtwo", "Charmander", "Psyduck", "Pickachu"));
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK, autoInit = false)
  @ExecutorConfig(CACHED_POOL)
  void concurrentInitializers(Store store, StoreContext context, Executor threadPool)
      throws IOException {
    setUp(context);
    mockStore.write("e1", "Ditto", "Eevee");
    mockStore.writeWorkIndex();

    // Create initCount concurrent initializers
    int initCount = 10;
    var arrival = new CyclicBarrier(initCount);
    var assertionTasks = new ArrayList<CompletableFuture<Void>>();
    for (int i = 0; i < initCount; i++) {
      var task = Unchecked.runAsync(() -> {
        awaitUninterruptibly(arrival);

        store.initialize();
        assertEntryEquals(store, "e1", "Ditto", "Eevee");
      }, threadPool);

      assertionTasks.add(task);
    }

    assertAll(assertionTasks.stream().map(cf -> cf::join));
  }

  /** Dirty entry files of untracked entries found during initialization are deleted. */
  @StoreParameterizedTest
  @StoreConfig(store = DISK, autoInit = false)
  void initializeWithIncompleteEditsForUntrackedEntries(Store store, StoreContext context)
      throws IOException {
    setUp(context);
    mockStore.write("e1", "Eevee", "Jigglypuff");
    mockStore.writeWorkIndex();

    // Write two loose dirty entry files
    mockStore.writeDirty("e2", "Jynx", "Mew");
    mockStore.writeDirtyTruncated("e3", "Raichu", "Ditto");

    store.initialize();
    mockStore.assertDirtyEntryFileDoesNotExist("e2");
    mockStore.assertDirtyEntryFileDoesNotExist("e3");
    assertAbsent(store, context, "e2", "e3");
    assertEntryEquals(store, "e1", "Eevee", "Jigglypuff");
    assertThat(store.size()).isEqualTo(sizeOf("Eevee", "Jigglypuff"));
  }

  /** Dirty entry files of tracked entries found during initialization are deleted. */
  @StoreParameterizedTest
  @StoreConfig(store = DISK, autoInit = false)
  void initializeWithIncompleteEditsForTrackedEntries(Store store, StoreContext context)
      throws IOException {
    setUp(context);
    mockStore.write("e1", "Eevee", "Jigglypuff");
    mockStore.write("e2", "Jynx", "Mew");
    mockStore.write("e3", "Psyduck", "Raichu");
    mockStore.writeWorkIndex();

    // Simulate incomplete edits
    mockStore.writeDirty("e1", "Pickachu", "Charmander");
    mockStore.writeDirty("e2", "Mewtwo", "Squirtle");
    mockStore.writeDirtyTruncated("e3", "Meowth", "Lucario");

    store.initialize();
    mockStore.assertDirtyEntryFileDoesNotExist("e1");
    mockStore.assertDirtyEntryFileDoesNotExist("e2");
    mockStore.assertDirtyEntryFileDoesNotExist("e3");
    assertEntryEquals(store, "e1", "Eevee", "Jigglypuff");
    assertEntryEquals(store, "e2", "Jynx", "Mew");
    assertEntryEquals(store, "e3", "Psyduck", "Raichu");
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK, autoInit = false)
  void initializeWithDirtyFileForTrackedEntry(Store store, StoreContext context)
      throws IOException {
    setUp(context);

    // Write a tracked entry with only its dirty file on disk
    var index = new Index(context.config().appVersion());
    var entry = new DiskEntry("e1", "Eevee", "Mew", context.config().appVersion());
    index.put(entry.toIndexEntry(context.hasher(), context.clock().instant()));
    mockStore.writeIndex(index);
    mockStore.writeDirty(entry, false);

    store.initialize();
    mockStore.assertDirtyEntryFileDoesNotExist("e1");
    assertAbsent(store, context, "e1");
    assertThat(store.size()).isZero();
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK, autoInit = false)
  void untrackedEntriesFoundOnDiskAreDeleted(Store store, StoreContext context)
      throws IOException {
    setUp(context);
    mockStore.write("e1", "Psyduck", "Pickachu");
    mockStore.writeWorkIndex();

    // Both clean and dirty files of untracked entries are deleted
    mockStore.write("e2", "Eevee", "Ditto");
    mockStore.write("e3", "Jynx", "Mew");
    mockStore.writeDirty("e3", "Raichu", "Mewtwo");

    store.initialize();
    assertAbsent(store, context, "e2", "e3");
    mockStore.assertDirtyEntryFileDoesNotExist("e3");
    assertEntryEquals(store, "e1", "Psyduck", "Pickachu");
    assertThat(store.size()).isEqualTo(sizeOf("Psyduck", "Pickachu"));
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK, autoInit = false)
  void deleteTrackedEntriesBeforeInitialization(Store store, StoreContext context)
      throws IOException {
    setUp(context);
    mockStore.write("e1", "Ditto", "Psyduck");
    mockStore.write("e2", "Mew", "Eevee");
    mockStore.writeWorkIndex();

    mockStore.delete("e1");

    store.initialize();
    assertAbsent(store, context, "e1");
    assertEntryEquals(store, "e2", "Mew", "Eevee");
    assertThat(store.size()).isEqualTo(sizeOf("Mew", "Eevee"));
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK, autoInit = false)
  void storeContentIsDroppedOnCorruptIndex(StoreContext context) throws IOException {
    setUp(context);
    for (var corruptMode : IndexCorruptionMode.values()) {
      try {
        var store = context.newStore();
        assertStoreContentIsDroppedOnCorruptIndex(store, context, corruptMode);

        // Clean workspace for next mode
        context.drainQueuedTasks();
        store.close();
        mockStore.delete();
      } catch (AssertionError e) {
        fail(corruptMode.toString(), e);
      }
    }
  }

  private void assertStoreContentIsDroppedOnCorruptIndex(
      Store store, StoreContext context, IndexCorruptionMode corruptionMode) throws IOException {
    mockStore.write("e1", "Ditto", "Eevee");
    mockStore.write("e2", "Jynx", "Snorlax");
    mockStore.writeIndex(mockStore.copyWorkIndex(), corruptionMode);

    store.initialize();
    mockStore.assertHasNoEntriesOnDisk();
    assertAbsent(store, context, "e1", "e2");
    assertThat(store.size()).isZero();

    // The corrupt index is overwritten with an empty index
    mockStore.assertEmptyIndex();

    // Make sure the lock file is not deleted with the store content
    assertThat(mockStore.lockFile()).exists();
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK, autoInit = false)
  void entryCorruption(StoreContext context) throws IOException {
    setUp(context);
    for (var corruptMode : EntryCorruptionMode.values()) {
      try {
        var store = context.newStore();
        assertEntryCorruption(store, corruptMode);

        // Clean workspace for next mode
        context.drainQueuedTasks();
        store.close();
        mockStore.delete();
      } catch (AssertionError e) {
        fail(corruptMode.toString(), e);
      }
    }
  }

  private void assertEntryCorruption(
      Store store, EntryCorruptionMode corruptionMode) throws IOException {
    mockStore.write("e1", "Pickachu", "Mew", corruptionMode);
    mockStore.writeWorkIndex();

    store.initialize();
    assertThatExceptionOfType(StoreCorruptionException.class)
        .isThrownBy(() -> view(store, "e1"));

    // The current implementation doesn't automatically remove the entry
    mockStore.assertEntryFileExists("e1");
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void unreadableEntriesAreNotTrackedByTheIndex(Store store, StoreContext context)
      throws IOException {
    setUp(context);
    var editor = edit(store, "e1");
    writeEntry(editor, "Pickachu", "Ditto");
    editor.commitOnClose();

    assertUnreadable(store, "e1");
    store.flush();

    var index = mockStore.readIndex();
    assertThat(index.contains(context.hasher().hash("e1"))).isFalse();

    // Completing the edit makes the entry readable
    editor.close();
    store.flush();

    var index2 = mockStore.readIndex();
    assertThat(index2.contains(context.hasher().hash("e1"))).isTrue();
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void removeBeforeFlush(Store store, StoreContext context) throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Mew", "Mewtwo");

    assertThat(store.remove("e1")).isTrue();
    store.flush();

    var index = mockStore.readIndex();
    assertThat(index.contains(context.hasher().hash("e1"))).isFalse();
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void clearBeforeFlush(Store store, StoreContext context) throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Mew", "Mewtwo");
    writeEntry(store, "e2", "Jynx", "Ditto");

    store.clear();
    store.flush();
    mockStore.assertHasNoEntriesOnDisk();

    var index = mockStore.readIndex();
    assertThat(index.contains(context.hasher().hash("e1"))).isFalse();
    assertThat(index.contains(context.hasher().hash("e2"))).isFalse();
  }

  @StoreParameterizedTest
  @StoreConfig(
      store = DISK,
      maxSize = 10,
      execution = SAME_THREAD)
  void lruEvictionBeforeFlush(Store store, StoreContext context) throws IOException {
    setUp(context);
    writeEntry(store, "e1", "aaa", "bbb"); // Grow size to 6 bytes
    writeEntry(store, "e2", "ccc", "ddd"); // Grow size to 12 bytes, causing e1 to be evicted
    assertAbsent(store, context, "e1");
    assertThat(store.size()).isEqualTo(6);
    store.flush();

    var index = mockStore.readIndex();
    assertThat(index.contains(context.hasher().hash("e1"))).isFalse();
    assertThat(index.contains(context.hasher().hash("e2"))).isTrue();
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void closingTheStoreDiscardsIncompleteFirstEdit(Store store, StoreContext context)
      throws IOException {
    setUp(context);
    var editor = edit(store, "e1");
    writeEntry(editor, "Jynx", "Ditto");

    context.drainQueuedTasks();
    store.close();

    // Closing the store deletes the editor's work file
    mockStore.assertDirtyEntryFileDoesNotExist("e1");

    // The entry isn't in the index since it wasn't readable before closing
    var index = mockStore.readIndex();
    assertThat(index.contains(context.hasher().hash("e1"))).isFalse();

    // The editor silently discards writes & commits
    writeData(editor, "Charmander");
    editor.commitOnClose();
    editor.close();
    mockStore.assertEntryFileDoesNotExist("e1");
    mockStore.assertDirtyEntryFileDoesNotExist("e1");
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void closingTheStoreDiscardsIncompleteSecondEdit(Store store, StoreContext context)
      throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Pickachu", "Eevee");

    var editor = edit(store, "e1");
    writeEntry(editor, "Jynx", "Ditto");

    context.drainQueuedTasks();
    store.close();

    // Closing the store deletes the editor's work file
    mockStore.assertDirtyEntryFileDoesNotExist("e1");

    // The editor silently discards writes & commits
    writeData(editor, "Charmander");
    editor.commitOnClose();
    editor.close();
    mockStore.assertDirtyEntryFileDoesNotExist("e1");
    mockStore.assertEntryEquals("e1", "Pickachu", "Eevee");
    assertThat(store.size()).isEqualTo(sizeOf("Pickachu", "Eevee"));
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void viewerDisallowsEditsAfterClosingTheStore(Store store, StoreContext context)
      throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Mew", "Mewtwo");

    var viewer = view(store, "e1");

    context.drainQueuedTasks();
    store.close();

    try (viewer) {
      // Viewer keeps operating normally
      assertEntryEquals(viewer, "Mew", "Mewtwo");

      // No edits are allowed
      assertThat(viewer.edit()).isNull();
    }
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  @ExecutorConfig(CACHED_POOL)
  void concurrentRemovals(Store store, StoreContext context, Executor threadPool)
      throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Ditto", "Eevee");

    int removalTriesCount = 10;
    var arrival = new CyclicBarrier(removalTriesCount);
    var assertionTasks = new ArrayList<CompletableFuture<Void>>();
    var removed = new AtomicBoolean();
    for (int i = 0; i < removalTriesCount; i++) {
      var task = Unchecked.runAsync(() -> {
        awaitUninterruptibly(arrival);
        // Assert remove only succeeds once
        assertThat(!store.remove("e1") || removed.compareAndSet(false, true))
            .withFailMessage("more than one removal succeeded")
            .isTrue();
      }, threadPool);

      assertionTasks.add(task);
    }

    assertAll(assertionTasks.stream().map(cf -> cf::join));
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void removeIsAppliedOnDisk(Store store, StoreContext context) throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Jynx", "Ditto");

    assertThat(store.remove("e1")).isTrue();
    mockStore.assertEntryFileDoesNotExist("e1");
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void removeFromViewerIsAppliedOnDisk(Store store, StoreContext context) throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Jynx", "Ditto");

    try (var viewer = view(store, "e1")) {
      assertThat(viewer.removeEntry()).isTrue();
      mockStore.assertEntryFileDoesNotExist("e1");
    }
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void clearIsAppliedOnDisk(Store store, StoreContext context) throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Jynx", "Ditto");
    writeEntry(store, "e2", "Mew", "Charmander");
    writeEntry(store, "e3", "Eevee", "Mewtwo");

    store.clear();
    mockStore.assertHasNoEntriesOnDisk();
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void discardedEditIsAppliedOnDisk(Store store, StoreContext context) throws IOException {
    setUp(context);
    try (var editor = edit(store, "e1")) {
      writeEntry(editor, "Pickachu", "Jynx");
      // Don't commit

      mockStore.assertDirtyEntryFileExists("e1");
    }
    mockStore.assertDirtyEntryFileDoesNotExist("e1");
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void editorWorkFileIsCreatedLazily(Store store, StoreContext context) throws IOException {
    setUp(context);
    try (var editor = edit(store, "e1")) {
      mockStore.assertDirtyEntryFileDoesNotExist("e1");
      writeEntry(editor, "Ditto", "Eevee");
      mockStore.assertDirtyEntryFileExists("e1");
    }
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK, maxSize = 5, execution = QUEUED, indexUpdateDelaySeconds = 1000)
  void evictionRunsSequentially(Store store, StoreContext context) throws IOException {
    setUp(context);

    var executor = context.mockExecutor();
    // Prevent later index writes to not interfere with the executor
    executor.executeOnSameThread(true);
    store.flush();
    executor.executeOnSameThread(false);

    // No data is written to not interfere with the executor with write completion callbacks

    setMetadata(store, "e1", "aaa"); // Grow size to 3 bytes
    setMetadata(store, "e2", "ccc"); // Grow size to 6 bytes
    assertThat(store.size()).isEqualTo(6);
    // An eviction task is submitted
    assertThat(executor.taskCount()).isOne();
    setMetadata(store, "e3", "eee"); // Grow size to 9 bytes
    assertThat(store.size()).isEqualTo(9);
    // No evictions tasks are submitted when one is already "running"
    assertThat(executor.taskCount()).isOne();

    // Get size down to 3
    executor.runNext();
    assertAbsent(store, context, "e1", "e2");
    assertThat(store.size()).isEqualTo(3);

    setMetadata(store, "e1", "hello"); // Grow size to 8 bytes
    assertThat(executor.taskCount()).isOne();

    executor.runNext();
    assertAbsent(store, context, "e3");
    assertThat(store.size()).isEqualTo(5);
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK, maxSize = 10, execution = SAME_THREAD, autoInit = false)
  void storeIsInitializedWithinBounds(Store store, StoreContext context) throws IOException {
    setUp(context);
    mockStore.write("e1", "aaa", "bbb"); // Grow size to 6 bytes
    mockStore.write("e2", "ccc", "ddd"); // Grow size to 12 bytes
    mockStore.writeWorkIndex();

    store.initialize();
    assertAbsent(store, context, "e1");
    assertEntryEquals(store, "e2", "ccc" , "ddd");
    assertThat(store.size()).isEqualTo(6);
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK, maxSize = 10, execution = SAME_THREAD)
  void lruOrderIsPersisted(StoreContext context) throws IOException {
    // LRU queue: e1, e2, e2
    var store1 = context.newStore();
    writeEntry(store1, "e1", "a", "b"); // Grow size to 2 bytes
    writeEntry(store1, "e2", "c", "d"); // Grow size to 4 bytes
    writeEntry(store1, "e3", "e", "f"); // Grow size to 6 bytes
    assertThat(store1.size()).isEqualTo(6);

    // LRU queue: e2, e3, e1
    view(store1, "e1").close();

    // LRU queue: e2, e3, e1, e4
    writeEntry(store1, "e4", "h", "i"); // Grow size to 8 bytes
    assertThat(store1.size()).isEqualTo(8);

    // LRU queue: e3, e1, e4, e2
    view(store1, "e2").close();
    context.drainQueuedTasks();
    store1.close();

    var store2 = context.newStore();
    // Grow size to 16 bytes, causing first 3 LRU entries to be evicted to get back to 10
    writeEntry(store2, "e5", "jjjj", "kkkk");
    assertAbsent(store2, context, "e3", "e1", "e4");
    assertThat(store2.size()).isEqualTo(10);
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void externallyDeletedEntryFile(Store store, StoreContext context) throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Ditto", "Jynx");
    mockStore.delete("e1");
    assertAbsent(store, context, "e1");
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void externallyDeletedEntryFileWhileIterating(Store store, StoreContext context)
      throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Ditto", "Jynx");
    var iter = store.iterator();
    mockStore.delete("e1");
    assertThat(iter.hasNext()).isFalse();
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK, maxSize = 5, execution = QUEUED, indexUpdateDelaySeconds = 0)
  void indexUpdateEvents(Store store, StoreContext context) throws IOException {
    setUp(context);

    var executor = context.mockExecutor();

    // Opening an editor for a previously non-existent entry doesn't issue an index write
    var editor = edit(store, "e1");
    assertThat(executor.taskCount()).isZero();

    // Completing an entry's first edit issues an index write
    setMetadata(editor, "Jynx"); // 4 bytes
    editor.commitOnClose();
    editor.close();
    assertThat(executor.taskCount()).isOne();
    executor.runNext();

    // Opening an editor for an existing entry issues an index write
    edit(store, "e1").close();
    assertThat(executor.taskCount()).isOne();
    executor.runNext();

    // Viewing an existing entry issues an index write
    view(store, "e1").close();
    assertThat(executor.taskCount()).isOne();
    executor.runNext();

    // Attempting to view a non-existent entry doesn't issue an index write
    store.view("e2");
    assertThat(executor.taskCount()).isZero();

    // Removing an existing entry issues an index write
    assertThat(store.remove("e1")).isTrue();
    assertThat(executor.taskCount()).isOne();
    executor.runNext();

    // Removing an existing entry with a viewer issues an index write
    setMetadata(store, "e1", "Steve");
    assertThat(executor.taskCount()).isOne();
    executor.runNext();
    try (var viewer = view(store, "e1")) {
      viewer.removeEntry();
      assertThat(executor.taskCount()).isOne();
      executor.runNext();
    }

    // Removing a non-existent entry doesn't issue an index write
    assertThat(store.remove("e2")).isFalse();
    assertThat(executor.taskCount()).isZero();

    // Clearing the store issues an index write
    store.clear();
    assertThat(executor.taskCount()).isOne();
    executor.runNext();

    // Opening Viewers from iterators issues an index write
    setMetadata(store, "e1", "Jynx"); // 4 bytes
    assertThat(executor.taskCount()).isOne();
    executor.runNext();
    var iter = store.iterator();
    assertThat(executor.taskCount()).isZero();
    assertThat(iter.hasNext()).isTrue();
    iter.next().close();
    assertThat(executor.taskCount()).isOne();
    executor.runNext();

    // Invoking Iterator::remove issues an index write
    iter.remove();
    assertThat(executor.taskCount()).isOne();
    executor.runNext();

    // Writing a new entry issues an index write.
    // Writing is serialized, so only SerialExecutor' drain task is submitted.
    setMetadata(store, "e3", "abc"); // 3 bytes
    setMetadata(store, "e4", "xy"); // 2 bytes
    assertThat(executor.taskCount()).isOne();
    executor.runAll();

    assertThat(store.size()).isEqualTo(5);

    // Eviction due to exceeding the size bound issues an index write
    try (var editor2 = edit(store, "e4")) {
      assertThat(executor.taskCount()).isOne();
      executor.runNext();
      setMetadata(editor2, "xyz"); // Growing e4 to 3 bytes causes e3 to get evicted
      editor2.commitOnClose();

      // Eviction isn't scheduled until the edit is committed
      assertThat(executor.taskCount()).isZero();
    }
    assertThat(executor.taskCount()).isOne();
    executor.runNext();
    assertAbsent(store, context, "e3");
  }

  @StoreParameterizedTest
  @StoreConfig(
      store = DISK,
      execution = QUEUED,
      autoAdvanceClock = false,
      indexUpdateDelaySeconds = 2)
  void indexUpdatesAreTimeLimited(Store store, StoreContext context) throws IOException {
    setUp(context);

    var clock = context.clock();
    var executor = context.mockExecutor();
    var delayer = context.delayer();

    // t = 0
    // First index write is dispatched immediately
    setMetadata(store, "e1", "a");
    assertThat(executor.taskCount()).isOne();
    executor.runNext();

    // t = 0
    // An index write is scheduled to run in 2 seconds as one has just run
    view(store, "e1").close();
    assertThat(delayer.taskCount()).isOne();
    assertThat(executor.taskCount()).isZero();

    clock.advanceSeconds(1);
    // t = 1
    // 1 second is still remaining till the scheduled write is dispatched to the executor
    assertThat(executor.taskCount()).isZero();

    clock.advanceSeconds(1);
    // t = 2
    // Now the write is dispatched to the executor
    assertThat(executor.taskCount()).isOne();
    executor.runNext();

    // t = 2
    // An index write is scheduled to run in 2 seconds as one has just run
    view(store, "e1").close();
    assertThat(delayer.taskCount()).isOne();
    assertThat(executor.taskCount()).isZero();

    clock.advanceSeconds(1);
    // t = 3
    // An index write is not scheduled since one is still going to run in 1 second
    view(store, "e1").close();
    assertThat(delayer.taskCount()).isOne();
    assertThat(executor.taskCount()).isZero();

    clock.advanceSeconds(1);
    // t = 4
    // The scheduled write is dispatched and a new write is scheduled to run
    // in 2 seconds as one has just run.
    assertThat(executor.taskCount()).isOne();
    executor.runNext();
    view(store, "e1").close();
    assertThat(delayer.taskCount()).isOne();

    clock.advanceSeconds(4);
    // t = 8
    // An index write is dispatched immediately as two seconds have passed since the last write
    view(store, "e1").close();
    assertThat(delayer.taskCount()).isZero();
    assertThat(executor.taskCount()).isOne();
    executor.runNext();

    clock.advanceSeconds(2);
    // t = 10
    // An index write is dispatched immediately as two seconds have passed since the last write
    view(store, "e1").close();
    assertThat(delayer.taskCount()).isZero();
    assertThat(executor.taskCount()).isOne();
    executor.runNext();
  }

  @StoreParameterizedTest
  @StoreConfig(
      store = DISK,
      execution = SAME_THREAD,
      autoAdvanceClock = false,
      indexUpdateDelaySeconds = 1000)
  void indexIsFlushedOnClosure(Store store, StoreContext context) throws IOException {
    setUp(context);
    var clock = context.clock();
    try (store) {
      // Prevent index writes before closing
      store.flush();

      writeEntry(store, "e1", "Ditto", "Eevee");
      clock.advanceSeconds(1);
      writeEntry(store, "e2", "Mew", "Mewtwo");
      clock.advanceSeconds(1);
      writeEntry(store, "e3", "Jynx", "Snorlax");

      mockStore.assertEmptyIndex();
    }

    var start = context.clock().inception();
    mockStore.assertIndexEquals(
        "e1", start, sizeOf("Ditto", "Eevee"),
        "e2", start.plusSeconds(1), sizeOf("Mew", "Mewtwo"),
        "e3", start.plusSeconds(2), sizeOf("Jynx", "Snorlax"));
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK, autoInit = false)
  void queryingSizeInitializesTheStore(Store store, StoreContext context) throws IOException {
    setUp(context);
    mockStore.write("e1", "Mew", "Pickachu");
    mockStore.write("e2", "Mewtwo", "Jigglypuff");
    mockStore.writeWorkIndex();

    assertThat(store.size()).isEqualTo(sizeOf("Mew", "Pickachu", "Mewtwo", "Jigglypuff"));
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void entryFileIsTruncatedWhenMetadataShrinks(Store store, StoreContext context)
      throws IOException {
    setUp(context);

    writeEntry(store, "e1", "123", "abc");
    long sizeBeforeShrinking = Files.size(mockStore.entryFile("e1"));

    // Shrink metadata by 1 byte
    try (var editor = edit(store, "e1")) {
      setMetadata(editor, "12");
      editor.commitOnClose();
    }

    long sizeAfterShrinking = Files.size(mockStore.entryFile("e1"));
    assertThat(sizeBeforeShrinking)
        .withFailMessage("%d -> %d", sizeBeforeShrinking, sizeAfterShrinking)
        .isEqualTo(sizeAfterShrinking + 1);
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK, maxSize = 10, execution = SAME_THREAD)
  void entryExceedingMaxSizeIsIgnored(Store store, StoreContext context) throws IOException {
    setUp(context);

    writeEntry(store, "e1", "12", "abc"); // 5 bytes
    writeEntry(store, "e2", "123", "abc"); // 6 bytes -> e1 is evicted to accommodate e2
    assertAbsent(store, context, "e1");
    assertEntryEquals(store, "e2", "123", "abc");
    assertThat(store.size()).isEqualTo(6);
    mockStore.assertDirtyEntryFileDoesNotExist("e3");

    writeEntry(store, "e3", "12345", "abcxyz"); // 11 bytes -> e3 is ignored & e2 remains untouched
    assertAbsent(store, context, "e3");
    assertEntryEquals(store, "e2", "123", "abc");
    assertThat(store.size()).isEqualTo(6);
    mockStore.assertDirtyEntryFileDoesNotExist("e3");
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK, maxSize = 5)
  void editExceedingMaxSizeByWritingIsSilentlyRefused(Store store, StoreContext context)
      throws IOException {
    setUp(context);

    // Exercise exceeding maxSize by writing data
    try (var editor = edit(store, "e1")) {
      setMetadata(editor, "12");
      writeData(editor, "abcd"); // Exceed limit by 1 byte

      editor.commitOnClose();
    }
    assertAbsent(store, context, "e1");
    mockStore.assertDirtyEntryFileDoesNotExist("e1");
    assertThat(store.size()).isZero();
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK, maxSize = 5)
  void editExceedingMaxSizeBySettingMetadataIsSilentlyRefused(Store store, StoreContext context)
      throws IOException {
    setUp(context);

    // Exercise exceeding maxSize by setting metadata
    try (var editor = edit(store, "e1")) {
      writeData(editor, "abcd");
      setMetadata(editor, "12"); // Exceed limit by 1 bytes

      editor.commitOnClose();
    }
    assertAbsent(store, context, "e1");
    mockStore.assertDirtyEntryFileDoesNotExist("e1");
    assertThat(store.size()).isZero();
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK, maxSize = 5)
  void editorExceedingMaxSizeIsSilentlyRefused(Store store, StoreContext context) throws IOException {
    setUp(context);

    // Exercise exceeding maxSize multiple times
    try (var editor = edit(store, "e1")) {
      // Don't exceed size limit
      setMetadata(editor, "12");
      editor.writeAsync(0, UTF_8.encode("aaa")).join();

      // Exceed size limit by 2 bytes twice
      setMetadata(editor, "1234");
      editor.writeAsync(3, UTF_8.encode("aa")).join();

      editor.commitOnClose();
    }
    assertAbsent(store, context, "e1");
    mockStore.assertDirtyEntryFileDoesNotExist("e1");
    assertThat(store.size()).isZero();
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void closedStoreIsInoperable(StoreContext context) throws IOException {
    setUp(context);

    var store1 = context.newStore();
    context.drainQueuedTasks();
    store1.close();
    assertInoperable(store1);

    var store2 = context.newStore();
    context.drainQueuedTasks();
    store2.dispose();
    assertInoperable(store2);
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void viewerRemoveIsDisallowedAfterClosingTheStore(Store store, StoreContext context)
      throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Eevee", "Jynx");

    try (var viewer = view(store, "e1")) {
      store.close();

      assertThatIllegalStateException().isThrownBy(viewer::removeEntry);
      mockStore.assertEntryEquals("e1", "Eevee", "Jynx");
      assertThat(mockStore.readIndex().contains(context.hasher().hash("e1"))).isTrue();
    }
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void viewerRemoveIsDisallowedAfterDisposingTheStore(Store store, StoreContext context)
      throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Eevee", "Jynx");

    try (var viewer = view(store, "e1")) {
      store.dispose();

      assertThatIllegalStateException().isThrownBy(viewer::removeEntry);
    }
  }

  /** Closing the store while iterating silently terminates iteration. */
  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void closeWhileIterating(Store store, StoreContext context) throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Ditto", "Charmander");
    writeEntry(store, "e2", "Eevee", "Jynx");

    var iter = store.iterator();
    assertThat(iter.hasNext()).isTrue();
    iter.next().close(); // Consume next
    context.drainQueuedTasks();
    store.close();
    assertThat(iter.hasNext()).isFalse();
    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(iter::next);
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void closeStoreWhileReading(Store store, StoreContext context) throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Jynx", "Ditto");

    try (var viewer = view(store, "e1")) {
      store.close();

      assertEntryEquals(viewer, "Jynx", "Ditto");
    }
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void disposeClearsStoreContent(Store store, StoreContext context) throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Jynx", "Eevee");

    store.dispose();
    assertInoperable(store);
    assertThat(store.iterator().hasNext()).isFalse();
    assertThat(store.size()).isZero();
    assertThat(context.directory()).isEmptyDirectory();
  }

  // Using two tests as JUnit 5 doesn't allow RepeatedTest + ParameterizedTest

  @RepeatedTest(10)
  @StoreConfig(store = DISK, fileSystem = SYSTEM, execution = QUEUED, indexUpdateDelaySeconds = 0)
  @ExecutorConfig(CACHED_POOL)
  void disposeDuringIndexWrite_systemFileSystem(
      Store store, StoreContext context, Executor threadPool) throws IOException {
    testDisposeDuringIndexWrite(store, context, threadPool);
  }

  @RepeatedTest(10)
  @StoreConfig(store = DISK, fileSystem = WINDOWS, execution = QUEUED, indexUpdateDelaySeconds = 0)
  @ExecutorConfig(CACHED_POOL)
  void disposeDuringIndexWrite_windowsEmulatingFilesystem(
      Store store, StoreContext context, Executor threadPool) throws IOException {
    testDisposeDuringIndexWrite(store, context, threadPool);
  }

  private void testDisposeDuringIndexWrite(
      Store store, StoreContext context, Executor threadPool) throws IOException {
    setUp(context);

    var executor = context.mockExecutor();
    setMetadata(store, "e1", "Jynx");
    assertThat(executor.taskCount()).isOne();
    executor.runNext();

    for (int i = 0; i < 10; i++) {
      view(store, "e1").close(); // Trigger IndexWriteScheduler
    }
    // Since index is written with a SerialExecutor, only one drain task is dispatched
    assertThat(executor.taskCount()).isOne();

    var arrival = new CyclicBarrier(2);
    var triggerWrite = Unchecked.runAsync(() -> {
      awaitUninterruptibly(arrival);
      executor.runNext();
    }, threadPool);
    var invokeDispose = Unchecked.runAsync(() -> {
      awaitUninterruptibly(arrival);
      store.dispose();
    }, threadPool);

    CompletableFuture.allOf(triggerWrite, invokeDispose).join();
    assertThat(executor.taskCount()).isZero();
    assertThat(context.directory()).isEmptyDirectory();
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void disposeStoreWhileReading(Store store, StoreContext context) throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Jynx", "Ditto");

    try (var viewer = view(store, "e1")) {
      store.dispose();
      mockStore.assertHasNoEntriesOnDisk();

      assertEntryEquals(viewer, "Jynx", "Ditto");
    }
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK)
  void hashCollisionOnViewing(Store store, StoreContext context) throws IOException {
    setUp(context);
    context.hasher().setHash("e1", 1);
    context.hasher().setHash("e2", 1);

    writeEntry(store, "e1", "Jynx", "Psyduck");
    assertThat(store.view("e2")).isNull();
    assertEntryEquals(store, "e1", "Jynx", "Psyduck");
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK, autoInit = false)
  void hashCollisionOnViewingRecoveredEntry(Store store, StoreContext context) throws IOException {
    setUp(context);
    context.hasher().setHash("e1", 1);
    context.hasher().setHash("e2", 1);

    mockStore.write("e1", "Jynx", "Psyduck");
    mockStore.writeWorkIndex();

    store.initialize();
    assertThat(store.view("e2")).isNull();
    assertEntryEquals(store, "e1", "Jynx", "Psyduck");
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK, autoInit = false)
  void hashCollisionOnRemoval(Store store, StoreContext context) throws IOException {
    setUp(context);
    context.hasher().setHash("e1", 1);
    context.hasher().setHash("e2", 1);

    mockStore.write("e1", "Jynx", "Psyduck");
    mockStore.writeWorkIndex();

    // e2 removes e1 since e1 isn't read or edited so it doesn't know its key
    store.initialize();
    assertThat(store.remove("e2")).isTrue();
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK, autoInit = false)
  void hashCollisionOnRemovalAfterView(Store store, StoreContext context) throws IOException {
    setUp(context);
    context.hasher().setHash("e1", 1);
    context.hasher().setHash("e2", 1);

    mockStore.write("e1", "Jynx", "Psyduck");
    mockStore.writeWorkIndex();

    store.initialize();
    view(store, "e1").close();

    // e2 doesn't remove e1 since e1 knows its key due to being read
    assertThat(store.remove("e2")).isFalse();
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK, autoInit = false)
  void hashCollisionOnRemovalAfterEdit(Store store, StoreContext context) throws IOException {
    setUp(context);
    context.hasher().setHash("e1", 1);
    context.hasher().setHash("e2", 1);

    store.initialize();
    writeEntry(store, "e1", "Jynx", "Psyduck");

    // e2 doesn't remove e1 since the entry knows its key due to being written
    assertThat(store.remove("e2")).isFalse();
  }

  @StoreParameterizedTest
  @StoreConfig(store = DISK, autoInit = false)
  void hashCollisionOnCompletedEdit(Store store, StoreContext context) throws IOException {
    setUp(context);
    context.hasher().setHash("e1", 1);
    context.hasher().setHash("e2", 1);

    store.initialize();
    writeEntry(store, "e1", "Jynx", "Psyduck");
    writeEntry(store, "e2", "Eevee", "Mewtwo");

    // e2 replaces e1 as they collide
    assertThat(store.view("e1")).isNull();
    assertEntryEquals(store, "e2", "Eevee", "Mewtwo");

    // e1 doesn't remove e2 as it knows its key due to being edited
    assertThat(store.remove("e1")).isFalse();
  }

  private static void assertInoperable(Store store) {
    assertThatIllegalStateException().isThrownBy(() -> store.view("e1"));
    assertThatIllegalStateException().isThrownBy(() -> store.edit("e1"));
    assertThatIllegalStateException().isThrownBy(() -> store.remove("e1"));
    assertThatIllegalStateException().isThrownBy(store::clear);
  }
}
