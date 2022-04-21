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

package com.github.mizosoft.methanol.adapter.jackson.internal;

import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class JacksonAdapterUtils {
  private JacksonAdapterUtils() {}

  public static byte[] collectBytes(List<ByteBuffer> buffers) {
    int size = buffers.stream().mapToInt(ByteBuffer::remaining).sum();
    byte[] bytes = new byte[size];
    int offset = 0;
    for (var buff : buffers) {
      int remaining = buff.remaining();
      buff.get(bytes, offset, buff.remaining());
      offset += remaining;
    }
    return bytes;
  }

  /** Used when the exception is not handled structurally to avoid unchecked wrapping. */
  @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
  public static <X extends Throwable> X throwUnchecked(Throwable t) throws X {
    throw (X) t; // when Java generics actually work... uuh or don't?... what?
  }

  // The non-blocking parser only works with UTF-8 and ASCII
  // https://github.com/FasterXML/jackson-core/issues/596
  public static <T> BodySubscriber<T> coerceUtf8(
      BodySubscriber<T> baseSubscriber, Charset charset) {
    return charset.equals(StandardCharsets.UTF_8) || charset.equals(StandardCharsets.US_ASCII)
        ? baseSubscriber
        : new CharsetCoercingSubscriber<>(baseSubscriber, charset, StandardCharsets.UTF_8);
  }
}
