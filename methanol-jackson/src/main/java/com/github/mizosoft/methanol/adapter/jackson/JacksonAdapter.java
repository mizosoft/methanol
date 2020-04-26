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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.MoreBodySubscribers;
import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter;
import com.github.mizosoft.methanol.adapter.jackson.internal.JacksonAdapterUtils;
import com.github.mizosoft.methanol.adapter.jackson.internal.JacksonSubscriber;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;

abstract class JacksonAdapter extends AbstractBodyAdapter {

  final ObjectMapper mapper;

  JacksonAdapter(ObjectMapper mapper) {
    super(MediaType.APPLICATION_JSON);
    this.mapper = requireNonNull(mapper);
  }

  static final class Encoder extends JacksonAdapter implements BodyAdapter.Encoder {

    Encoder(ObjectMapper mapper) {
      super(mapper);
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
      ObjectWriter objWriter = mapper.writerFor(object.getClass());
      ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
      try (Writer writer = new OutputStreamWriter(outBuffer, charsetOrUtf8(mediaType))) {
        objWriter.writeValue(writer, object);
      } catch (JsonProcessingException e) {
        throw new UncheckedIOException(e);
      } catch (IOException ioe) {
        throw new AssertionError(ioe); // writing to a memory buffer
      }
      return attachMediaType(BodyPublishers.ofByteArray(outBuffer.toByteArray()), mediaType);
    }
  }

  static final class Decoder extends JacksonAdapter implements BodyAdapter.Decoder {

    Decoder(ObjectMapper mapper) {
      super(mapper);
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
      JsonParser asyncParser;
      try {
        asyncParser = mapper.getFactory().createNonBlockingByteArrayParser();
      } catch (IOException | UnsupportedOperationException ignored) {
        // Fallback to de-serializing from byte array
        return BodySubscribers.mapping(
            BodySubscribers.ofByteArray(), bytes -> readValueUnchecked(type, bytes));
      }
      return JacksonAdapterUtils.coerceUtf8(
          new JacksonSubscriber<>(mapper, type, asyncParser), charsetOrUtf8(mediaType));
    }

    @Override
    public <T> BodySubscriber<Supplier<T>> toDeferredObject(
        TypeRef<T> type, @Nullable MediaType mediaType) {
      requireNonNull(type);
      requireSupport(type);
      requireCompatibleOrNull(mediaType);
      return BodySubscribers.mapping(
          MoreBodySubscribers.ofReader(charsetOrUtf8(mediaType)),
          reader -> () -> readValueUnchecked(type, reader));
    }

    private <T> T readValueUnchecked(TypeRef<T> type, byte[] body) {
      try {
        JsonParser parser = mapper.getFactory().createParser(body);
        return mapper.readerFor(mapper.constructType(type.type())).readValue(parser);
      } catch (IOException ioe) {
        throw new UncheckedIOException(ioe);
      }
    }

    private <T> T readValueUnchecked(TypeRef<T> type, Reader reader) {
      try {
        return mapper.readerFor(mapper.constructType(type.type())).readValue(reader);
      } catch (IOException ioe) {
        throw new UncheckedIOException(ioe);
      }
    }
  }
}
