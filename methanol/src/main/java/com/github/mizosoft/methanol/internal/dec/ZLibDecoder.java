/*
 * MIT License
 *
 * Copyright (c) 2019 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.internal.dec;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.dec.AsyncDecoder;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

/**
 * Base class for deflate and gzip decoders.
 */
abstract class ZLibDecoder implements AsyncDecoder {

  private final WrapMode wrapMode;
  final Inflater inflater; // package-private for subclass access

  ZLibDecoder(WrapMode wrapMode) {
    this.wrapMode = wrapMode;
    inflater = wrapMode.newInflater();
  }

  @Override
  public String encoding() {
    return wrapMode.encoding;
  }

  @Override
  public void close() {
    inflater.end(); // Inflate::end is thread-safe
  }

  void inflateSource(ByteSource source, ByteSink sink) throws IOException {
    while (source.hasRemaining() && !inflater.finished()) {
      inflateBlock(source.currentSource(), sink.currentSink());
    }
  }

  // Inflate [in, out] block (`in` is swapped only if inflater needs input)
  void inflateBlock(ByteBuffer in, ByteBuffer out) throws IOException {
    if (inflater.needsInput()) {
      inflater.setInput(in);
    } else if (inflater.needsDictionary()) {
      throw new ZipException("Missing preset dictionary");
    }
    try {
      inflater.inflate(out);
    } catch (DataFormatException e) {
      throw new ZipException(requireNonNull(e.getMessage(), "Wrong deflate format"));
    }
  }

  enum WrapMode {
    DEFLATE("deflate", false),
    GZIP("gzip", true); // gzip has it's own wrapping method

    final String encoding;
    final boolean nowrap;

    WrapMode(String encoding, boolean nowrap) {
      this.encoding = encoding;
      this.nowrap = nowrap;
    }

    Inflater newInflater() {
      return new Inflater(nowrap);
    }
  }
}
