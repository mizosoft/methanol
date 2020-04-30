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

package com.github.mizosoft.methanol.adapter.jaxb;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;

final class CachingJaxbBindingFactory implements JaxbBindingFactory {

  private final ConcurrentMap<Class<?>, JAXBContext> cachedContexts;

  CachingJaxbBindingFactory() {
    cachedContexts = new ConcurrentHashMap<>();
  }

  @Override
  public Marshaller createMarshaller(Class<?> boundClass) throws JAXBException {
    return getOrCreateContext(boundClass).createMarshaller();
  }

  @Override
  public Unmarshaller createUnmarshaller(Class<?> boundClass) throws JAXBException {
    return getOrCreateContext(boundClass).createUnmarshaller();
  }

  // not private for testing
  JAXBContext getOrCreateContext(Class<?> boundClass) {
    return cachedContexts.computeIfAbsent(
        boundClass,
        c -> {
          try {
            return JAXBContext.newInstance(c);
          } catch (JAXBException e) {
            throw new UncheckedJaxbException(e);
          }
        });
  }
}
