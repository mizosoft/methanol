/*
 * Copyright (c)  Moataz Abdelnasser
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

/**
 * Core Methanol module.
 *
 * @uses com.github.mizosoft.methanol.BodyDecoder.Factory
 * @uses com.github.mizosoft.methanol.BodyAdapter.Encoder
 * @uses com.github.mizosoft.methanol.BodyAdapter.Decoder
 * @provides com.github.mizosoft.methanol.BodyDecoder.Factory For the gzip and deflate encodings.
 */
module methanol {
  requires transitive java.net.http;
  requires static org.checkerframework.checker.qual;
  requires static com.google.errorprone.annotations;

  exports com.github.mizosoft.methanol;
  exports com.github.mizosoft.methanol.decoder;
  exports com.github.mizosoft.methanol.adapter;
  exports com.github.mizosoft.methanol.function;
  exports com.github.mizosoft.methanol.internal.flow to
      methanol.adapter.jackson,
      methanol.adapter.jackson.flux,
      methanol.redis,
      methanol.testing;
  exports com.github.mizosoft.methanol.internal.extensions to
      methanol.adapter.jackson,
      methanol.adapter.jackson.flux;
  exports com.github.mizosoft.methanol.internal.concurrent to
      methanol.adapter.jackson,
      methanol.redis,
      methanol.testing;
  exports com.github.mizosoft.methanol.internal.cache to
      methanol.redis,
      methanol.testing;
  exports com.github.mizosoft.methanol.internal.function to
      methanol.testing;
  exports com.github.mizosoft.methanol.internal to
      methanol.redis,
      methanol.testing,
      methanol.adapter.jaxb.jakarta,
      methanol.adapter.jaxb;

  uses com.github.mizosoft.methanol.BodyDecoder.Factory;
  uses com.github.mizosoft.methanol.BodyAdapter.Encoder;
  uses com.github.mizosoft.methanol.BodyAdapter.Decoder;

  provides com.github.mizosoft.methanol.BodyDecoder.Factory with
      com.github.mizosoft.methanol.internal.decoder.GzipBodyDecoderFactory,
      com.github.mizosoft.methanol.internal.decoder.DeflateBodyDecoderFactory;
}
