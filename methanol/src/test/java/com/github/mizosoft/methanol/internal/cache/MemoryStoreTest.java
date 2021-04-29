/*
 * Copyright (c) 2019, 2020 Moataz Abdelnasser
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
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MemoryStoreTest extends StoreTest {
  @Override
  Store newStore(long maxSize) {
    return new MemoryStore(maxSize);
  }

  @Test
  void hasNoExecutor() {
    assertEquals(Optional.empty(), store.executor());
  }
  
  @Test
  void writeWithReadOnlyBuffer() throws IOException {
    try (var editor = notNull(store.edit("e1"))) {
      editor.metadata(UTF_8.encode(METADATA_1).asReadOnlyBuffer());
      editor.writeAsync(0, UTF_8.encode(DATA_1).asReadOnlyBuffer());
      editor.commit();
    }
    assertEntryContains("e1", METADATA_1, DATA_1);
  }
}
