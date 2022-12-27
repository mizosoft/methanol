/*
 * Copyright (c) 2022 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.testing;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.charset.StandardCharsets;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class StringDecoder extends AbstractBodyAdapter implements BodyAdapter.Decoder {
  public StringDecoder() {
    super(MediaType.TEXT_ANY);
  }

  @Override
  public boolean supportsType(TypeRef<?> type) {
    return String.class == type.type();
  }

  @Override
  public <T> BodySubscriber<T> toObject(TypeRef<T> objectType, @Nullable MediaType mediaType) {
    requireNonNull(objectType);
    requireSupport(objectType);
    requireCompatibleOrNull(mediaType);
    var subscriber = BodySubscribers.ofString(charsetOrDefault(mediaType, StandardCharsets.UTF_8));
    return BodySubscribers.mapping(subscriber, objectType.exactRawType()::cast);
  }
}
