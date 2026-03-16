/*
 * Copyright (c) 2026 Konrad Windszus
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

package com.github.mizosoft.methanol.zstd.internal;

import com.github.mizosoft.methanol.BodyDecoder;
import com.github.mizosoft.methanol.decoder.AsyncBodyDecoder;
import java.net.http.HttpResponse.BodySubscriber;
import java.util.concurrent.Executor;

/** {@code BodyDecoder.Factory} provider for zstd encoding. */
public final class ZstdBodyDecoderFactory implements BodyDecoder.Factory {
  static final String ZSTD_ENCODING = "zstd";

  /**
   * Creates a new {@code ZstdBodyDecoderFactory}. Meant to be called by the {@code ServiceLoader}
   * class.
   */
  public ZstdBodyDecoderFactory() {
    // zstd-jni is pure Java with no native library loading
  }

  @Override
  public String encoding() {
    return ZSTD_ENCODING;
  }

  @Override
  public <T> BodyDecoder<T> create(BodySubscriber<T> downstream) {
    return new AsyncBodyDecoder<>(new ZstdDecoder(), downstream);
  }

  @Override
  public <T> BodyDecoder<T> create(BodySubscriber<T> downstream, Executor executor) {
    return new AsyncBodyDecoder<>(new ZstdDecoder(), downstream, executor);
  }
}
