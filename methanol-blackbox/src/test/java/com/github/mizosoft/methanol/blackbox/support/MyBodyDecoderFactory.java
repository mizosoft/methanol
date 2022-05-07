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

package com.github.mizosoft.methanol.blackbox.support;

import com.github.mizosoft.methanol.BodyDecoder;
import com.github.mizosoft.methanol.decoder.AsyncBodyDecoder;
import com.github.mizosoft.methanol.decoder.AsyncDecoder;
import java.io.IOException;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.util.concurrent.Executor;

/** For asserting that user providers replace the defaults for same encoding. */
public abstract class MyBodyDecoderFactory implements BodyDecoder.Factory {
  MyBodyDecoderFactory() {}

  abstract MyDecoder newDecoder();

  @Override
  public <T> BodyDecoder<T> create(BodySubscriber<T> downstream) {
    return new AsyncBodyDecoder<>(newDecoder(), downstream);
  }

  @Override
  public <T> BodyDecoder<T> create(BodySubscriber<T> downstream, Executor executor) {
    return new AsyncBodyDecoder<>(newDecoder(), downstream, executor);
  }

  static AsyncDecoder getDefaultAsyncDecoder(String encoding) {
    return BodyDecoder.Factory.installedFactories().stream()
        .filter(f -> !(f instanceof MyBodyDecoderFactory) && encoding.equals(f.encoding()))
        .map(f -> ((AsyncBodyDecoder<?>) f.create(BodySubscribers.discarding())).asyncDecoder())
        .findFirst()
        .orElseThrow(() -> new AssertionError("cannot find default decoder for: " + encoding));
  }

  private static final class MyDecoder implements AsyncDecoder {
    private final AsyncDecoder delegate;

    MyDecoder(AsyncDecoder delegate) {
      this.delegate = delegate;
    }

    @Override
    public String encoding() {
      return delegate.encoding();
    }

    @Override
    public void decode(ByteSource source, ByteSink sink) throws IOException {
      delegate.decode(source, sink);
    }

    @Override
    public void close() {
      delegate.close();
    }
  }

  public static final class MyDeflateBodyDecoderFactory extends MyBodyDecoderFactory {
    public MyDeflateBodyDecoderFactory() {}

    @Override
    MyDecoder newDecoder() {
      return new MyDecoder(getDefaultAsyncDecoder("deflate"));
    }

    @Override
    public String encoding() {
      return "deflate";
    }
  }

  public static final class MyGzipBodyDecoderFactory extends MyBodyDecoderFactory {
    public MyGzipBodyDecoderFactory() {}

    @Override
    MyDecoder newDecoder() {
      return new MyDecoder(getDefaultAsyncDecoder("gzip"));
    }

    @Override
    public String encoding() {
      return "gzip";
    }
  }
}
