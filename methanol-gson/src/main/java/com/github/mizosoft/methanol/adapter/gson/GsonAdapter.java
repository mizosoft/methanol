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
import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter;
import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
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
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;

abstract class GsonAdapter extends AbstractBodyAdapter {

  final Gson gson;

  GsonAdapter(Gson gson) {
    super(MediaType.APPLICATION_JSON);
    this.gson = requireNonNull(gson);
  }

  @Override
  public boolean supportsType(TypeRef<?> type) {
    try {
      getAdapter(type);
      return true;
    } catch (IllegalArgumentException e) {
      // Gson::getAdapter throws IAE if it can't de/serialize the type
      return false;
    }
  }

  @SuppressWarnings("unchecked")
  <T> TypeAdapter<T> getAdapter(TypeRef<T> type) {
    return (TypeAdapter<T>) gson.getAdapter(com.google.gson.reflect.TypeToken.get(type.type()));
  }

  static final class Encoder extends GsonAdapter implements BodyAdapter.Encoder {

    Encoder(Gson gson) {
      super(gson);
    }

    @Override
    public BodyPublisher toBody(Object object, @Nullable MediaType mediaType) {
      requireNonNull(object);
      TypeRef<?> runtimeType = TypeRef.of(object.getClass());
      requireSupport(runtimeType);
      requireCompatibleOrNull(mediaType);
      @SuppressWarnings("unchecked")
      TypeAdapter<Object> adapter = (TypeAdapter<Object>) getAdapter(runtimeType);
      ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
      try (JsonWriter writer =
          gson.newJsonWriter(new OutputStreamWriter(outBuffer, charsetOrUtf8(mediaType)))) {
        adapter.write(writer, object);
      } catch (IOException ioe) {
        throw new AssertionError(ioe); // writing to a memory buffer
      }
      return attachMediaType(BodyPublishers.ofByteArray(outBuffer.toByteArray()), mediaType);
    }
  }

  static final class Decoder extends GsonAdapter implements BodyAdapter.Decoder {

    Decoder(Gson gson) {
      super(gson);
    }

    @Override
    public <T> BodySubscriber<T> toObject(TypeRef<T> objectType, @Nullable MediaType mediaType) {
      requireNonNull(objectType);
      requireSupport(objectType);
      requireCompatibleOrNull(mediaType);
      TypeAdapter<T> adapter = getAdapter(objectType);
      Charset charset = charsetOrUtf8(mediaType);
      return BodySubscribers.mapping(
          BodySubscribers.ofByteArray(),
          bytes ->
              toJsonUnchecked(
                  new InputStreamReader(new ByteArrayInputStream(bytes), charset), adapter));
    }

    @Override
    public <T> BodySubscriber<Supplier<T>> toDeferredObject(
        TypeRef<T> objectType, @Nullable MediaType mediaType) {
      requireNonNull(objectType);
      requireSupport(objectType);
      requireCompatibleOrNull(mediaType);
      TypeAdapter<T> adapter = getAdapter(objectType);
      return BodySubscribers.mapping(
          MoreBodySubscribers.ofReader(charsetOrUtf8(mediaType)),
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
