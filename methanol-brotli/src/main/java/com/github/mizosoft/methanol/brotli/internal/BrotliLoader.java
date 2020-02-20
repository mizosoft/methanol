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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Helper class for loading brotli JNI bindings. */
class BrotliLoader {

  private static final String LINUX = "linux";
  private static final String WINDOWS = "windows";
  private static final String MAC_OS = "macos";
  private static final String UNKNOWN = "unknown";

  // Maps arch path in jar to os.arch aliases
  private static final Map<String, Set<String>> ARCH_PATHS =
      Map.of(
          "x86", Set.of("x86", "i386", "i486", "i586", "i686"),
          "x86-64" /* '-' and not '_' is used in arch path */, Set.of("x86_64", "amd64"));

  private static final String LIB_NAME = "brotlidecjni";
  private static final Path LIB_ROOT = Path.of("/native");
  private static final String TEMP_FILE_PREFIX = BrotliLoader.class.getCanonicalName();
  private static final Object LOAD_LOCK = new Object();

  private static volatile boolean loaded;

  static void ensureLoaded() throws IOException {
    if (!loaded) {
      synchronized (LOAD_LOCK) {
        if (!loaded) {
          loadBrotli();
          loaded = true;
        }
      }
    }
  }

  static void loadBrotli() throws IOException {
    Path libPathInJar = getLibPathInJar();
    // getResourceAsStream requires '/' separators which is not Path::toString's case on windows
    String normalizedPath = libPathInJar.toString().replace('\\', '/');
    InputStream libIn = BrotliLoader.class.getResourceAsStream(normalizedPath);
    if (libIn == null) {
      throw new IOException("couldn't find jar-bundled native library: " + libPathInJar);
    }
    try (libIn) {
      Path tempLibPath =
          Files.createTempFile(TEMP_FILE_PREFIX, libPathInJar.getFileName().toString());
      // Delete temp file on exit
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      Files.deleteIfExists(tempLibPath);
                    } catch (IOException ignored) {
                      // TODO: might wanna log
                    }
                  }));
      try (OutputStream out = Files.newOutputStream(tempLibPath)) {
        libIn.transferTo(out);
      }
      System.load(tempLibPath.toString());
    }
  }

  // UNKNOWN is used as a fake enum
  @SuppressWarnings({"StringEquality", "ReferenceEquality"})
  private static Path getLibPathInJar() throws IOException {
    String os = System.getProperty("os.name");
    String normalizedOs = normalizeOs(os.toLowerCase(Locale.ROOT));
    if (normalizedOs == UNKNOWN) {
      throw new IOException("unrecognized OS: " + os);
    }
    String arch = System.getProperty("os.arch");
    String normalizedArch = normalizeArch(arch.toLowerCase(Locale.ROOT));
    if (normalizedArch == UNKNOWN) {
      throw new IOException("unrecognized architecture: " + arch);
    }
    return LIB_ROOT
        .resolve(normalizedOs)
        .resolve(normalizedArch)
        .resolve(System.mapLibraryName(LIB_NAME));
  }

  private static String normalizeOs(String os) {
    if (os.contains("linux")) {
      return LINUX;
    } else if (os.contains("windows")) {
      return WINDOWS;
    } else if (os.contains("mac os x") || os.contains("darwin") || os.contains("osx")) {
      return MAC_OS;
    } else {
      return UNKNOWN;
    }
  }

  private static String normalizeArch(String arch) {
    return ARCH_PATHS.entrySet().stream()
        .filter(e -> e.getValue().contains(arch))
        .findFirst()
        .map(Map.Entry::getKey)
        .orElse(UNKNOWN);
  }
}
