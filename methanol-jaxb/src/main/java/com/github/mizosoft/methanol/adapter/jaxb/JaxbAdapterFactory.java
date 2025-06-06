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

package com.github.mizosoft.methanol.adapter.jaxb;

import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.BodyAdapter.Decoder;
import com.github.mizosoft.methanol.BodyAdapter.Encoder;

/**
 * Contains static factory methods for <a href="https://javaee.github.io/jaxb-v2/">Java EE JAXB</a>
 * {@link BodyAdapter adapters}.
 */
public class JaxbAdapterFactory {
  private JaxbAdapterFactory() {}

  /**
   * Creates an encoder using the {@link JaxbBindingFactory#create() default JaxbBindingFactory}.
   */
  public static Encoder createEncoder() {
    return createEncoder(JaxbBindingFactory.create());
  }

  /** Creates an encoder using the given {@code JaxbBindingFactory}. */
  public static Encoder createEncoder(JaxbBindingFactory jaxbFactory) {
    return new JaxbAdapter.Encoder(jaxbFactory);
  }

  /** Creates a decoder using the {@link JaxbBindingFactory#create() default JaxbBindingFactory}. */
  public static Decoder createDecoder() {
    return createDecoder(JaxbBindingFactory.create());
  }

  /** Creates a decoder using the given {@code JaxbBindingFactory}. */
  public static Decoder createDecoder(JaxbBindingFactory jaxbFactory) {
    return new JaxbAdapter.Decoder(jaxbFactory);
  }
}
