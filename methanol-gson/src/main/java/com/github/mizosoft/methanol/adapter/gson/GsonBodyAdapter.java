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

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.MoreBodySubscribers;
import com.github.mizosoft.methanol.TypeReference;
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter;
import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;

abstract class GsonBodyAdapter extends AbstractBodyAdapter {

  private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;
  private static final MediaType APPLICATION_JSON = MediaType.of("application", "json");

  final Gson gson;

  GsonBodyAdapter(Gson gson) {
    super(APPLICATION_JSON);
    this.gson = requireNonNull(gson);
  }

  @Override
  public boolean supportsType(TypeReference<?> type) {
    try {
      getAdapter(type);
      return true;
    } catch (IllegalArgumentException e) {
      // Gson::getAdapter throws IAE if it can't de/serialize the type
      return false;
    }
  }

  @SuppressWarnings("unchecked")
  <T> TypeAdapter<T> getAdapter(TypeReference<T> type) {
    return (TypeAdapter<T>) gson.getAdapter(TypeToken.get(type.type()));
  }

  static final class Encoder extends GsonBodyAdapter implements BodyAdapter.Encoder {

    Encoder(Gson gson) {
      super(gson);
    }

    @Override
    public BodyPublisher toBody(Object object, @Nullable MediaType mediaType) {
      requireNonNull(object);
      TypeReference<?> runtimeType = TypeReference.from(object.getClass());
      requireSupport(runtimeType);
      requireCompatibleOrNull(mediaType);
      @SuppressWarnings("unchecked")
      TypeAdapter<Object> adapter = (TypeAdapter<Object>) getAdapter(runtimeType);
      ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
      try (JsonWriter writer =
          gson.newJsonWriter(
              new OutputStreamWriter(outBuffer, charsetOrDefault(mediaType, DEFAULT_CHARSET)))) {
        adapter.write(writer, object);
      } catch (IOException ioe) {
        throw new AssertionError(ioe); // writing to a memory buffer
      }
      return attachMediaType(BodyPublishers.ofByteArray(outBuffer.toByteArray()), mediaType);
    }
  }

  static final class Decoder extends GsonBodyAdapter implements BodyAdapter.Decoder {

    Decoder(Gson gson) {
      super(gson);
    }

    @Override
    public <T> BodySubscriber<T> toObject(TypeReference<T> type, @Nullable MediaType mediaType) {
      requireNonNull(type);
      requireSupport(type);
      requireCompatibleOrNull(mediaType);
      TypeAdapter<T> adapter = getAdapter(type);
      Charset charset = charsetOrDefault(mediaType, DEFAULT_CHARSET);
      return BodySubscribers.mapping(
          BodySubscribers.ofByteArray(),
          bytes ->
              toJsonUnchecked(
                  new InputStreamReader(new ByteArrayInputStream(bytes), charset), adapter));
    }

    @Override
    public <T> BodySubscriber<Supplier<T>> toDeferredObject(
        TypeReference<T> type, @Nullable MediaType mediaType) {
      requireNonNull(type);
      requireSupport(type);
      requireCompatibleOrNull(mediaType);
      TypeAdapter<T> adapter = getAdapter(type);
      return BodySubscribers.mapping(
          MoreBodySubscribers.ofReader(charsetOrDefault(mediaType, DEFAULT_CHARSET)),
          in -> () -> toJsonUnchecked(in, adapter));
    }

    private <T> T toJsonUnchecked(Reader in, TypeAdapter<T> adapter) {
      try {
        return adapter.read(gson.newJsonReader(in));
      } catch (IOException ioe) {
        throw new UncheckedIOException(ioe);
      }
    }
  }
}
