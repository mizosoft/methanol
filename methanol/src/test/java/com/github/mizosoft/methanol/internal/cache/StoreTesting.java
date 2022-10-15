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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.testing.junit.DiskStoreContext;
import com.github.mizosoft.methanol.testing.junit.StoreContext;
import com.github.mizosoft.methanol.testing.junit.StoreSpec.StoreType;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.stream.Stream;

class StoreTesting {
  private StoreTesting() {}

  static void assertUnreadable(Store store, String key) throws IOException {
    try (var viewer = store.view(key)) {
      assertThat(viewer)
          .withFailMessage("expected entry <%s> to be unreadable", key)
          .isNull();
    }
  }

  static void assertAbsent(Store store, StoreContext context, String... keys) throws IOException {
    // Make sure the store knows nothing about the entries
    for (var key : keys) {
      assertUnreadable(store, key);
      if (context.config().storeType() == StoreType.DISK) {
        var mockStore = new MockDiskStore((DiskStoreContext) context);
        assertThat(mockStore.entryFile(key))
            .withFailMessage("unexpected entry file for: %s", key)
            .doesNotExist();
      }
    }
  }

  static long sizeOf(String... values) {
    return Stream.of(values)
        .map(UTF_8::encode)
        .mapToLong(ByteBuffer::remaining)
        .sum();
  }

  static Viewer view(Store store, String key) throws IOException {
    var viewer = store.view(key);
    assertThat(viewer)
        .withFailMessage("expected entry <%s> to be readable", key)
        .isNotNull();
    return viewer;
  }

  static Editor edit(Store store, String key) throws IOException {
    var editor = store.edit(key);
    assertThat(editor)
        .withFailMessage("expected entry <%s> to be editable", key)
        .isNotNull();
    return editor;
  }

  static Editor edit(Viewer viewer) throws IOException {
    var editor = viewer.edit();
    assertThat(editor)
        .withFailMessage("expected entry <%s> to be editable from given viewer", viewer.key())
        .isNotNull();
    return editor;
  }

  static void assertEntryEquals(Store store, String key, String metadata, String data)
      throws IOException {
    try (var viewer = view(store, key)) {
      assertEntryEquals(viewer, metadata, data);
    }
  }

  static void assertEntryEquals(Viewer viewer, String metadata, String data) throws IOException {
    assertThat(UTF_8.decode(viewer.metadata()).toString()).isEqualTo(metadata);
    assertThat(readData(viewer)).isEqualTo(data);
    assertThat(viewer.dataSize()).isEqualTo(sizeOf(data));
    assertThat(viewer.entrySize()).isEqualTo(sizeOf(metadata, data));
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
