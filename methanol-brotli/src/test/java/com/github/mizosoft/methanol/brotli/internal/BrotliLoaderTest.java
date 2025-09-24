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

package com.github.mizosoft.methanol.brotli.internal;

import static com.github.mizosoft.methanol.brotli.internal.BrotliLoader.BASE_LIB_NAME;
import static com.github.mizosoft.methanol.brotli.internal.BrotliLoader.ENTRY_DIR_PREFIX;
import static com.github.mizosoft.methanol.testing.TestUtils.listFiles;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(architectures = "aarch64")
class BrotliLoaderTest {

  @Test
  void entryCreation(@TempDir Path tempDir) throws IOException {
    new BrotliLoader(tempDir).extractLibrary();

    var libName = System.mapLibraryName(BASE_LIB_NAME);
    var createdEntries = listFiles(tempDir);
    assertEquals(1, createdEntries.size(), createdEntries.toString());

    var entry = createdEntries.get(0);
    var createdFiles = listFiles(entry);
    assertEquals(2, createdFiles.size(), createdFiles.toString());

    var expected = Set.of(entry.resolve(libName), entry.resolve(libName + ".lock"));
    assertEquals(expected, Set.copyOf(createdFiles));
  }

  @Test
  void cleanupRoutine(@TempDir Path tempDir) throws IOException {
    var libName = System.mapLibraryName(BASE_LIB_NAME);
    var staleEntry = Files.createDirectory(tempDir.resolve(ENTRY_DIR_PREFIX + "stale"));
    var activeEntry = Files.createDirectories(tempDir.resolve(ENTRY_DIR_PREFIX + "active"));
    Files.createFile(staleEntry.resolve(libName)); // Stale entry has only the lib file.
    Files.createFile(activeEntry.resolve(libName)); // Active entry has both lib and lock files.
    Files.createFile(activeEntry.resolve(libName + ".lock"));

    var entryUnderCreation =
        Files.createDirectory(tempDir.resolve(ENTRY_DIR_PREFIX + "underCreation"));

    new BrotliLoader(tempDir).extractLibrary();

    var entries = listFiles(tempDir);
    assertEquals(3, entries.size(), entries.toString()); // (active, under creation, new)
    assertFalse(entries.contains(staleEntry), entries.toString());
    assertTrue(entries.containsAll(Set.of(activeEntry, entryUnderCreation)), entries.toString());
  }

  @Test
  void corruptDictionary(@TempDir Path tempDir) {
    var loader = new BrotliLoader(tempDir, "/data/corrupt_dictionary.bin");
    var ioe = assertThrows(IOException.class, loader::loadBrotliDictionary);
    assertEquals(ioe.getMessage(), "dictionary is corrupt");
  }

  @Test
  void wrongDictionarySize(@TempDir Path tempDir) {
    var loader = new BrotliLoader(tempDir, "/data/truncated_dictionary.bin");
    assertThrows(EOFException.class, loader::loadBrotliDictionary);

    var loader2 = new BrotliLoader(tempDir, "/data/elongated_dictionary.bin");
    var ioe = assertThrows(IOException.class, loader2::loadBrotliDictionary);
    assertEquals(ioe.getMessage(), "too large dictionary");
  }
}
