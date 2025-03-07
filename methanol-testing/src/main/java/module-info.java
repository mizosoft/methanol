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

/** Miscellaneous test utilities used internally. */
module methanol.testing {
  requires transitive methanol;
  requires transitive org.junit.jupiter.params;
  requires transitive org.assertj.core;
  requires okhttp3.tls;
  requires java.logging;
  requires mockwebserver3;
  requires lettuce.core;
  requires methanol.redis;
  requires static org.checkerframework.checker.qual;
  requires static com.google.errorprone.annotations;

  exports com.github.mizosoft.methanol.testing;
  exports com.github.mizosoft.methanol.testing.decoder;
  exports com.github.mizosoft.methanol.testing.file;
  exports com.github.mizosoft.methanol.testing.verifiers;
  exports com.github.mizosoft.methanol.testing.store;

  uses com.github.mizosoft.methanol.testing.MemoryFileSystemProvider;

  provides java.nio.file.spi.FileTypeDetector with
      com.github.mizosoft.methanol.testing.RegistryFileTypeDetector;
}
