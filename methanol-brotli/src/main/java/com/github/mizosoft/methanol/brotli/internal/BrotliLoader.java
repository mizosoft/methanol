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

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URL;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Helper class for loading brotli JNI and setting bundled dictionary. */
final class BrotliLoader {
  private static final Logger logger = System.getLogger(BrotliLoader.class.getName());

  private static final String LINUX = "linux";
  private static final String WINDOWS = "windows";
  private static final String MAC_OS = "macos";

  // Maps arch path in jar to os.arch aliases
  private static final Map<String, Set<String>> ARCH_PATHS =
      Map.of(
          "x86", Set.of("x86", "i386", "i486", "i586", "i686"),
          "x86-64" /* '-' and not '_' is used in arch path */, Set.of("x86_64", "amd64"));

  static final String BASE_LIB_NAME = "brotlijni";
  static final String ENTRY_DIR_PREFIX = BrotliLoader.class.getName() + "-";
  private static final String LIB_ROOT = "native";
  private static final String DEFAULT_DICTIONARY_PATH = "/data/dictionary.bin";

  public static final int BROTLI_DICT_SIZE = 122784;
  private static final byte[] BROTLI_DICT_SHA_256 =
      new byte[] {
        32, -28, 46, -79, -75, 17, -62, 24, 6, -44, -46, 39, -48, 126, 93, -48,
        104, 119, -40, -50, 123, 58, -127, 127, 55, -113, 49, 54, 83, -13, 92, 112
      };

  private static final BrotliLoader INSTANCE =
      new BrotliLoader(
          Path.of(
              System.getProperty(
                  "com.github.mizosoft.methanol.brotli.tmpdir",
                  System.getProperty("java.io.tmpdir"))));

  private final Path tempDir;
  private final String dictionaryPath;
  private volatile boolean loaded;

  BrotliLoader(Path tempDir) {
    this(tempDir, DEFAULT_DICTIONARY_PATH);
  }

  BrotliLoader(Path tempDir, String dictionaryPath) {
    this.tempDir = tempDir;
    this.dictionaryPath = dictionaryPath;
  }

  void ensureLoaded() throws IOException {
    if (!loaded) {
      synchronized (this) {
        if (!loaded) {
          var dictionary = loadBrotliDictionary();
          var entry = extractLibrary();
          entry.deleteOnExit();
          entry.loadLibrary();
          if (!CommonJNI.nativeSetDictionaryData(dictionary)) {
            throw new IOException("failed to set brotli dictionary");
          }
          loaded = true;
        }
      }
    }
  }

  ByteBuffer loadBrotliDictionary() throws IOException {
    // The buffer must be direct as the native address is directory passed to
    // BrotliSetDictionaryData from the JNI side
    var dictData = ByteBuffer.allocateDirect(BROTLI_DICT_SIZE);
    try (var dictIn = BrotliLoader.class.getResourceAsStream(dictionaryPath)) {
      if (dictIn == null) {
        throw new FileNotFoundException("Couldn't find brotli dictionary: " + dictionaryPath);
      }

      MessageDigest dictDigest;
      try {
        dictDigest = MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException e) {
        throw new IOException("Couldn't digest brotli dictionary", e);
      }

      int read;
      byte[] tempBuffer = new byte[8 * 1024];
      while ((read = dictIn.read(tempBuffer)) != -1) {
        dictData.put(tempBuffer, 0, read);
        dictDigest.update(tempBuffer, 0, read);
      }

      if (dictData.hasRemaining()) {
        throw new EOFException("Incomplete dictionary");
      }
      if (!Arrays.equals(dictDigest.digest(), BROTLI_DICT_SHA_256)) {
        throw new IOException("Corrupt dictionary");
      }
    } catch (BufferOverflowException e) {
      throw new IOException("Too large dictionary");
    }
    return dictData;
  }

