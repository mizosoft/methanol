/*
 * Copyright (c) 2022 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.store.redis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

enum Script {
  COMMIT("commit.lua"),
  VIEW("view.lua"),
  COMMIT2("commit2.lua"),
  VIEW2("view2.lua");

  private static final String SCRIPTS_PATH = "/scripts/";

  private final String filename;
  private byte @MonotonicNonNull [] lazyBytes;
  private @MonotonicNonNull String lazySha1;

  Script(String filename) {
    this.filename = filename;
  }

  byte[] encodedBytes() {
    var bytes = lazyBytes;
    if (bytes == null) {
      bytes = loadScript();
      lazyBytes = bytes;
    }
    return bytes;
  }

  private byte[] loadScript() {
    try (var in = getClass().getResourceAsStream(SCRIPTS_PATH + filename)) {
      if (in == null) {
        throw new NoSuchFileException(filename);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  String sha1() {
    var sha1 = lazySha1;
    if (sha1 == null) {
      sha1 = toHexString(newSha1Digest().digest(encodedBytes()));
      lazySha1 = sha1;
    }
    return sha1;
  }

  private static MessageDigest newSha1Digest() {
    try {
      return MessageDigest.getInstance("SHA1");
    } catch (NoSuchAlgorithmException e) {
      throw new UnsupportedOperationException("SHA1 not available!", e);
    }
  }

  private static String toHexString(byte[] bytes) {
    var sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      char upperHex = Character.forDigit((b >> 4) & 0xf, 16);
      char lowerHex = Character.forDigit(b & 0xf, 16);
      sb.append(upperHex).append(lowerHex);
    }
    return sb.toString();
  }
}
