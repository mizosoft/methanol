/*
 * MIT License
 *
 * Copyright (c) 2019 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.convert.gson;

import com.github.mizosoft.methanol.Converter;
import com.github.mizosoft.methanol.convert.gson.GsonConverter.OfRequest;
import com.github.mizosoft.methanol.convert.gson.GsonConverter.OfResponse;
import com.google.gson.Gson;

/**
 * Provides {@link Converter} implementations for the JSON format using Gson.
 */
public class GsonConverterFactory {

  /**
   * Returns a {@code Converter.OfRequest} that uses a default {@code Gson} instance.
   */
  public static Converter.OfRequest createOfRequest() {
    return createOfRequest(new Gson());
  }

  /**
   * Returns a {@code Converter.OfRequest} that uses the given {@code Gson} instance.
   */
  public static Converter.OfRequest createOfRequest(Gson gson) {
    return new OfRequest(gson);
  }

  /**
   * Returns a {@code Converter.OfResponse} that uses a default {@code Gson} instance.
   */
  public static Converter.OfResponse createOfResponse() {
    return createOfResponse(new Gson());
  }

  /**
   * Returns a {@code Converter.OfResponse} that uses the given {@code Gson} instance.
   */
  public static Converter.OfResponse createOfResponse(Gson gson) {
    return new OfResponse(gson);
  }
}
