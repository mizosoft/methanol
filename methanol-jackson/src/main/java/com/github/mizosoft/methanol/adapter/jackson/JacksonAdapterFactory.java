/*
 * Copyright (c) 2024 Moataz Abdelnasser
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

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.BodyAdapter.Decoder;
import com.github.mizosoft.methanol.BodyAdapter.Encoder;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.adapter.jackson.JacksonAdapter.BinaryFormatDecoder;
import com.github.mizosoft.methanol.adapter.jackson.JacksonAdapter.BinaryFormatEncoder;
import com.github.mizosoft.methanol.adapter.jackson.JacksonAdapter.TextFormatDecoder;
import com.github.mizosoft.methanol.adapter.jackson.JacksonAdapter.TextFormatEncoder;
import com.google.errorprone.annotations.InlineMe;
import java.io.IOException;
import java.io.Writer;

/**
 * Provides {@link BodyAdapter} implementations for JSON using the <a
 * href="https://github.com/FasterXML/jackson">Jackson library</a>.
 */
public class JacksonAdapterFactory {
  private JacksonAdapterFactory() {} // Non-instantiable.

  /**
   * Returns an {@code Encoder} that uses a default {@code ObjectMapper} for JSON and is only
   * compatible with {@code application/json}.
   *
   * @deprecated Use {@link #createJsonEncoder()}.
   */
  @Deprecated
  @InlineMe(
      replacement = "JacksonAdapterFactory.createJsonEncoder()",
      imports = "com.github.mizosoft.methanol.adapter.jackson.JacksonAdapterFactory")
  public static Encoder createEncoder() {
    return createJsonEncoder();
  }

  /**
   * Returns an {@code Encoder} that uses the given {@code ObjectMapper} and is only compatible with
   * {@code application/json}.
   *
   * @deprecated Use {@link #createJsonEncoder(ObjectMapper)}.
   */
  @Deprecated
  @InlineMe(
      replacement = "JacksonAdapterFactory.createJsonEncoder(mapper)",
      imports = "com.github.mizosoft.methanol.adapter.jackson.JacksonAdapterFactory")
  public static Encoder createEncoder(ObjectMapper mapper) {
    return createJsonEncoder(mapper);
  }

  /**
   * Returns an {@link Encoder} that uses a default {@code ObjectMapper} for JSON and is only
   * compatible with {@code application/json}.
   */
  public static Encoder createJsonEncoder() {
    return createJsonEncoder(new JsonMapper());
  }

  /**
   * Returns an {@link Encoder} that uses the given {@code ObjectMapper} and is only compatible with
   * {@code application/json}.
   */
  public static Encoder createJsonEncoder(ObjectMapper mapper) {
    return createEncoder(mapper, MediaType.APPLICATION_JSON);
  }

  /**
   * Returns an {@link Encoder} that uses the given {@code ObjectMapper} and is compatible with the
   * given media types.
   */
  public static Encoder createEncoder(
      ObjectMapper mapper, MediaType firstMediaType, MediaType... otherMediaTypes) {
    return createEncoder(mapper, ObjectWriterFactory.getDefault(), firstMediaType, otherMediaTypes);
  }

  /**
   * Returns an {@link Encoder} that uses the given {@code ObjectMapper} and is compatible with the
   * given media types. The encoder creates {@code ObjectWriters} using the given factory.
   */
  public static Encoder createEncoder(
      ObjectMapper mapper,
      ObjectWriterFactory writerFactory,
      MediaType firstMediaType,
      MediaType... otherMediaTypes) {
    return isBinaryFormat(mapper)
        ? new BinaryFormatEncoder(
            mapper, writerFactory, toMediaTypeArray(firstMediaType, otherMediaTypes))
        : new TextFormatEncoder(
            mapper, writerFactory, toMediaTypeArray(firstMediaType, otherMediaTypes));
  }

  /**
   * Returns a {@link Decoder} that uses a default {@code ObjectMapper} for JSON and is only
   * compatible with {@code application/json}.
   *
   * @deprecated Use {@link #createJsonDecoder()}.
   */
  @Deprecated
  @InlineMe(
      replacement = "JacksonAdapterFactory.createJsonDecoder()",
      imports = "com.github.mizosoft.methanol.adapter.jackson.JacksonAdapterFactory")
  public static Decoder createDecoder() {
    return createJsonDecoder();
  }

  /**
   * Returns a {@link Decoder} that uses the given {@code ObjectMapper} and is only compatible with
   * {@code application/json}.
   *
   * @deprecated Use {@link #createJsonDecoder(ObjectMapper)}.
   */
  @Deprecated
  @InlineMe(
      replacement = "JacksonAdapterFactory.createJsonDecoder(mapper)",
      imports = "com.github.mizosoft.methanol.adapter.jackson.JacksonAdapterFactory")
  public static Decoder createDecoder(ObjectMapper mapper) {
    return createJsonDecoder(mapper);
  }

  /**
   * Returns a {@link Decoder} that uses a default {@code ObjectMapper} for JSON and is only
   * compatible with {@code application/json}.
   */
  public static Decoder createJsonDecoder() {
    return createJsonDecoder(new JsonMapper());
  }

  /**
   * Returns a {@link Decoder} that uses the given {@code ObjectMapper} and is only compatible with
   * {@code application/json}.
   */
  public static Decoder createJsonDecoder(ObjectMapper mapper) {
    return createDecoder(mapper, MediaType.APPLICATION_JSON);
  }

  /**
   * Returns a {@link Decoder} that uses the given {@code ObjectMapper} and is compatible with the
   * given media types.
   */
  public static Decoder createDecoder(
      ObjectMapper mapper, MediaType firstMediaType, MediaType... otherMediaTypes) {
    return createDecoder(mapper, ObjectReaderFactory.getDefault(), firstMediaType, otherMediaTypes);
  }

  /**
   * Returns a {@link Decoder} that uses the given {@code ObjectMapper} and is compatible with the
   * given media types. The decoder creates {@code ObjectReaders} using the given factory.
   */
  public static Decoder createDecoder(
      ObjectMapper mapper,
      ObjectReaderFactory readerFactory,
      MediaType firstMediaType,
      MediaType... otherMediaTypes) {
    return isBinaryFormat(mapper)
        ? new BinaryFormatDecoder(
            mapper, readerFactory, toMediaTypeArray(firstMediaType, otherMediaTypes))
        : new TextFormatDecoder(
            mapper, readerFactory, toMediaTypeArray(firstMediaType, otherMediaTypes));
  }

  private static MediaType[] toMediaTypeArray(
      MediaType firstMediaType, MediaType... otherMediaTypes) {
    requireNonNull(firstMediaType);
    requireNonNull(otherMediaTypes);
    var mediaTypeArray = new MediaType[1 + otherMediaTypes.length];
    mediaTypeArray[0] = firstMediaType;
    System.arraycopy(otherMediaTypes, 0, mediaTypeArray, 1, otherMediaTypes.length);
    return mediaTypeArray;
  }

  private static boolean isBinaryFormat(ObjectMapper mapper) {
    var factory = mapper.getFactory();
    try {
      factory.createGenerator(Writer.nullWriter());
    } catch (UnsupportedOperationException e) {
      return true;
    } catch (IOException ignored) {
      // Assume text format.
    }
    return false;
  }
}
