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

package com.github.mizosoft.methanol.convert.protobuf;

import com.github.mizosoft.methanol.Converter;
import com.google.protobuf.ExtensionRegistryLite;

/**
 * Providers {@link Converter} for google's <a
 * href="https://developers.google.com/protocol-buffers">protocol buffers</a> format. The converters
 * support any subtype of {@link com.google.protobuf.MessageLite}.
 */
public class ProtobufConverterFactory {

  private ProtobufConverterFactory() {} // non-instantiable

  /** Returns a new {@code Converter.OfRequest}. */
  public static Converter.OfRequest createOfRequest() {
    return new ProtobufConverter.OfRequest();
  }

  /** Returns a new {@code Converter.OfResponse} with an empty extension registry. */
  public static Converter.OfResponse createOfResponse() {
    return createOfResponse(ExtensionRegistryLite.getEmptyRegistry());
  }

  /** Returns a new {@code Converter.OfResponse} that uses the given extension registry. */
  public static Converter.OfResponse createOfResponse(ExtensionRegistryLite registry) {
    return new ProtobufConverter.OfResponse(registry);
  }
}
