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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.cache.Store.EntryReader;
import com.github.mizosoft.methanol.internal.cache.Store.EntryWriter;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.testing.store.DiskStoreContext;
import com.github.mizosoft.methanol.testing.store.StoreConfig.StoreType;
import com.github.mizosoft.methanol.testing.store.StoreContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.file.NoSuchFileException;
import java.util.stream.Stream;

class StoreTesting {
  private StoreTesting() {}

  static void assertUnreadable(Store store, String key) throws IOException, InterruptedException {
    try (var viewer = store.view(key).orElse(null)) {
      assertThat(viewer).withFailMessage("expected entry <%s> to be unreadable", key).isNull();
    }
  }

  static void assertAbsent(Store store, StoreContext context, String... keys)
      throws IOException, InterruptedException {
    // Make sure the store knows nothing about the entries.
    for (var key : keys) {
      assertUnreadable(store, key);
      if (context.config().storeType() == StoreType.DISK) {
        var mockStore = new MockDiskStore((DiskStoreContext) context);
        try {
          // Check if this is a different logical entry in case of collisions.
          var entry = mockStore.readEntry(key);
          assertThat(entry.key)
              .withFailMessage("unexpected entry file for <%s>", key)
              .isNotEqualTo(key);
        } catch (NoSuchFileException ignored) {
          assertThat(mockStore.entryFile(key))
              .withFailMessage("unexpected entry file for <%s>", key)
              .doesNotExist();
        }
      }
    }
  }

  static long sizeOf(String... values) {
    return Stream.of(values).map(UTF_8::encode).mapToLong(ByteBuffer::remaining).sum();
  }

  static Viewer view(Store store, String key) throws IOException, InterruptedException {
    var viewer = store.view(key);
    assertThat(viewer).withFailMessage("expected entry <%s> to be readable", key).isNotEmpty();
    return viewer.orElseThrow();
  }

  static Editor edit(Store store, String key) throws IOException, InterruptedException {
    var editor = store.edit(key);
    assertThat(editor).withFailMessage("expected entry <%s> to be editable", key).isNotEmpty();
    return editor.orElseThrow();
  }

  static Editor edit(Viewer viewer) {
    try {
      var editor = viewer.edit();
      assertThat(editor)
          .withFailMessage("expected entry <%s> to be editable through given viewer", viewer.key())
          .isNotEmpty();
      return editor.orElseThrow();
    } catch (IOException | InterruptedException e) {
      return fail("unexpected exception", e);
    }
  }

  static void assertEntryEquals(Store store, String key, String metadata, String data)
      throws IOException, InterruptedException {
    try (var viewer = view(store, key)) {
      assertEntryEquals(viewer, metadata, data);
    }
  }

  static void assertEntryEquals(Viewer viewer, String metadata, String data)
      throws IOException, InterruptedException {
    assertThat(UTF_8.decode(viewer.metadata()).toString()).isEqualTo(metadata);
    assertThat(read(viewer)).isEqualTo(data);
    assertThat(viewer.dataSize()).isEqualTo(sizeOf(data));
    assertThat(viewer.entrySize()).isEqualTo(sizeOf(metadata, data));
  }

  static void write(Store store, String key, String metadata, String data)
      throws IOException, InterruptedException {
    try (var editor = edit(store, key)) {
      write(editor, data);
      commit(editor, metadata);
    }
  }

  static void write(Editor editor, String data) throws IOException, InterruptedException {
    write(editor.writer(), data);
  }

  static void setMetadata(Store store, String key, String metadata)
      throws IOException, InterruptedException {
    try (var editor = edit(store, key)) {
      commit(editor, metadata);
    }
  }

  static void commit(Editor editor, String metadata, String data)
      throws IOException, InterruptedException {
    write(editor, data);
    commit(editor, metadata);
  }

  static void commit(Editor editor, String metadata) {
    try {
      editor.commit(UTF_8.encode(metadata));
    } catch (IOException e) {
      fail("unexpected exception", e);
    }
  }

  static void write(EntryWriter writer, String data) throws IOException, InterruptedException {
    writer.write(UTF_8.encode(data));
  }

  static String read(Viewer viewer) throws IOException, InterruptedException {
    return read(viewer.newReader());
  }

  static String read(EntryReader reader) throws IOException, InterruptedException {
    var out = new ByteArrayOutputStream();
    var outChannel = Channels.newChannel(out);
    var buffer = ByteBuffer.allocate(1024);
    while (reader.read(buffer.clear()) != -1) {
      outChannel.write(buffer.flip());
    }
    return out.toString(UTF_8);
  }
}
