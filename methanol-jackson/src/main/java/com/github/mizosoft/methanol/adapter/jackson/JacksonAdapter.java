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
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

abstract class JacksonAdapter extends AbstractBodyAdapter {
  final ObjectMapper mapper;

  JacksonAdapter(ObjectMapper mapper, MediaType... mediaTypes) {
    super(mediaTypes);
    this.mapper = requireNonNull(mapper);
  }

  private abstract static class AbstractEncoder extends JacksonAdapter implements BaseEncoder {
    final ObjectWriterFactory writerFactory;

    AbstractEncoder(
        ObjectMapper mapper, ObjectWriterFactory writerFactory, MediaType... mediaTypes) {
      super(mapper, mediaTypes);
      this.writerFactory = requireNonNull(writerFactory);
    }

    @Override
    public boolean supportsType(TypeRef<?> typeRef) {
      return mapper.canSerialize(typeRef.rawType());
    }

    @Override
    public <T> BodyPublisher toBody(T value, TypeRef<T> typeRef, Hints hints) {
      requireSupport(typeRef, hints);
      byte[] bytes;
      var objectWriter = writerFactory.createWriter(mapper, typeRef);
      try {
        bytes = getBytes(objectWriter, value, hints.mediaTypeOrAny().charsetOrUtf8());
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return attachMediaType(BodyPublishers.ofByteArray(bytes), hints.mediaTypeOrAny());
    }

    abstract byte[] getBytes(ObjectWriter objectWriter, Object value, Charset charset)
        throws IOException;
  }

  static final class TextFormatEncoder extends AbstractEncoder {
    TextFormatEncoder(
        ObjectMapper mapper, ObjectWriterFactory writerFactory, MediaType... mediaTypes) {
      super(mapper, writerFactory, mediaTypes);
    }

    @Override
    byte[] getBytes(ObjectWriter objectWriter, Object value, Charset charset) throws IOException {
      if (charset.equals(StandardCharsets.UTF_8)) {
        return objectWriter.writeValueAsBytes(value); // Optimized for UTF-8.
      } else {
        return objectWriter.writeValueAsString(value).getBytes(charset);
      }
    }
  }

  static final class BinaryFormatEncoder extends AbstractEncoder {
    BinaryFormatEncoder(
        ObjectMapper mapper, ObjectWriterFactory writerFactory, MediaType... mediaTypes) {
      super(mapper, writerFactory, mediaTypes);
    }

    @Override
    byte[] getBytes(ObjectWriter objectWriter, Object value, Charset ignored) throws IOException {
      return objectWriter.writeValueAsBytes(value);
    }
  }

  private abstract static class AbstractDecoder extends JacksonAdapter implements BaseDecoder {
    final ObjectReaderFactory readerFactory;

    AbstractDecoder(
        ObjectMapper mapper, ObjectReaderFactory readerFactory, MediaType... mediaTypes) {
      super(mapper, mediaTypes);
      this.readerFactory = requireNonNull(readerFactory);
    }

    @Override
    public boolean supportsType(TypeRef<?> typeRef) {
      return mapper.canDeserialize(mapper.constructType(typeRef.type()));
    }

    @Override
    public <T> BodySubscriber<T> toObject(TypeRef<T> typeRef, Hints hints) {
      requireSupport(typeRef, hints);
      var objectReader = readerFactory.createReader(mapper, typeRef);
      return BodySubscribers.mapping(
          BodySubscribers.ofByteArray(),
          bytes ->
              readValueUnchecked(
                  objectReader, bytes, typeRef, hints.mediaTypeOrAny().charsetOrUtf8()));
    }

    @Override
    public <T> BodySubscriber<Supplier<T>> toDeferredObject(TypeRef<T> typeRef, Hints hints) {
      requireSupport(typeRef, hints);
      var objectReader = readerFactory.createReader(mapper, typeRef);
      return BodySubscribers.mapping(
          BodySubscribers.ofInputStream(),
          inputStream ->
              () ->
                  readValueUnchecked(
                      objectReader, inputStream, typeRef, hints.mediaTypeOrAny().charsetOrUtf8()));
    }

    abstract <T> T readValueUnchecked(
        ObjectReader reader, byte[] bytes, TypeRef<T> typeRef, Charset charset);

    abstract <T> T readValueUnchecked(
        ObjectReader reader, InputStream inputStream, TypeRef<T> typeRef, Charset charset);
  }

  static final class TextFormatDecoder extends AbstractDecoder {
    TextFormatDecoder(
        ObjectMapper mapper, ObjectReaderFactory readerFactory, MediaType... mediaTypes) {
      super(mapper, readerFactory, mediaTypes);
    }

    @Override
    <T> T readValueUnchecked(
        ObjectReader objectReader, byte[] bytes, TypeRef<T> typeRef, Charset charset) {
      try {
        return typeRef.uncheckedCast(objectReader.readValue(new String(bytes, charset)));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    <T> T readValueUnchecked(
        ObjectReader objectReader, InputStream inputStream, TypeRef<T> typeRef, Charset charset) {
      try (var reader = new InputStreamReader(inputStream, charset)) {
        return typeRef.uncheckedCast(objectReader.readValue(reader));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  static final class BinaryFormatDecoder extends AbstractDecoder {
    BinaryFormatDecoder(
        ObjectMapper mapper, ObjectReaderFactory readerFactory, MediaType... mediaTypes) {
      super(mapper, readerFactory, mediaTypes);
    }

    @Override
    <T> T readValueUnchecked(
        ObjectReader objectReader, byte[] bytes, TypeRef<T> typeRef, Charset charset) {
      try {
        return typeRef.uncheckedCast(objectReader.readValue(bytes));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    <T> T readValueUnchecked(
        ObjectReader objectReader, InputStream inputStream, TypeRef<T> typeRef, Charset charset) {
      try {
        return typeRef.uncheckedCast(objectReader.readValue(inputStream));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
