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

package com.github.mizosoft.methanol.adapter.protobuf;

import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.BodyAdapter.Decoder;
import com.github.mizosoft.methanol.BodyAdapter.Encoder;
import com.google.protobuf.ExtensionRegistryLite;

/**
 * Providers {@link BodyAdapter} for Google's <a
 * href="https://developers.google.com/protocol-buffers">Protocol Buffers</a> format. The adapters
 * support any subtype of {@link com.google.protobuf.MessageLite}.
 */
public class ProtobufAdapterFactory {
  private ProtobufAdapterFactory() {} // Non-instantiable.

  /** Returns a new {@link Encoder}. */
  public static Encoder createEncoder() {
    return new ProtobufAdapter.Encoder();
  }

  /** Returns a new {@link Decoder} with an empty extension registry. */
  public static Decoder createDecoder() {
    return createDecoder(ExtensionRegistryLite.getEmptyRegistry());
  }

  /** Returns a new {@link Decoder} that uses the given extension registry. */
  public static Decoder createDecoder(ExtensionRegistryLite registry) {
    return new ProtobufAdapter.Decoder(registry);
  }
}
