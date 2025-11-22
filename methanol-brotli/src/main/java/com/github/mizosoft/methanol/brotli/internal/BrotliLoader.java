/*
 * Copyright (c) 2025 Moataz Hussein
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

import com.github.mizosoft.methanol.brotli.internal.vendor.CommonJNI;
import com.github.mizosoft.methanol.internal.concurrent.Lazy;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

import static java.util.Objects.requireNonNull;

/** Helper class for loading brotli JNI and setting the bundled dictionary. */
final class BrotliLoader {
  private static final long VERSION = 1;

  private static final Logger logger = System.getLogger(BrotliLoader.class.getName());

  private static final String LINUX = "linux";
  private static final String WINDOWS = "windows";
  private static final String MAC_OS = "macos";

  static final String WORKSPACE_DIR_NAME = BrotliLoader.class.getName() + "@" + VERSION;
  static final String BASE_LIB_NAME = "brotlijni";
  static final String ENTRY_DIR_PREFIX = "entry-";
  static final String LOCK_FILE_NAME = ".lock";

  private static final String LIBRARY_PATH_PROPERTY =
      "com.github.mizosoft.methanol.brotli.libraryPath";

  private static final String LIB_ROOT = "native";
  private static final String DEFAULT_DICTIONARY_PATH = "/data/dictionary.bin";

  public static final int BROTLI_DICT_SIZE = 122784;
  private static final byte[] BROTLI_DICT_SHA_256 =
      new byte[] {
        32, -28, 46, -79, -75, 17, -62, 24, 6, -44, -46, 39, -48, 126, 93, -48,
        104, 119, -40, -50, 123, 58, -127, 127, 55, -113, 49, 54, 83, -13, 92, 112
      };

