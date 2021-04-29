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

import static com.github.mizosoft.methanol.ExecutorProvider.ExecutorType.CACHED_POOL;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.assertAbsent;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.assertEntryEquals;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.assertUnreadable;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.edit;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.setMetadata;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.sizeOf;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.view;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.writeData;
import static com.github.mizosoft.methanol.internal.cache.StoreTesting.writeEntry;
import static com.github.mizosoft.methanol.testing.extensions.StoreProvider.StoreConfig.Execution.QUEUED;
import static com.github.mizosoft.methanol.testing.extensions.StoreProvider.StoreConfig.Execution.SAME_THREAD;
import static com.github.mizosoft.methanol.testing.extensions.StoreProvider.StoreConfig.StoreType.DISK;
import static com.github.mizosoft.methanol.testutils.TestUtils.awaitUninterruptibly;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.mizosoft.methanol.ExecutorProvider;
import com.github.mizosoft.methanol.ExecutorProvider.ExecutorConfig;
import com.github.mizosoft.methanol.internal.cache.MockDiskStore.DiskEntry;
import com.github.mizosoft.methanol.internal.cache.MockDiskStore.EntryCorruptionMode;
import com.github.mizosoft.methanol.internal.cache.MockDiskStore.Index;
import com.github.mizosoft.methanol.internal.cache.MockDiskStore.IndexCorruptionMode;
import com.github.mizosoft.methanol.internal.function.Unchecked;
import com.github.mizosoft.methanol.testing.extensions.StoreProvider;
import com.github.mizosoft.methanol.testing.extensions.StoreProvider.StoreConfig;
import com.github.mizosoft.methanol.testing.extensions.StoreProvider.StoreContext;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;

/** DiskStore specific tests that are complementary to {@link StoreTest}. */
@Timeout(60)
@ExtendWith({StoreProvider.class, ExecutorProvider.class})
class DiskStoreTest {
  private @MonotonicNonNull MockDiskStore mockStore;

  void setUp(StoreContext context) {
    mockStore = new MockDiskStore(context);
  }

  @ParameterizedTest
  @StoreConfig(store = DISK, autoInit = false)
  void initializeWithNonExistentDirectory(Store store, StoreContext context)
      throws IOException {
    setUp(context);
    Files.delete(context.directory());

    store.initialize();
    assertTrue(Files.exists(context.directory()));
    mockStore.assertEmptyIndex();
  }

  @ParameterizedTest
  @StoreConfig(store = DISK, autoInit = false)
  void persistenceOnInitialization(Store store, StoreContext context) throws IOException {
    setUp(context);
    mockStore.write("e1", "Ditto", "Eevee");
    mockStore.write("e2", "Mew", "Mewtwo");
    mockStore.writeWorkIndex();

    store.initialize();
    assertEntryEquals(store, "e1", "Ditto", "Eevee");
    assertEntryEquals(store, "e2", "Mew", "Mewtwo");
    assertEquals(sizeOf("Ditto", "Eevee", "Mew", "Mewtwo"), store.size());
  }

  @ParameterizedTest
  @StoreConfig(store = DISK)
  void persistenceAcrossSessions(StoreContext context) throws IOException {
    setUp(context);
    var store1 = context.newStore();
    writeEntry(store1, "e1", "Mewtwo", "Charmander");
    writeEntry(store1, "e2", "Psyduck", "Pickachu");
    store1.close();

    var store2 = context.newStore();
    assertEntryEquals(store2, "e1", "Mewtwo", "Charmander");
    assertEntryEquals(store2, "e2", "Psyduck", "Pickachu");
    assertEquals(sizeOf("Mewtwo", "Charmander", "Psyduck", "Pickachu"), store2.size());
  }

  @ParameterizedTest
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
    var tasks = new ArrayList<CompletableFuture<Void>>();
    for (int i = 0; i < initCount; i++) {
      var task = Unchecked.runAsync(() -> {
        awaitUninterruptibly(arrival);

        store.initialize();
        assertEntryEquals(store, "e1", "Ditto", "Eevee");
      }, threadPool);

      tasks.add(task);
    }

