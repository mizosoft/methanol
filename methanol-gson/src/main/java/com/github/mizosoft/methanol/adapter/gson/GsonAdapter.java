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

package com.github.mizosoft.methanol.adapter.gson;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.MoreBodySubscribers;
import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.util.function.Supplier;

abstract class GsonAdapter extends AbstractBodyAdapter {
  final Gson gson;

  GsonAdapter(Gson gson) {
    super(MediaType.APPLICATION_JSON);
    this.gson = requireNonNull(gson);
  }

  @Override
  public boolean supportsType(TypeRef<?> typeRef) {
    try {
      gson.getAdapter(TypeToken.get(typeRef.type()));
      return true;
    } catch (IllegalArgumentException ignored) {
      // Gson::getAdapter throws IAE if it can't de/serialize the type.
      return false;
    }
  }

  static final class Encoder extends GsonAdapter implements BaseEncoder {
    Encoder(Gson gson) {
      super(gson);
    }

    @Override
    public <T> BodyPublisher toBody(T value, TypeRef<T> typeRef, Hints hints) {
      requireSupport(typeRef, hints);
      return attachMediaType(
          BodyPublishers.ofString(
              gson.toJson(value, typeRef.type()), hints.effectiveCharsetOrUtf8()),
          hints.mediaTypeOrAny());
    }
  }

  static final class Decoder extends GsonAdapter implements BaseDecoder {
    Decoder(Gson gson) {
      super(gson);
    }

    @Override
    public <T> BodySubscriber<T> toObject(TypeRef<T> typeRef, Hints hints) {
      requireSupport(typeRef, hints);
      return BodySubscribers.mapping(
          BodySubscribers.ofString(hints.effectiveCharsetOrUtf8()),
          json -> gson.fromJson(json, typeRef.type()));
    }

    @Override
    public <T> BodySubscriber<Supplier<T>> toDeferredObject(TypeRef<T> typeRef, Hints hints) {
      requireSupport(typeRef, hints);
      return BodySubscribers.mapping(
          MoreBodySubscribers.ofReader(hints.effectiveCharsetOrUtf8()),
          reader -> () -> gson.fromJson(reader, typeRef.type()));
    }
  }
}
