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

package com.github.mizosoft.methanol.blackbox.support;

import com.github.mizosoft.methanol.BodyDecoder;
import com.github.mizosoft.methanol.decoder.AsyncBodyDecoder;
import com.github.mizosoft.methanol.decoder.AsyncDecoder;
import java.io.IOException;
import java.net.http.HttpResponse.BodySubscriber;
import java.util.concurrent.Executor;

public class BadzipBodyDecoderFactory implements BodyDecoder.Factory {
  public BadzipBodyDecoderFactory() {}

  @Override
  public String encoding() {
    return "badzip";
  }

  @Override
  public <T> BodyDecoder<T> create(BodySubscriber<T> downstream) {
    return new AsyncBodyDecoder<>(Badzip.INSTANCE, downstream);
  }

  @Override
  public <T> BodyDecoder<T> create(BodySubscriber<T> downstream, Executor executor) {
    return new AsyncBodyDecoder<>(Badzip.INSTANCE, downstream, executor);
  }

  private enum Badzip implements AsyncDecoder {
    INSTANCE;

    @Override
    public String encoding() {
      return "badzip";
    }

    @Override
    public void decode(ByteSource source, ByteSink sink) throws IOException {
      throw new IOException("Ops! I forgot my encoding :(");
    }

    @Override
    public void close() {}
  }
}
