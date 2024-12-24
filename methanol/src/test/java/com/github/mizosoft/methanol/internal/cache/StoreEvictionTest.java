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

import static com.github.mizosoft.methanol.testing.store.StoreTesting.assertEntryEquals;
import static com.github.mizosoft.methanol.testing.store.StoreTesting.assertUnreadable;
import static com.github.mizosoft.methanol.testing.store.StoreTesting.edit;
import static com.github.mizosoft.methanol.testing.store.StoreTesting.setMetadata;
import static com.github.mizosoft.methanol.testing.store.StoreTesting.view;
import static com.github.mizosoft.methanol.testing.store.StoreTesting.write;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.testing.Logging;
import com.github.mizosoft.methanol.testing.store.StoreConfig.Execution;
import com.github.mizosoft.methanol.testing.store.StoreConfig.StoreType;
import com.github.mizosoft.methanol.testing.store.StoreExtension.StoreParameterizedTest;
import com.github.mizosoft.methanol.testing.store.StoreSpec;
import java.io.IOException;

class StoreEvictionTest {
  static {
    Logging.disable(DiskStore.class);
  }

  @StoreParameterizedTest
  @StoreSpec(
      skipped = {StoreType.REDIS_STANDALONE, StoreType.REDIS_CLUSTER},
      maxSize = 10,
      execution = Execution.SAME_THREAD)
  void writeExactlyMaxSizeBytesByOneEntry(Store store) throws IOException {
    write(store, "e1", "12345", "abcde"); // Grow size to 10 bytes.
    assertEntryEquals(store, "e1", "12345", "abcde");
    assertThat(store.size()).isEqualTo(10);
  }

  @StoreParameterizedTest
  @StoreSpec(
      skipped = {StoreType.REDIS_STANDALONE, StoreType.REDIS_CLUSTER},
      maxSize = 10,
      execution = Execution.SAME_THREAD)
  void writeExactlyMaxSizeBytesByTwoEntries(Store store) throws IOException {
    write(store, "e1", "12", "abc"); // Grow size to 5 bytes.
    write(store, "e2", "45", "def"); // Grow size to 10 bytes.
    assertEntryEquals(store, "e1", "12", "abc");
    assertEntryEquals(store, "e2", "45", "def");
    assertThat(store.size()).isEqualTo(10);
  }

  @StoreParameterizedTest
  @StoreSpec(
      skipped = {StoreType.REDIS_STANDALONE, StoreType.REDIS_CLUSTER},
      maxSize = 15,
      execution = Execution.SAME_THREAD)
  void writeBeyondMaxSize(Store store) throws IOException {
    write(store, "e1", "12", "abc"); // Grow size to 5 bytes.
    write(store, "e2", "34", "def"); // Grow size to 10 bytes.
    assertThat(store.size()).isEqualTo(10);

    // LRU queue: e2, e1.
    view(store, "e1").close();

    // Grow size to 16 bytes, causing e2 to be evicted.
    write(store, "e3", "567", "ghi");

    // LRU queue: e1, e3.
    assertUnreadable(store, "e2");
    assertEntryEquals(store, "e1", "12", "abc");
    assertEntryEquals(store, "e3", "567", "ghi");
    assertThat(store.size()).isEqualTo(11);

    // Grows size to 11 + 14 bytes causing both e1 & e3 to be evicted.
    write(store, "e4", "Jynx", "Charmander");
    assertUnreadable(store, "e1");
    assertUnreadable(store, "e3");
    assertEntryEquals(store, "e4", "Jynx", "Charmander");
    assertThat(store.size()).isEqualTo(14);
  }

  @StoreParameterizedTest
  @StoreSpec(
      skipped = {StoreType.REDIS_STANDALONE, StoreType.REDIS_CLUSTER},
      maxSize = 15,
      execution = Execution.SAME_THREAD)
  void discardedWriteBeyondMaxSize(Store store) throws IOException {
    write(store, "e1", "123", "abc"); // Grow size to 6 bytes.
    write(store, "e2", "456", "def"); // Grow size to 12 bytes.
    assertThat(store.size()).isEqualTo(12);

    try (var editor = edit(store, "e3")) {
      write(editor, "abcd");
    }
    assertUnreadable(store, "e3");
    assertEntryEquals(store, "e1", "123", "abc");
    assertEntryEquals(store, "e2", "456", "def");
    assertThat(store.size()).isEqualTo(12);
  }

