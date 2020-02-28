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

package com.github.mizosoft.methanol.brotli.internal;

import static com.github.mizosoft.methanol.brotli.internal.BrotliLoader.BASE_LIB_NAME;
import static com.github.mizosoft.methanol.brotli.internal.BrotliLoader.ENTRY_DIR_PREFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrotliLoaderTest {

  public static @TempDir Path tempDir;

  @BeforeAll
  static void setupBrotliTempDir() {
    System.setProperty("com.github.mizosoft.methanol.brotli.tmpdir", tempDir.toString());
  }

  @Test
  void entryCreation() throws Exception {
    cleanTempDir();
    BrotliLoader.extractLib();
    try (var dirStream = Files.list(tempDir)) {
      var itr = dirStream.iterator();
      var entry = itr.next();
      assertFalse(itr.hasNext());
      try (var files = Files.list(entry)) {
        var libName = System.mapLibraryName(BASE_LIB_NAME);
        var fileSet = files.collect(Collectors.toUnmodifiableSet());
        var expected = Set.of(entry.resolve(libName), entry.resolve(libName + ".lock"));
        assertEquals(expected, fileSet);
      }
    }
  }

  @Test
  void staleEntryDeletion() throws Exception {
    cleanTempDir();
    var libName = System.mapLibraryName(BASE_LIB_NAME);
    var entryOnUse = Files.createDirectory(tempDir.resolve(ENTRY_DIR_PREFIX + "123"));
    Files.createFile(entryOnUse.resolve(libName));
    Files.createFile(entryOnUse.resolve(libName + ".lock"));
    var staleEntry = Files.createDirectory(tempDir.resolve(ENTRY_DIR_PREFIX + "abc"));
    Files.createFile(staleEntry.resolve(libName));
    var entryUnderCreation = Files.createDirectory(tempDir.resolve(ENTRY_DIR_PREFIX + "@#$"));
    BrotliLoader.extractLib();
    try (var dirStream = Files.list(tempDir)) {
      var entries = dirStream.collect(Collectors.toUnmodifiableSet());
      assertEquals(3, entries.size());
      assertFalse(entries.contains(staleEntry));
      assertTrue(entries.contains(entryOnUse));
      assertTrue(entries.contains(entryUnderCreation));
    }
  }

  private static void cleanTempDir() throws Exception {
    Files.walkFileTree(tempDir, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
        if (exc != null) {
          throw exc;
        }
        if (!dir.equals(tempDir)) {
          Files.delete(dir);
        }
        return FileVisitResult.CONTINUE;
      }
    });
  }
}
