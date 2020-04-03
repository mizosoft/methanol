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

package com.github.mizosoft.methanol.adapter.gson;

import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.BodyAdapter.Decoder;
import com.github.mizosoft.methanol.BodyAdapter.Encoder;
import com.google.gson.Gson;

/** Provides {@link BodyAdapter} implementations for the JSON format using Gson. */
public class GsonAdapterFactory {

  private GsonAdapterFactory() {} // non-instantiable

  /** Returns a {@code Encoder} that uses a default {@code Gson} instance. */
  public static Encoder createEncoder() {
    return createEncoder(new Gson());
  }

  /** Returns a {@code Encoder} that uses the given {@code Gson} instance. */
  public static Encoder createEncoder(Gson gson) {
    return new GsonAdapter.Encoder(gson);
  }

  /** Returns a {@code Decoder} that uses a default {@code Gson} instance. */
  public static Decoder createDecoder() {
    return createDecoder(new Gson());
  }

  /** Returns a {@code Decoder} that uses the given {@code Gson} instance. */
  public static Decoder createDecoder(Gson gson) {
    return new GsonAdapter.Decoder(gson);
  }
}
