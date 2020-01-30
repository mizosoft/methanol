/*
 * MIT License
 *
 * Copyright (c) 2019 Moataz Abdelnasser
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.mizosoft.methanol.testing.EmptyPublisher;
import com.github.mizosoft.methanol.testing.FailedPublisher;
import com.github.mizosoft.methanol.testing.TestException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

class ConverterTest {

  @Test
  void toDeferredObject_default() {
    var converter = new ReplacingConverter("lol");
    var subscriber = converter.toDeferredObject(new TypeReference<String>() {}, null);
    var supplier = toFuture(subscriber).getNow(null);
    assertNotNull(supplier);
    EmptyPublisher.<List<ByteBuffer>>instance().subscribe(subscriber);
    assertEquals("lol", supplier.get());
  }

  @Test
  void toDeferredObject_default_completeExceptionally() {
    var converter = new ReplacingConverter("lol");
    var subscriber = converter.toDeferredObject(new TypeReference<String>() {}, null);
    var supplier = toFuture(subscriber).getNow(null);
    assertNotNull(supplier);
    new FailedPublisher<List<ByteBuffer>>(TestException::new)
        .subscribe(subscriber);
    var uncheckedIoe = assertThrows(UncheckedIOException.class, supplier::get);
    var checkedIoe = uncheckedIoe.getCause();
    assertEquals(TestException.class, checkedIoe.getCause().getClass());
  }

  private static <T> CompletableFuture<T> toFuture(BodySubscriber<T> s) {
    return s.getBody().toCompletableFuture();
  }

  private static final class ReplacingConverter implements Converter.OfResponse {

    private final Object value;

    private ReplacingConverter(Object value) {
      this.value = value;
    }

    @Override public boolean isCompatibleWith(MediaType mediaType) {
      return false;
    }

    @Override public boolean supportsType(TypeReference<?> type) {
      return false;
    }

    @SuppressWarnings("unchecked")
    @Override public <T> BodySubscriber<T> toObject(
        TypeReference<T> type, @Nullable MediaType mediaType) {
      return (BodySubscriber<T>) BodySubscribers.replacing(value);
    }
  }
}