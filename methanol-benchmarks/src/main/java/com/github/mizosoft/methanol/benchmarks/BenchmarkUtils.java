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

package com.github.mizosoft.methanol.benchmarks;

import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.TypeReference;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterInputStream;
import java.util.zip.GZIPOutputStream;

public class BenchmarkUtils {

  public static final int N_CPU = Runtime.getRuntime().availableProcessors();

  public static final MediaType APPLICATION_JSON = MediaType.of("application", "json");

  public static final TypeReference<List<Map<String, Object>>> ARRAY_OF_OBJECTS =
      new TypeReference<>() {};

  public static byte[] gzip(byte[] data) {
    var outBuff = new ByteArrayOutputStream();
    try (var gzOut = new GZIPOutputStream(outBuff)) {
      gzOut.write(data);
    } catch (IOException ioe) {
      throw new AssertionError(ioe);
    }
    return outBuff.toByteArray();
  }

  public static byte[] deflate(byte[] data) {
    try {
      return new DeflaterInputStream(new ByteArrayInputStream(data)).readAllBytes();
    } catch (IOException ioe) {
      throw new UncheckedIOException(ioe);
    }
  }
}