  @StoreParameterizedTest
  @StoreSpec(
      skipped = {StoreType.REDIS_STANDALONE, StoreType.REDIS_CLUSTER},
      maxSize = 4,
      execution = Execution.SAME_THREAD)
  void writeBeyondMaxSizeByMetadataExpansion(Store store) throws IOException {
    write(store, "e1", "1", "a"); // Grow size to 2 bytes.
    write(store, "e2", "2", "b"); // Grow size to 4 bytes.
    assertThat(store.size()).isEqualTo(4);

    // Increase metadata by 1 byte, causing size to grow to 5 bytes & e2 to be evicted.
    setMetadata(store, "e1", "12");
    assertUnreadable(store, "e2");
    assertEntryEquals(store, "e1", "12", "a");
    assertThat(store.size()).isEqualTo(3);
  }

  @StoreParameterizedTest
  @StoreSpec(
      skipped = {StoreType.REDIS_STANDALONE, StoreType.REDIS_CLUSTER},
      maxSize = 4,
      execution = Execution.SAME_THREAD)
  void writeBeyondMaxSizeByDataExpansion(Store store) throws IOException {
    write(store, "e1", "1", "a"); // Grow size to 2 bytes.
    write(store, "e2", "2", "b"); // Grow size to 4 bytes.
    assertThat(store.size()).isEqualTo(4);

    // Increase data by 1 byte, causing size to grow to 5 bytes & e2 to be evicted.
    write(store, "e1", "1", "ab");
    assertUnreadable(store, "e2");
    assertEntryEquals(store, "e1", "1", "ab");
    assertThat(store.size()).isEqualTo(3);
  }

  @StoreParameterizedTest
  @StoreSpec(
      skipped = {StoreType.REDIS_STANDALONE, StoreType.REDIS_CLUSTER},
      maxSize = 18,
      execution = Execution.SAME_THREAD)
  void lruEviction(Store store) throws IOException {
    // Grow size to 6 bytes.
    // LRU queue: e1.
    write(store, "e1", "aaa", "bbb");
    assertThat(store.size()).isEqualTo(6);

    // Grow size to 12 bytes.
    // LRU queue: e1, e2.
    write(store, "e2", "ccc", "ddd");
    assertThat(store.size()).isEqualTo(12);

    // LRU queue: e2, e1.
    view(store, "e1").close();

    // Grow size to 18 bytes.
    // LRU queue: e2, e1, e3.
    write(store, "e3", "eee", "fff");
    assertThat(store.size()).isEqualTo(18);

    // LRU queue: e2, e3, e1.
    view(store, "e1").close();

    // Grow size to 24 bytes, causing e2 to be evicted to get down to 18.
    // LRU queue: e3, e1, e4.
    write(store, "e4", "ggg", "hhh");
    assertUnreadable(store, "e2");
    assertThat(store.size()).isEqualTo(18);

    // LRU queue: e1, e4, e3.
    view(store, "e3").close();

    // Grow size to 24 bytes, causing e1 to be evicted to get down to 18 bytes.
    // LRU queue: e4, e3, e5
    write(store, "e5", "iii", "jjj");
    assertUnreadable(store, "e1");
    assertThat(store.size()).isEqualTo(18);

    // Grow size to 18 + 12 bytes, causing e4 & e3 to be evicted to get down to 18 bytes.
    // LRU queue: e5, e6.
    write(store, "e6", "kkk", "lmnopqrst");
    assertUnreadable(store, "e4", "e3");
    assertThat(store.size()).isEqualTo(18);

    // LRU queue: e6, e5.
    view(store, "e5").close();

    // Grow size to 24 bytes, causing e6 to be evicted to get down to 12.
    // LRU queue: e5, e7.
    write(store, "e7", "uuu", "vvv");
    assertUnreadable(store, "e6");
    assertThat(store.size()).isEqualTo(12);

    // Grow size to 18 bytes, causing nothing to be evicted since size is within bounds.
    // LRU queue: e5, e7, e8.
    write(store, "e8", "xxx", "~!@");
    assertThat(store.size()).isEqualTo(18);

    // Write one 18 bytes entry, causing all other entries to be evicted.
    write(store, "e9", "Ricardo", "all is mine");
    assertUnreadable(store, "e5, e7", "e8");
    assertThat(store.size()).isEqualTo(18);
  }
}
