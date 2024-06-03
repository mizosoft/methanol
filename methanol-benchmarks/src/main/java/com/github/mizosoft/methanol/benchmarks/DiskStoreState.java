/*
 * Copyright (c) 2024 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.benchmarks;

import com.github.mizosoft.methanol.internal.cache.Store;
import com.github.mizosoft.methanol.testing.store.DiskStoreConfig;
import com.github.mizosoft.methanol.testing.store.StoreConfig.Execution;
import com.github.mizosoft.methanol.testing.store.StoreConfig.FileSystemType;
import com.github.mizosoft.methanol.testing.store.StoreContext;
import java.nio.ByteBuffer;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@State(Scope.Benchmark)
public class DiskStoreState {
  static final int METADATA_SIZE = 256;
  static final int DATA_SIZE = 4 * 1024;
  static final int ENTRY_SIZE = METADATA_SIZE + DATA_SIZE;

  StoreContext context;
  Store store;

  void setupStore(long maxSize) throws Exception {
    context =
        StoreContext.of(
            new DiskStoreConfig(
                maxSize, 1, FileSystemType.SYSTEM, Execution.ASYNC, 1, false, false, false));
    store = context.createAndRegisterStore();
  }

  @TearDown
  public void tearDown() throws Exception {
    context.close();
  }

  ByteBuffer read(String key, ReadDst dst) throws Exception {
    try (var viewer = store.view(key).orElseThrow()) {
      int read;
      if ((read = viewer.newReader().read(dst.buffer.clear())) != DATA_SIZE) {
        throw new AssertionError(read);
      }
      return dst.buffer.flip();
    }
  }

  void write(String key, WriteSrc src) throws Exception {
    try (var editor = store.edit(key).orElseThrow()) {
      int written;
      if ((written = editor.writer().write(src.data())) != DATA_SIZE) {
        throw new AssertionError(written);
      }
      editor.commit(src.metadata());
    }
  }

  @State(Scope.Thread)
  public static class ReadDst {
    final ByteBuffer buffer = ByteBuffer.allocate(DATA_SIZE);
  }

  @State(Scope.Benchmark)
  public static class WriteSrc {
    private final ByteBuffer metadata = ByteBuffer.allocate(METADATA_SIZE);
    private final ByteBuffer data = ByteBuffer.allocate(DATA_SIZE);

    @Setup
    public void setUp() {
      var content = "abc";

      int i = 0;
      while (metadata.hasRemaining()) {
        metadata.put((byte) content.charAt(i++ % content.length()));
      }
      metadata.flip();

      while (data.hasRemaining()) {
        data.put((byte) content.charAt(i++ % content.length()));
      }
      data.flip();
    }

    ByteBuffer data() {
      return data.duplicate();
    }

    ByteBuffer metadata() {
      return metadata.duplicate();
    }
  }
}
