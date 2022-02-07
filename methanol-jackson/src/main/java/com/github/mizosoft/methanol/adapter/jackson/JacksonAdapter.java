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

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;

// TODO optimize code paths where UTF-8 is expected
abstract class JacksonAdapter extends AbstractBodyAdapter {
  final ObjectMapper mapper;

  JacksonAdapter(ObjectMapper mapper, MediaType... mediaTypes) {
    super(mediaTypes);
    this.mapper = requireNonNull(mapper);
  }

  private abstract static class AbstractEncoder extends JacksonAdapter
      implements BodyAdapter.Encoder {
    final ObjectWriterFactory writerFactory;

    AbstractEncoder(
        ObjectMapper mapper, ObjectWriterFactory writerFactory, MediaType... mediaTypes) {
      super(mapper, mediaTypes);
      this.writerFactory = requireNonNull(writerFactory);
    }

    @Override
    public boolean supportsType(TypeRef<?> type) {
      return mapper.canSerialize(type.rawType());
    }

    @Override
    public BodyPublisher toBody(Object object, @Nullable MediaType mediaType) {
      requireNonNull(object);
      requireSupport(object.getClass());
      requireCompatibleOrNull(mediaType);
      byte[] bytes;
      var objWriter = writerFactory.createWriter(mapper, TypeRef.from(object.getClass()));
      try {
        bytes = getBytes(objWriter, object, mediaType);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return attachMediaType(BodyPublishers.ofByteArray(bytes), mediaType);
    }

    abstract byte[] getBytes(ObjectWriter objWriter, Object value, @Nullable MediaType mediaType)
        throws IOException;
  }

  static final class TextFormatEncoder extends AbstractEncoder {
    TextFormatEncoder(
        ObjectMapper mapper, ObjectWriterFactory writerFactory, MediaType... mediaTypes) {
      super(mapper, writerFactory, mediaTypes);
    }

    @Override
    byte[] getBytes(ObjectWriter objWriter, Object value, @Nullable MediaType mediaType)
        throws IOException {
      var buffer = new ByteArrayOutputStream();
      try (var writer = new OutputStreamWriter(buffer, charsetOrUtf8(mediaType))) {
        objWriter.writeValue(writer, value);
      }
      return buffer.toByteArray();
    }
  }

  static final class BinaryFormatEncoder extends AbstractEncoder {
    BinaryFormatEncoder(
        ObjectMapper mapper, ObjectWriterFactory writerFactory, MediaType... mediaTypes) {
      super(mapper, writerFactory, mediaTypes);
    }

    @Override
    byte[] getBytes(ObjectWriter objWriter, Object value, @Nullable MediaType mediaType)
        throws IOException {
      return objWriter.writeValueAsBytes(value);
    }
  }

  private abstract static class AbstractDecoder extends JacksonAdapter
      implements BodyAdapter.Decoder {
    final ObjectReaderFactory readerFactory;

    AbstractDecoder(
        ObjectMapper mapper, ObjectReaderFactory readerFactory, MediaType... mediaTypes) {
      super(mapper, mediaTypes);
      this.readerFactory = requireNonNull(readerFactory);
    }

    @Override
    public boolean supportsType(TypeRef<?> type) {
      return mapper.canDeserialize(mapper.constructType(type.type()));
    }

    @Override
    public <T> BodySubscriber<T> toObject(TypeRef<T> type, @Nullable MediaType mediaType) {
      requireNonNull(type);
      requireSupport(type);
      requireCompatibleOrNull(mediaType);
      var objReader = readerFactory.createReader(mapper, type);
      return BodySubscribers.mapping(
          BodySubscribers.ofByteArray(), bytes -> readValueUnchecked(objReader, bytes, mediaType));
    }

    @Override
    public <T> BodySubscriber<Supplier<T>> toDeferredObject(
        TypeRef<T> type, @Nullable MediaType mediaType) {
      requireNonNull(type);
      requireSupport(type);
      requireCompatibleOrNull(mediaType);
      var objReader = readerFactory.createReader(mapper, type);
      return BodySubscribers.mapping(
          BodySubscribers.ofInputStream(),
          inputStream -> () -> readValueUnchecked(objReader, inputStream, mediaType));
    }

    abstract <T> T readValueUnchecked(
        ObjectReader reader, byte[] bytes, @Nullable MediaType mediaType);

    abstract <T> T readValueUnchecked(
        ObjectReader reader, InputStream inputStream, @Nullable MediaType mediaType);
  }

  static final class TextFormatDecoder extends AbstractDecoder {
    TextFormatDecoder(
        ObjectMapper mapper, ObjectReaderFactory readerFactory, MediaType... mediaTypes) {
      super(mapper, readerFactory, mediaTypes);
    }

    <T> T readValueUnchecked(ObjectReader objReader, byte[] bytes, @Nullable MediaType mediaType) {
      try {
        return objReader.readValue(new String(bytes, charsetOrUtf8(mediaType)));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    <T> T readValueUnchecked(
        ObjectReader objReader, InputStream inputStream, @Nullable MediaType mediaType) {
      try (var reader = new InputStreamReader(inputStream, charsetOrUtf8(mediaType))) {
        return objReader.readValue(reader);
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
    <T> T readValueUnchecked(ObjectReader objReader, byte[] bytes, @Nullable MediaType mediaType) {
      try {
        return objReader.readValue(bytes);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    <T> T readValueUnchecked(
        ObjectReader objReader, InputStream inputStream, @Nullable MediaType mediaType) {
      try {
        return objReader.readValue(inputStream);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
