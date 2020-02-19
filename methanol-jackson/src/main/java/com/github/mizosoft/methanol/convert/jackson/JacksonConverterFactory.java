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

package com.github.mizosoft.methanol.convert.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.mizosoft.methanol.Converter;
import com.github.mizosoft.methanol.convert.jackson.JacksonConverter.OfRequest;
import com.github.mizosoft.methanol.convert.jackson.JacksonConverter.OfResponse;

/** Provides {@link Converter} implementations for the JSON format using Jackson. */
public class JacksonConverterFactory {

  private JacksonConverterFactory() {} // non-instantiable

  /** Returns a {@code Converter.OfRequest} that uses a default {@code ObjectMapper} instance. */
  public static Converter.OfRequest createOfRequest() {
    return createOfRequest(new JsonMapper());
  }

  /**
   * Returns a {@code Converter.OfRequest} that uses the given {@code ObjectMapper} instance.
   *
   * @param mapper the {@code ObjectMapper}
   */
  public static Converter.OfRequest createOfRequest(ObjectMapper mapper) {
    return new OfRequest(mapper);
  }

  /** Returns a {@code Converter.OfResponse} that uses a default {@code ObjectMapper} instance. */
  public static Converter.OfResponse createOfResponse() {
    return createOfResponse(new JsonMapper());
  }

  /**
   * Returns a {@code Converter.OfResponse} that uses the given {@code ObjectMapper} instance.
   *
   * @param mapper the {@code ObjectMapper}
   */
  public static Converter.OfResponse createOfResponse(ObjectMapper mapper) {
    return new OfResponse(mapper);
  }
}
