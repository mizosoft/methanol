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

import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;

/**
 * Creates new {@link Marshaller} or {@link Unmarshaller} objects on demand for use by an adapter.
 */
public interface JaxbBindingFactory {
  /** Returns a new {@code Marshaller} for encoding an object of the given class. */
  Marshaller createMarshaller(Class<?> boundClass) throws JAXBException;

  /** Returns a new {@code Unmarshaller} for decoding to an object of the given class. */
  Unmarshaller createUnmarshaller(Class<?> boundClass) throws JAXBException;

  /**
   * Returns a new {@code JaxbBindingFactory} that creates and caches {@code JAXBContexts} for each
   * requested type.
   */
  static JaxbBindingFactory create() {
    return new CachingJaxbBindingFactory();
  }
}