    assertAll(tasks.stream().map(cf -> cf::join));
  }

  /** Dirty entry files of untracked entries found during initialization are deleted. */
  @ParameterizedTest
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
    assertFalse(mockStore.dirtyEntryFileExists("e2"));
    assertFalse(mockStore.dirtyEntryFileExists("e3"));
    assertAbsent(store, context, "e2", "e3");
    assertEntryEquals(store, "e1", "Eevee", "Jigglypuff");
    assertEquals(sizeOf("Eevee", "Jigglypuff"), store.size());
  }

  /** Dirty entry files of tracked entries found during initialization are deleted. */
  @ParameterizedTest
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
    assertFalse(mockStore.dirtyEntryFileExists("e1"));
    assertFalse(mockStore.dirtyEntryFileExists("e2"));
    assertFalse(mockStore.dirtyEntryFileExists("e3"));
    assertEntryEquals(store, "e1", "Eevee", "Jigglypuff");
    assertEntryEquals(store, "e2", "Jynx", "Mew");
    assertEntryEquals(store, "e3", "Psyduck", "Raichu");
  }

  @ParameterizedTest
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
    assertFalse(mockStore.dirtyEntryFileExists("e1"));
    assertAbsent(store, context, "e1");
    assertEquals(0, store.size());
  }

  @ParameterizedTest
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
    assertFalse(mockStore.dirtyEntryFileExists("e3"));
    assertEntryEquals(store, "e1", "Psyduck", "Pickachu");
    assertEquals(sizeOf("Psyduck", "Pickachu"), store.size());
  }

  @ParameterizedTest
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
    assertEquals(sizeOf("Mew", "Eevee"), store.size());
  }

  @ParameterizedTest
  @StoreConfig(store = DISK, autoInit = false)
  void storeContentsIsDroppedOnCorruptIndex(StoreContext context) throws IOException {
    setUp(context);
    for (var corruptMode : IndexCorruptionMode.values()) {
      try {
        var store = context.newStore();
        assertStoreContentsIsDroppedOnCorruptIndex(store, context, corruptMode);

        // Clean the store directory
        store.close();
        mockStore.delete();
      } catch (AssertionError e) {
        fail(corruptMode.toString(), e);
      }
    }
  }

  private void assertStoreContentsIsDroppedOnCorruptIndex(
      Store store, StoreContext context, IndexCorruptionMode corruptionMode) throws IOException {
    mockStore.write("e1", "Ditto", "Eevee");
    mockStore.write("e2", "Jynx", "Snorlax");
    mockStore.writeIndex(mockStore.copyWorkIndex(), corruptionMode);

    store.initialize();
    mockStore.assertHasNoEntriesOnDisk();
    assertAbsent(store, context, "e1", "e2");
    assertEquals(0, store.size());

    // The corrupt index is overwritten with an empty index
    mockStore.assertEmptyIndex();
    // Make sure the lock file is not deleted with the store content
    assertTrue(mockStore.lockFileExists());
  }

  @ParameterizedTest
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
    assertFalse(index.contains(context.hasher().hash("e1")));

    // Completing the edit makes the entry readable
    editor.close();
    store.flush();

    var index2 = mockStore.readIndex();
    assertTrue(index2.contains(context.hasher().hash("e1")));
  }

  @ParameterizedTest
  @StoreConfig(store = DISK)
  void removeBeforeFlush(Store store, StoreContext context) throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Mew", "Mewtwo");

    assertTrue(store.remove("e1"));
    store.flush();

    var index = mockStore.readIndex();
    assertFalse(index.contains(context.hasher().hash("e1")));
  }

  @ParameterizedTest
  @StoreConfig(store = DISK)
  void clearBeforeFlush(Store store, StoreContext context) throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Mew", "Mewtwo");
    writeEntry(store, "e2", "Jynx", "Ditto");

    store.clear();
    store.flush();
    mockStore.assertHasNoEntriesOnDisk();

    var index = mockStore.readIndex();
    assertFalse(index.contains(context.hasher().hash("e1")));
    assertFalse(index.contains(context.hasher().hash("e2")));
  }

  @ParameterizedTest
  @StoreConfig(
      store = DISK,
      maxSize = 10,
      execution = SAME_THREAD)
  void lruEvictionBeforeFlush(Store store, StoreContext context) throws IOException {
    setUp(context);
    writeEntry(store, "e1", "aaa", "bbb"); // Grow size to 6 bytes
    writeEntry(store, "e2", "ccc", "ddd"); // Grow size to 12 bytes, causing e1 to be evicted
    assertAbsent(store, context, "e1");
    assertEquals(6, store.size());
    store.flush();

    var index = mockStore.readIndex();
    assertFalse(index.contains(context.hasher().hash("e1")));
    assertTrue(index.contains(context.hasher().hash("e2")));
  }

  @ParameterizedTest
  @StoreConfig(store = DISK)
  void closingTheStoreDiscardsIncompleteFirstEdit(Store store, StoreContext context)
      throws IOException {
    setUp(context);
    var editor = edit(store, "e1");
    writeEntry(editor, "Jynx", "Ditto");

    store.close();

    // Closing the store deletes the editor's work file
    assertFalse(mockStore.dirtyEntryFileExists("e1"));

    // The entry isn't in the index since it wasn't readable before closing
    var index = mockStore.readIndex();
    assertFalse(index.contains(context.hasher().hash("e1")));

    // The editor silently discards writes & commits
    writeData(editor, "Charmander");
    editor.commitOnClose();
    editor.close();
    assertFalse(mockStore.entryFileExists("e1"));
    assertFalse(mockStore.dirtyEntryFileExists("e1"));
  }

  @ParameterizedTest
  @StoreConfig(store = DISK)
  void closingTheStoreDiscardsIncompleteSecondEdit(Store store, StoreContext context)
      throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Pickachu", "Eevee");

    var editor = edit(store, "e1");
    writeEntry(editor, "Jynx", "Ditto");

    store.close();

    // Closing the store deletes the editor's work file
    assertFalse(mockStore.dirtyEntryFileExists("e1"));

    // The editor silently discards writes & commits
    writeData(editor, "Charmander");
    editor.commitOnClose();
    editor.close();
    assertFalse(mockStore.dirtyEntryFileExists("e1"));
    mockStore.assertEntryEquals("e1", "Pickachu", "Eevee");
    assertEquals(sizeOf("Pickachu", "Eevee"), store.size());
  }

  @ParameterizedTest
  @StoreConfig(store = DISK)
  @ExecutorConfig(CACHED_POOL)
  void concurrentRemovals(Store store, StoreContext context, Executor threadPool)
      throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Ditto", "Eevee");

    int removalCount = 10;
    var arrival = new CyclicBarrier(removalCount);
    var tasks = new ArrayList<CompletableFuture<Void>>();
    var removed = new AtomicBoolean();
    for (int i = 0; i < removalCount; i++) {
      var task = Unchecked.runAsync(() -> {
        awaitUninterruptibly(arrival);
        // Assert remove only succeeds once
        assertTrue(!store.remove("e1") || removed.compareAndSet(false, true));
      }, threadPool);

      tasks.add(task);
    }

    assertAll(tasks.stream().map(cf -> cf::join));
  }

  @ParameterizedTest
  @StoreConfig(store = DISK)
  void removeIsAppliedOnDisk(Store store, StoreContext context) throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Jynx", "Ditto");

    assertTrue(store.remove("e1"));
    assertFalse(mockStore.entryFileExists("e1"));
  }

  @ParameterizedTest
  @StoreConfig(store = DISK)
  void clearIsAppliedOnDisk(Store store, StoreContext context) throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Jynx", "Ditto");
    writeEntry(store, "e2", "Mew", "Charmander");
    writeEntry(store, "e3", "Eevee", "Mewtwo");

    store.clear();
    mockStore.assertHasNoEntriesOnDisk();
  }

  @ParameterizedTest
  @StoreConfig(store = DISK)
  void discardedEditIsAppliedOnDisk(Store store, StoreContext context) throws IOException {
    setUp(context);
    try (var editor = edit(store, "e1")) {
      writeEntry(editor, "Pickachu", "Jynx");
      // Don't commit

      assertTrue(mockStore.dirtyEntryFileExists("e1"));
    }
    assertFalse(mockStore.dirtyEntryFileExists("e1"));
  }

  @ParameterizedTest
  @StoreConfig(store = DISK)
  void editorWorkFileIsCreatedLazily(Store store, StoreContext context) throws IOException {
    setUp(context);
    try (var editor = edit(store, "e1")) {
      assertFalse(mockStore.dirtyEntryFileExists("e1"));
      writeEntry(editor, "Ditto", "Eevee");
      assertTrue(mockStore.dirtyEntryFileExists("e1"));
    }
  }

  @ParameterizedTest
  @StoreConfig(store = DISK, maxSize = 5, execution = QUEUED, indexFlushDelaySeconds = 1000)
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
    assertEquals(6, store.size());
    // An eviction task is submitted
    assertEquals(1, executor.taskCount());
    setMetadata(store, "e3", "eee"); // Grow size to 9 bytes
    assertEquals(9, store.size());
    // No evictions tasks are submitted when one is already "running"
    assertEquals(1, executor.taskCount());

    // Get size down to 3
    executor.runNext();
    assertAbsent(store, context, "e1", "e2");
    assertEquals(3, store.size());

    setMetadata(store, "e1", "hello"); // Grow size to 8 bytes
    assertEquals(1, executor.taskCount());

    executor.runNext();
    assertAbsent(store, context, "e3");
    assertEquals(5, store.size());
  }

  @ParameterizedTest
  @StoreConfig(store = DISK, maxSize = 10, execution = SAME_THREAD, autoInit = false)
  void storeIsInitializedWithinBounds(Store store, StoreContext context) throws IOException {
    setUp(context);
    mockStore.write("e1", "aaa", "bbb"); // Grow size to 6 bytes
    mockStore.write("e2", "ccc", "ddd"); // Grow size to 12 bytes
    mockStore.writeWorkIndex();

    store.initialize();
    assertAbsent(store, context, "e1");
    assertEntryEquals(store, "e2", "ccc" , "ddd");
    assertEquals(6, store.size());
  }

  @ParameterizedTest
  @StoreConfig(store = DISK, maxSize = 10, execution = SAME_THREAD)
  void lruOrderIsPersisted(StoreContext context) throws IOException {
    // LRU queue: e1, e2, e2
    var store1 = context.newStore();
    writeEntry(store1, "e1", "a", "b"); // Grow size to 2 bytes
    writeEntry(store1, "e2", "c", "d"); // Grow size to 4 bytes
    writeEntry(store1, "e3", "e", "f"); // Grow size to 6 bytes
    assertEquals(6, store1.size());

    // LRU queue: e2, e3, e1
    view(store1, "e1").close();

    // LRU queue: e2, e3, e1, e4
    writeEntry(store1, "e4", "h", "i"); // Grow size to 8 bytes
    assertEquals(8, store1.size());

    // LRU queue: e3, e1, e4, e2
    view(store1, "e2").close();
    store1.close();

    var store2 = context.newStore();
    // Grow size to 16 bytes, causing first 3 LRU entries to be evicted to get back to 10
    writeEntry(store2, "e5", "jjjj", "kkkk");
    assertAbsent(store2, context, "e3", "e1", "e4");
    assertEquals(10, store2.size());
  }

  @ParameterizedTest
  @StoreConfig(store = DISK)
  void externallyDeletedEntryFile(Store store, StoreContext context) throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Ditto", "Jynx");
    mockStore.delete("e1");
    assertAbsent(store, context, "e1");
  }

  @ParameterizedTest
  @StoreConfig(store = DISK)
  void externallyDeletedEntryFileWhileIterating(Store store, StoreContext context)
      throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Ditto", "Jynx");
    var iter = store.viewAll();
    mockStore.delete("e1");
    assertFalse(iter.hasNext());
  }

  @ParameterizedTest
  @StoreConfig(store = DISK, maxSize = 5, execution = QUEUED, indexFlushDelaySeconds = 0)
  void indexUpdateEvents(Store store, StoreContext context) throws IOException {
    setUp(context);

    var executor = context.mockExecutor();

    // Editing a previously non-existent entry doesn't issue an index write
    var editor = edit(store, "e1");
    assertEquals(0, executor.taskCount());

    // Completing an entry's first edit issues an index write
    setMetadata(editor, "Jynx"); // 4 bytes
    editor.commitOnClose();
    editor.close();
    assertEquals(1, executor.taskCount());
    executor.runNext();

    // Opening an editor for an existing entry issues an index write
    edit(store, "e1").close();
    assertEquals(1, executor.taskCount());
    executor.runNext();

    // Viewing an existing entry issues an index write
    view(store, "e1").close();
    assertEquals(1, executor.taskCount());
    executor.runNext();

    // Attempting to view a non-existent entry doesn't issue an index write
    store.view("e2");
    assertEquals(0, executor.taskCount());

    // Removing an existing entry issues an index write
    assertTrue(store.remove("e1"));
    assertEquals(1, executor.taskCount());
    executor.runNext();

    // Removing a non-existent entry doesn't issue an index write
    assertFalse(store.remove("e2"));
    assertEquals(0, executor.taskCount());

    // Clearing the store issues an index write
    store.clear();
    assertEquals(1, executor.taskCount());
    executor.runNext();

    // Opening Viewers from iterators issues an index write
    setMetadata(store, "e1", "Jynx"); // 4 bytes
    assertEquals(1, executor.taskCount());
    executor.runNext();
    var iter = store.viewAll();
    assertEquals(0, executor.taskCount());
    assertTrue(iter.hasNext());
    iter.next();
    assertEquals(1, executor.taskCount());
    executor.runNext();

    // Invoking Iterator::remove issues an index write
    iter.remove();
    assertEquals(1, executor.taskCount());
    executor.runNext();

    // Eviction due to exceeding the size bound issues an index write
    setMetadata(store, "e3", "Jynx"); // 4 bytes
    assertEquals(1, executor.taskCount());
    executor.runNext();
    try (var editor2 = edit(store, "e3")) {
      assertEquals(1, executor.taskCount());
      executor.runNext();

      setMetadata(editor2, "Jigglypuff"); // Grow to 10 bytes
      editor2.commitOnClose();

      // Eviction isn't scheduled until the edit is committed
      assertEquals(0, executor.taskCount());
    }
    assertEquals(1, executor.taskCount());
    executor.runNext();
  }

  @ParameterizedTest
  @StoreConfig(
      store = DISK,
      execution = QUEUED,
      autoAdvanceClock = false,
      indexFlushDelaySeconds = 2)
  void indexUpdatesAreTimeLimited(Store store, StoreContext context) throws IOException {
    setUp(context);
    setMetadata(store, "e1", "a");

    var clock = context.clock();
    var executor = context.mockExecutor();

    // First index write is executed immediately
    view(store, "e1").close();
    assertEquals(1, executor.taskCount());
    executor.runNext();

    // An index write is schedule to run 2 seconds
    // in the future as one has just run.
    view(store, "e1").close();
    assertEquals(0, executor.taskCount());

    // An index write is not scheduled since one is going
    // to run in 1 second.
    clock.advanceSeconds(1);
    view(store, "e1").close();
    assertEquals(0, executor.taskCount());

    // An index write is scheduled since 2 seconds have
    // passed since the last write.
    clock.advanceSeconds(3);
    view(store, "e1").close();
    assertEquals(1, executor.taskCount());
    executor.runAll();

    // An index write is scheduled since 2 seconds have
    // passed since the last write.
    clock.advanceSeconds(2);
    view(store, "e1").close();
    assertEquals(1, executor.taskCount());
    executor.runAll();
  }

  @ParameterizedTest
  @StoreConfig(
      store = DISK,
      execution = SAME_THREAD,
      autoAdvanceClock = false,
      indexFlushDelaySeconds = 1000)
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

  @ParameterizedTest
  @StoreConfig(store = DISK)
  void closedStoreIsInoperable(StoreContext context) throws IOException {
    setUp(context);

    var store1 = context.newStore();
    store1.close();
    assertInoperable(store1);

    // Closed by disposing
    var store2 = context.newStore();
    store2.dispose();
    assertInoperable(store2);
  }

  /** Closing the store while iterating silently terminates iteration. */
  @ParameterizedTest
  @StoreConfig(store = DISK)
  void closeWhileIterating(Store store, StoreContext context) throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Ditto", "Charmander");
    writeEntry(store, "e2", "Eevee", "Jynx");

    var iter = store.viewAll();
    assertTrue(iter.hasNext());
    iter.next(); // Consume next
    store.close();
    assertFalse(iter.hasNext());
    assertThrows(NoSuchElementException.class, iter::next);
  }

  @ParameterizedTest
  @StoreConfig(store = DISK)
  void disposeClearsStoreContent(Store store, StoreContext context) throws IOException {
    setUp(context);
    writeEntry(store, "e1", "Jynx", "Eevee");

    store.dispose();
    assertInoperable(store);
    assertEquals(0, store.size());
    assertFalse(store.viewAll().hasNext());
    assertFalse(mockStore.indexFileExists());
    assertFalse(mockStore.lockFileExists());
    mockStore.assertHasNoEntriesOnDisk();
  }

  @ParameterizedTest
  @StoreConfig(store = DISK, autoInit = false)
  void entryCorruption(StoreContext context) throws IOException {
    setUp(context);
    for (var corruptMode : EntryCorruptionMode.values()) {
      try {
        var store = context.newStore();
        assertEntryCorruption(store, corruptMode);

        // Clean the store directory
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
    assertThrows(StoreCorruptionException.class, () -> view(store, "e1"));

    // Currently the entry is not automatically removed
    assertTrue(mockStore.entryFileExists("e1"));
  }

  @ParameterizedTest
  @StoreConfig(store = DISK)
  void hashCollisionOnViewing(Store store, StoreContext context) throws IOException {
    setUp(context);
    context.hasher().setHash("e1", 1);
    context.hasher().setHash("e2", 1);

    writeEntry(store, "e1", "Jynx", "Psyduck");
    assertNull(store.view("e2"));
    assertEntryEquals(store, "e1", "Jynx", "Psyduck");
  }

  @ParameterizedTest
  @StoreConfig(store = DISK, autoInit = false)
  void hashCollisionOnViewingRecoveredEntry(Store store, StoreContext context) throws IOException {
    setUp(context);
    context.hasher().setHash("e1", 1);
    context.hasher().setHash("e2", 1);

    mockStore.write("e1", "Jynx", "Psyduck");
    mockStore.writeWorkIndex();

    store.initialize();
    assertNull(store.view("e2"));
    assertEntryEquals(store, "e1", "Jynx", "Psyduck");
  }

  @ParameterizedTest
  @StoreConfig(store = DISK, autoInit = false)
  void hashCollisionOnRemoval(Store store, StoreContext context) throws IOException {
    setUp(context);
    context.hasher().setHash("e1", 1);
    context.hasher().setHash("e2", 1);

    mockStore.write("e1", "Jynx", "Psyduck");
    mockStore.writeWorkIndex();

    // e2 removes e1 since e1 isn't read or edited so it doesn't know its key
    store.initialize();
    assertTrue(store.remove("e2"));
  }

  @ParameterizedTest
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
    assertFalse(store.remove("e2"));
  }

  @ParameterizedTest
  @StoreConfig(store = DISK, autoInit = false)
  void hashCollisionOnRemovalAfterEdit(Store store, StoreContext context) throws IOException {
    setUp(context);
    context.hasher().setHash("e1", 1);
    context.hasher().setHash("e2", 1);

    store.initialize();
    writeEntry(store, "e1", "Jynx", "Psyduck");

    // e2 doesn't remove e1 since the entry knows its key due to being written
    assertFalse(store.remove("e2"));
  }

  @ParameterizedTest
  @StoreConfig(store = DISK, autoInit = false)
  void hashCollisionOnCompletedEdit(Store store, StoreContext context) throws IOException {
    setUp(context);
    context.hasher().setHash("e1", 1);
    context.hasher().setHash("e2", 1);

    store.initialize();
    writeEntry(store, "e1", "Jynx", "Psyduck");
    writeEntry(store, "e2", "Eevee", "Mewtwo");

    // e2 replaces e1 as they collide
    assertNull(store.view("e1"));
    assertEntryEquals(store, "e2", "Eevee", "Mewtwo");

    // e1 doesn't remove e2 as it knows its key due to being edited
    assertFalse(store.remove("e1"));
  }

  private static void assertInoperable(Store store) {
    assertThrows(IllegalStateException.class, () -> store.view("e1"));
    assertThrows(IllegalStateException.class, () -> store.edit("e1"));
    assertThrows(IllegalStateException.class, () -> store.remove("e1"));
    assertThrows(IllegalStateException.class, store::clear);
  }
}
