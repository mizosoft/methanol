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

package com.github.mizosoft.methanol.adapter;

import com.github.mizosoft.methanol.BodyAdapter.Encoder;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.TypeRef;
import java.net.http.HttpRequest.BodyPublisher;
import org.checkerframework.checker.nullness.qual.Nullable;

/** An {@link Encoder} that forwards calls to another. */
public class ForwardingEncoder extends ForwardingBodyAdapter implements Encoder {
  private final Encoder delegate;

  protected ForwardingEncoder(Encoder delegate) {
    this.delegate = delegate;
  }

  @Override
  protected Encoder delegate() {
    return delegate;
  }

  @Override
  public BodyPublisher toBody(Object value, @Nullable MediaType mediaType) {
    return delegate.toBody(value, mediaType);
  }

  @Override
  public <T> BodyPublisher toBody(T value, TypeRef<T> typeRef, Hints hints) {
    return delegate.toBody(value, typeRef, hints);
  }
}
