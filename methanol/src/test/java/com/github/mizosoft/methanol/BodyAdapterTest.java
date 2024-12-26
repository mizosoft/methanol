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

import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;

import com.github.mizosoft.methanol.BodyAdapter.Decoder;
import com.github.mizosoft.methanol.testing.TestException;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.util.concurrent.CompletionException;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

class BodyAdapterTest {
  @Test
  void defaultToDeferredObject() {
    verifyThat(new ReplacingDecoder("abc"))
        .converting(String.class)
        .withDeferredBody("")
        .succeedsWith("abc");
  }

  @Test
  void defaultToDeferredObjectWithExceptionalCompletion() {
    verifyThat(new ReplacingDecoder("abc"))
        .converting(String.class)
        .withDeferredFailure(new TestException())
        .failsWith(CompletionException.class)
        .withCauseInstanceOf(TestException.class);
  }

  private static final class ReplacingDecoder implements Decoder {
    private final Object value;

    ReplacingDecoder(Object value) {
      this.value = value;
    }

    @Override
    public boolean isCompatibleWith(MediaType mediaType) {
      return true;
    }

    @Override
    public boolean supportsType(TypeRef<?> typeRef) {
      return typeRef.rawType().isInstance(value);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> BodySubscriber<T> toObject(TypeRef<T> typeRef, @Nullable MediaType mediaType) {
      return (BodySubscriber<T>) BodySubscribers.replacing(value);
    }
  }
}