  LibEntry extractLibrary() throws IOException {
    var libName = System.mapLibraryName(BASE_LIB_NAME);
    var libPath = findLibraryPath(libName);
    var libUrl = BrotliLoader.class.getResource(libPath);
    if (libUrl == null) {
      throw new FileNotFoundException("Couldn't find brotli jni library: " + libPath);
    }
    cleanStaleEntries(libName);
    return createLibEntry(libName, libUrl);
  }

  private void cleanStaleEntries(String libName) {
    try (var entryDirs =
        Files.list(tempDir).filter(p -> p.getFileName().toString().startsWith(ENTRY_DIR_PREFIX))) {
      entryDirs.forEach(
          dir -> {
            LibEntry entry = new LibEntry(dir, libName);
            if (entry.isStale()) {
              try {
                entry.deleteIfExists();
              } catch (IOException ioe) {
                logger.log(Level.WARNING, "couldn't delete stale entry: " + dir, ioe);
              }
            }
          });
    } catch (IOException ioe) {
      logger.log(Level.WARNING, "couldn't perform cleanup routine for stale entries", ioe);
    }
  }

  private LibEntry createLibEntry(String libName, URL libUrl) throws IOException {
    Path entryDir = Files.createTempDirectory(tempDir, ENTRY_DIR_PREFIX);
    LibEntry entry = new LibEntry(entryDir, libName);
    try (InputStream libIn = libUrl.openStream()) {
      entry.create(libIn);
    } catch (IOException ioe) {
      try {
        entry.deleteIfExists();
      } catch (IOException suppressed) {
        ioe.addSuppressed(suppressed);
      }
      throw ioe;
    }
    return entry;
  }

  private static String findLibraryPath(String libName) {
    String normalizedOs = normalizeOs(System.getProperty("os.name").toLowerCase(Locale.ROOT));
    String normalizedArch = normalizeArch(System.getProperty("os.arch").toLowerCase(Locale.ROOT));
    return String.format("/%s/%s/%s/%s", LIB_ROOT, normalizedOs, normalizedArch, libName);
  }

  private static String normalizeOs(String os) {
    if (os.contains("linux")) {
      return LINUX;
    } else if (os.contains("windows")) {
      return WINDOWS;
    } else if (os.contains("mac os x") || os.contains("darwin") || os.contains("osx")) {
      return MAC_OS;
    }
    throw new UnsupportedOperationException("unrecognized OS: " + os);
  }

  private static String normalizeArch(String arch) {
    return ARCH_PATHS.entrySet().stream()
        .filter(e -> e.getValue().contains(arch))
        .findFirst()
        .map(Map.Entry::getKey)
        .orElseThrow(() -> new UnsupportedOperationException("unrecognized architecture: " + arch));
  }

  /** Retrieves the singleton instance of this loader. */
  static BrotliLoader instance() {
    return INSTANCE;
  }

  /** Represents a temp entry for the extracted library and it's lock file. */
  private static final class LibEntry {

    private final Path dir;
    private final Path lockFile;
    private final Path libFile;

    LibEntry(Path dir, String libName) {
      this.dir = dir;
      lockFile = dir.resolve(libName + ".lock");
      libFile = dir.resolve(libName);
    }

    void create(InputStream libIn) throws IOException {
      // lockFile must be created first to not erroneously report staleness
      // to other instances in between libFile creation and lockFile creation
      Files.createFile(lockFile);
      Files.copy(libIn, libFile);
    }

    void deleteIfExists() throws IOException {
      Files.deleteIfExists(lockFile);
      Files.deleteIfExists(libFile);
      Files.deleteIfExists(dir);
    }

    void deleteOnExit() {
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      deleteIfExists();
                    } catch (IOException ignored) {
                      // An AccessDeniedException will ALWAYS be thrown
                      // in windows so it might be annoying to log it
                    }
                  }));
    }

    boolean isStale() {
      // Staleness is only reported on a "complete" entry (has the lib file and possibly
      // the lock file). This is because entry creation is not atomic, so care must
      // be taken to not delete an entry that is still under creation (still empty)
      return Files.notExists(lockFile) && Files.exists(libFile);
    }

    void loadLibrary() {
      System.load(libFile.toAbsolutePath().toString());
    }
  }
}
