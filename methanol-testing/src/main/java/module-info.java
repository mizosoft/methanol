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

/** Miscellaneous test utilities used internally. */
module methanol.testing {
  requires transitive methanol;
  requires okhttp3.tls;
  requires java.logging;
  requires org.assertj.core;
  requires org.junit.jupiter.api;
  requires org.junit.jupiter.params;
  requires mockwebserver3;
  requires lettuce.core;
  requires methanol.redis;
  requires static org.checkerframework.checker.qual;
  requires static com.google.errorprone.annotations;

  exports com.github.mizosoft.methanol.testing;
  exports com.github.mizosoft.methanol.testing.decoder;
  exports com.github.mizosoft.methanol.testing.file;
  exports com.github.mizosoft.methanol.testing.verifiers;
  exports com.github.mizosoft.methanol.testing.junit;

  uses com.github.mizosoft.methanol.testing.MemoryFileSystemProvider;
}
