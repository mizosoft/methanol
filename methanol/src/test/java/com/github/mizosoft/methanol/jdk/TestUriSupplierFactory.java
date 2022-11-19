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

package com.github.mizosoft.methanol.jdk;

import java.net.URI;
import java.util.function.Supplier;

/**
 * Defers obtaining the URI under test till a test method is invoked. This is needed as JUnit 5
 * invokes the argument provider before test's setup method, so URIs never get the chance to get
 * initialized when the provider is invoked.
 */
final class TestUriSupplierFactory {
  private final Object testInstance;

  TestUriSupplierFactory(Object testInstance) {
    this.testInstance = testInstance;
  }

  Supplier<String> uriString(String uriFieldName) {
    return new Supplier<>() {
      @Override
      public String get() {
        try {
          var field = testInstance.getClass().getDeclaredField(uriFieldName);
          field.setAccessible(true);
          return (String) field.get(testInstance);
        } catch (NoSuchFieldException | IllegalAccessException e) {
          throw new RuntimeException(e);
        }
      }

      @Override
      public String toString() {
        return uriFieldName;
      }
    };
  }

  Supplier<URI> uri(String uriFieldName, String path) {
    return new Supplier<>() {
      @Override
      public URI get() {
        try {
          var field = testInstance.getClass().getDeclaredField(uriFieldName);
          field.setAccessible(true);
          return ((URI) field.get(testInstance)).resolve(path);
        } catch (NoSuchFieldException | IllegalAccessException e) {
          throw new RuntimeException(e);
        }
      }

      @Override
      public String toString() {
        return uriFieldName;
      }
    };
  }
}
