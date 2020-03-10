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

package com.github.mizosoft.methanol.benchmarks;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.TypeReference;
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import org.checkerframework.checker.nullness.qual.Nullable;

final class ByteArrayJacksonDecoder extends AbstractBodyAdapter implements BodyAdapter.Decoder {

  private final JsonMapper mapper;

  ByteArrayJacksonDecoder(JsonMapper mapper, MediaType mediaType) {
    super(mediaType);
    this.mapper = mapper;
  }

  @Override
  public boolean supportsType(TypeReference<?> type) {
    return mapper.canDeserialize(mapper.constructType(type.type()));
  }

  @Override
  public <T> BodySubscriber<T> toObject(TypeReference<T> type, @Nullable MediaType mediaType) {
    requireNonNull(type);
    requireSupport(type);
    requireCompatibleOrNull(mediaType);
    return BodySubscribers.mapping(
        BodySubscribers.ofByteArray(), bytes -> readValueUnchecked(type, bytes));
  }

  private <T> T readValueUnchecked(TypeReference<T> type, byte[] body) {
    try {
      JsonParser parser = mapper.getFactory().createParser(body);
      return mapper.readerFor(mapper.constructType(type.type())).readValue(parser);
    } catch (IOException ioe) {
      throw new UncheckedIOException(ioe);
    }
  }
}
