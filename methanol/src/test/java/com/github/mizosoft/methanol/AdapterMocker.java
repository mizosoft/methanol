/*
 * Copyright (c) 2024 Moataz Hussein
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

package com.github.mizosoft.methanol;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.mizosoft.methanol.BodyAdapter.Encoder;
import com.github.mizosoft.methanol.BodyAdapter.Hints;
import java.net.http.HttpRequest.BodyPublisher;
import java.util.function.Supplier;

public class AdapterMocker {
  private AdapterMocker() {}

  static <T> Encoder mockEncoder(
      T value, TypeRef<T> typeRef, Hints hints, BodyPublisher publisher) {
    var encoder = mock(Encoder.class);
    when(encoder.supportsType(typeRef)).thenReturn(true);
    when(encoder.isCompatibleWith(hints.mediaTypeOrAny())).thenReturn(true);
    when(encoder.toBody(value, hints.mediaTypeOrAny())).thenReturn(publisher);
    when(encoder.toBody(
            eq(value),
            eq(typeRef),
            argThat(
                hintsArg -> hintsArg.mediaTypeOrAny().isCompatibleWith(hints.mediaTypeOrAny()))))
        .thenReturn(publisher);
    return encoder;
  }

  static <T> Encoder mockEncoder(
      T value, TypeRef<T> typeRef, Hints hints, Supplier<BodyPublisher> publisherSupplier) {
    var encoder = mock(Encoder.class);
    when(encoder.supportsType(typeRef)).thenReturn(true);
    when(encoder.isCompatibleWith(hints.mediaTypeOrAny())).thenReturn(true);
    when(encoder.toBody(value, hints.mediaTypeOrAny())).thenAnswer(__ -> publisherSupplier.get());
    when(encoder.toBody(
            eq(value),
            eq(typeRef),
            argThat(
                hintsArg -> hintsArg.mediaTypeOrAny().isCompatibleWith(hints.mediaTypeOrAny()))))
        .thenAnswer(__ -> publisherSupplier.get());
    return encoder;
  }
}
