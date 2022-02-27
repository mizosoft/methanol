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

import com.github.mizosoft.methanol.BodyDecoder;
import com.github.mizosoft.methanol.decoder.AsyncBodyDecoder;
import java.io.IOException;
import java.net.http.HttpResponse.BodySubscriber;
import java.util.concurrent.Executor;

/** {@code BodyDecoder.Factory} provider for brotli encoding. */
public final class BrotliBodyDecoderFactory implements BodyDecoder.Factory {
  static final String BROTLI_ENCODING = "br";

  /**
   * Creates a new {@code BrotliBodyDecoderFactory}. Meant to be called by the {@code ServiceLoader}
   * class.
   *
   * @throws IOException if the native brotli library cannot be loaded
   * @throws UnsupportedOperationException if the current machine is not recognized
   */
  public BrotliBodyDecoderFactory() throws IOException {
    BrotliLoader.instance().ensureLoaded();
  }

  @Override
  public String encoding() {
    return BROTLI_ENCODING;
  }

  @Override
  public <T> BodyDecoder<T> create(BodySubscriber<T> downstream) {
    return new AsyncBodyDecoder<>(new BrotliDecoder(), downstream);
  }

  @Override
  public <T> BodyDecoder<T> create(BodySubscriber<T> downstream, Executor executor) {
    return new AsyncBodyDecoder<>(new BrotliDecoder(), downstream, executor);
  }
}
