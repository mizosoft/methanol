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

import static com.github.mizosoft.methanol.testing.extensions.StoreProvider.StoreConfig.StoreType.DISK;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.testing.extensions.StoreProvider.StoreContext;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.stream.Stream;

class StoreTesting {
  private StoreTesting() {}

  static void assertUnreadable(Store store, String key) throws IOException {
    assertNull(store.view(key), "expected entry <" + key + "> to be unreadable");
  }

  static void assertAbsent(Store store, StoreContext context, String... keys) throws IOException {
    // Make sure the store knows nothing about the entries
    for (var key : keys) {
      assertUnreadable(store, key);
      if (context.config().storeType() == DISK) {
        var mockStore = new MockDiskStore(context);
        assertFalse(mockStore.entryFileExists(key), "unexpected entry file for: " + key);
      }
    }
  }

  static long sizeOf(String... values) {
    return Stream.of(values)
        .map(UTF_8::encode)
        .mapToInt(ByteBuffer::remaining)
        .summaryStatistics()
        .getSum();
  }

  static Viewer view(Store store, String key) throws IOException {
    var viewer = store.view(key);
    assertNotNull(viewer, "expected a viewer to be opened for " + key);
    return viewer;
  }

  static Editor edit(Store store, String key) throws IOException {
    var editor = store.edit(key);
    assertNotNull(editor, "expected an editor to be opened for " + key);
    return editor;
  }

  static Editor edit(Viewer viewer) throws IOException {
    var editor = viewer.edit();
    assertNotNull(editor, "expected an editor to be opened from viewer for: " + viewer.key());
    return editor;
  }

  static void assertEntryEquals(Store store, String key, String metadata, String data)
      throws IOException {
    try (var viewer = view(store, key)) {
      assertEntryEquals(viewer, metadata, data);
    }
  }

  static void assertEntryEquals(Viewer viewer, String metadata, String data) throws IOException {
    assertEquals(metadata, UTF_8.decode(viewer.metadata()).toString());
    assertEquals(data, readData(viewer));
    assertEquals(sizeOf(data), viewer.dataSize());
    assertEquals(sizeOf(metadata, data), viewer.entrySize());
  }

  static void writeEntry(Store store, String key, String metadata, String data) throws IOException {
    try (var editor = edit(store, key)) {
      writeEntry(editor, metadata, data);
      editor.commitOnClose();
    }
  }

  static void writeEntry(Editor editor, String metadata, String data) throws IOException {
    setMetadata(editor, metadata);
    writeData(editor, data);
  }

  static void setMetadata(Editor editor, String metadata) {
    editor.metadata(UTF_8.encode(metadata));
  }

  static void setMetadata(Store store, String key, String metadata) throws IOException {
    try (var editor = edit(store, key)) {
      setMetadata(editor, metadata);
      editor.commitOnClose();
    }
  }

  static void writeData(Editor editor, String data) throws IOException {
    Utils.blockOnIO(editor.writeAsync(0, UTF_8.encode(data)));
  }

  static String readData(Viewer viewer) throws IOException {
    var buffer = ByteBuffer.allocate(Math.toIntExact(viewer.dataSize()));
    Utils.blockOnIO(viewer.readAsync(0, buffer));
    return UTF_8.decode(buffer.flip()).toString();
  }
}
