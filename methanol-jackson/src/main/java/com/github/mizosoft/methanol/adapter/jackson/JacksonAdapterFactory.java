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

package com.github.mizosoft.methanol.adapter.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.BodyAdapter.Decoder;
import com.github.mizosoft.methanol.BodyAdapter.Encoder;
import com.github.mizosoft.methanol.MediaType;

/** Provides {@link BodyAdapter} implementations for the JSON format using Jackson. */
public class JacksonAdapterFactory {
  private JacksonAdapterFactory() {} // non-instantiable

  /** Returns an {@code Encoder} that uses a default {@code ObjectMapper} for JSON. */
  public static Encoder createEncoder() {
    return createEncoder(new JsonMapper());
  }

  /**
   * Returns an {@code Encoder} that uses the given {@code ObjectMapper} and is only compatible with
   * {@code application/json}.
   */
  public static Encoder createEncoder(ObjectMapper mapper) {
    return createEncoder(mapper, MediaType.APPLICATION_JSON);
  }

  /**
   * Returns an {@code Encoder} that uses the given {@code ObjectMapper} and is compatible with the
   * given media types.
   */
  public static Encoder createEncoder(ObjectMapper mapper, MediaType... mediaTypes) {
    checkMediaTypes(mediaTypes);
    return createEncoder(mapper, ObjectWriterFactory.getDefault(), mediaTypes);
  }

  /**
   * Returns an {@code Encoder} that uses the given {@code ObjectMapper} and is compatible with the
   * given media types. The encoder creates {@code ObjectWriters} using the given factory.
   */
  public static Encoder createEncoder(
      ObjectMapper mapper, ObjectWriterFactory writerFactory, MediaType... mediaTypes) {
    checkMediaTypes(mediaTypes);
    return new JacksonAdapter.Encoder(mapper, writerFactory, mediaTypes);
  }

  /** Returns a {@code Decoder} that uses a default {@code ObjectMapper} for JSON. */
  public static Decoder createDecoder() {
    return createDecoder(new JsonMapper());
  }

  /**
   * Returns a {@code Decoder} that uses the given {@code ObjectMapper} and is only compatible with
   * {@code application/json}.
   */
  public static Decoder createDecoder(ObjectMapper mapper) {
    return createDecoder(mapper, MediaType.APPLICATION_JSON);
  }

  /**
   * Returns a {@code Decoder} that uses the given {@code ObjectMapper} and is compatible with the
   * given media types.
   */
  public static Decoder createDecoder(ObjectMapper mapper, MediaType... mediaTypes) {
    checkMediaTypes(mediaTypes);
    return createDecoder(mapper, ObjectReaderFactory.getDefault(), mediaTypes);
  }

  /**
   * Returns a {@code Decoder} that uses the given {@code ObjectMapper} and is compatible with the
   * given media types. The decoder creates {@code ObjectReaders} using the given factory.
   */
  public static Decoder createDecoder(
      ObjectMapper mapper, ObjectReaderFactory readerFactory, MediaType... mediaTypes) {
    checkMediaTypes(mediaTypes);
    return new JacksonAdapter.Decoder(mapper, readerFactory, mediaTypes);
  }

  private static void checkMediaTypes(MediaType[] mediaTypes) {
    if (mediaTypes.length == 0) {
      throw new IllegalArgumentException("at least one media type has to be specified");
    }
  }
}