  private static final Lazy<Boolean> load =
      Lazy.of(
          () -> {
            try {
              new BrotliLoader(
                      Path.of(
                          System.getProperty(
                              "com.github.mizosoft.methanol.brotli.tmpdir",
                              System.getProperty("java.io.tmpdir"))))
                  .load();
              return true;
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          });

  private final Path workspaceDir;
  private final String dictionaryPath;
  private final LibLoader libLoader;

  BrotliLoader(Path tempDir) {
    this(tempDir, DEFAULT_DICTIONARY_PATH);
  }

  BrotliLoader(Path tempDir, String dictionaryPath) {
    this(tempDir, dictionaryPath, LibLoader.SystemLibLoader.INSTANCE);
  }

  BrotliLoader(Path tempDir, String dictionaryPath, LibLoader libLoader) {
    this.workspaceDir = tempDir.resolve(WORKSPACE_DIR_NAME);
    this.dictionaryPath = dictionaryPath;
    this.libLoader = libLoader;
  }

  void load() throws IOException {
    var dictionary = loadBrotliDictionary();
    loadLibrary();
    if (!CommonJNI.nativeSetDictionaryData(dictionary)) {
      throw new IOException("Failed to set brotli dictionary");
    }
  }

  void loadLibrary() throws IOException {
    // Try custom directory path.
    var libraryPath = System.getProperty(LIBRARY_PATH_PROPERTY);
    if (libraryPath != null) {
      try {
        var libFile = Path.of(libraryPath).resolve(System.mapLibraryName(BASE_LIB_NAME));
        libLoader.load(libFile.toAbsolutePath().toString());
        logger.log(Level.INFO, "Loaded %s library from custom path <%s>", BASE_LIB_NAME, libFile);
        return;
      } catch (UnsatisfiedLinkError ignored) {
      }
    }

    // Try java.library.path.
    try {
      libLoader.loadLibrary(BASE_LIB_NAME);
      logger.log(Level.INFO, "Loaded %s library from java.library.path", BASE_LIB_NAME);
      return;
    } catch (UnsatisfiedLinkError ignored) {
    }

    // Try JAR-bundled libraries.
    var entry = extractLibrary();
    entry.deleteOnExit();
    entry.loadLibrary();
    logger.log(Level.INFO, "Loaded %s library from JAR resources", BASE_LIB_NAME);
  }

  ByteBuffer loadBrotliDictionary() throws IOException {
    // The buffer must be direct as the native address is directly passed to
    // BrotliSetDictionaryData from the JNI side.
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
        if (dictData.remaining() < read) {
          throw new IOException("Too large dictionary");
        }

        dictData.put(tempBuffer, 0, read);
        dictDigest.update(tempBuffer, 0, read);
      }

      if (dictData.hasRemaining()) {
        throw new EOFException("Incomplete dictionary");
      }
      if (!Arrays.equals(dictDigest.digest(), BROTLI_DICT_SHA_256)) {
        throw new IOException("Corrupt dictionary");
      }
    }
    return dictData;
  }

  LibEntry extractLibrary() throws IOException {
    // Ensure workspaceDir exists.
    Files.createDirectories(workspaceDir);

    var libName = System.mapLibraryName(BASE_LIB_NAME);
    var libPath = getLibraryPath(libName);
    var libUrl = BrotliLoader.class.getResource(libPath);
    if (libUrl == null) {
      throw new FileNotFoundException(
          String.format("Couldn't find %s library in <%s>", BASE_LIB_NAME, libPath));
    }
    cleanStaleEntries(libName);
    return createLibEntry(libName, libUrl);
  }

  private void cleanStaleEntries(String libName) {
    try (var entryDirs =
        Files.list(workspaceDir)
            .filter(p -> p.getFileName().toString().startsWith(ENTRY_DIR_PREFIX))) {
      entryDirs.forEach(
          dir -> {
            var entry = new LibEntry(dir, libName, libLoader);
            if (entry.isStale()) {
              try {
                entry.delete();
              } catch (IOException ioe) {
                logger.log(Level.WARNING, "Couldn't delete stale entry: " + dir, ioe);
              }
            }
          });
    } catch (IOException ioe) {
      logger.log(Level.WARNING, "Couldn't perform cleanup routine for stale entries", ioe);
    }
  }

  private LibEntry createLibEntry(String libName, URL libUrl) throws IOException {
    var entry =
        new LibEntry(Files.createTempDirectory(workspaceDir, ENTRY_DIR_PREFIX), libName, libLoader);
    try (var libIn = libUrl.openStream()) {
      entry.create(libIn);
    } catch (IOException ioe) {
      try {
        entry.delete();
      } catch (IOException suppressed) {
        ioe.addSuppressed(suppressed);
      }
      throw ioe;
    }
    return entry;
  }

  private static String getLibraryPath(String libName) {
    var normalizedOs = normalizeOs(System.getProperty("os.name").toLowerCase(Locale.ROOT));
    var normalizedArch = normalizeArch(System.getProperty("os.arch").toLowerCase(Locale.ROOT));
    return String.format("/%s/%s-%s/%s", LIB_ROOT, normalizedOs, normalizedArch, libName);
  }

  private static String normalizeOs(String os) {
    if (os.contains("linux")) {
      return LINUX;
    } else if (os.contains("windows")) {
      return WINDOWS;
    } else if (os.contains("macos")
        || os.contains("mac os x")
        || os.contains("darwin")
        || os.contains("osx")) {
      return MAC_OS;
    }
    throw new UnsupportedOperationException("Unrecognized OS: " + os);
  }

  private static String normalizeArch(String arch) {
    if (arch.contains("x86-64") || arch.contains("x86_64") || arch.contains("amd64")) {
      return "x86-64";
    } else if (arch.contains("aarch64") || arch.contains("arm64")) {
      return "aarch64";
    }
    throw new UnsupportedOperationException("Unrecognized architecture: " + arch);
  }

  /** Ensures that the native brotli library is loaded. */
  static void ensureLoaded() throws IOException {
    try {
      load.get();
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }

  /** Represents a temp entry for the extracted library and its lock file. */
  private static final class LibEntry {
    private final Path dir;
    private final Path lockFile;
    private final Path libFile;
    private final LibLoader libLoader;

    LibEntry(Path dir, String libName, LibLoader libLoader) {
      this.dir = dir;
      this.lockFile = dir.resolve(LOCK_FILE_NAME);
      this.libFile = dir.resolve(libName);
      this.libLoader = requireNonNull(libLoader);
    }

    void create(InputStream libIn) throws IOException {
      // lockFile must be created first to not erroneously report staleness to other instances in
      // between libFile creation and lockFile creation.
      Files.createFile(lockFile);
      Files.copy(libIn, libFile);
    }

    void delete() throws IOException {
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
                      delete();
                    } catch (IOException ignored) {
                      // An AccessDeniedException will ALWAYS be thrown in Windows.
                    }
                  }));
    }

    boolean isStale() {
      // Staleness is only reported on a "complete" entry (has the lib file and possibly the lock
      // file). This is because entry creation is not atomic, so care must be taken to not delete an
      // entry that is still under creation (still empty)
      return Files.notExists(lockFile) && Files.exists(libFile);
    }

    void loadLibrary() {
      libLoader.load(libFile.toAbsolutePath().toString());
    }
  }
}
