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

import java.io.IOException;
import java.net.http.HttpResponse.BodyHandler;
import java.util.concurrent.CompletableFuture;

/**
 * A response body that is yet to be handled into the desirable type. This can be useful when the
 * response body differs in structure (and hence in high-level type) according to whether it has
 * succeeded or failed. An implementation of {@code ResponsePayload} can be acquired as the response
 * body through {@link BodyAdapter.Decoder#basic() the basic decoder}. See example below.
 *
 * <p>To avoid resource leaks, use this type within a try-with-resources construct immediately after
 * sending the response (regardless of whether the body is converted synchronously or
 * asynchronously):
 *
 * <pre>{@code
 * var client =
 *     Methanol.newBuilder()
 *         .adapterCodec(AdapterCodec.newBuilder().basic().build()) // Install basic adapters.
 *         .build()
 * var response = client.send(MutableRequest.GET("https://example.com"));
 * try(var body = response.body()) {
 *   if (HttpStatus.isSuccessful(response) {
 *     System.out.println(body.as(SuccessType.class));
 *   } else if (HttpStatus.isClientError(response)) {
 *     System.out.println(body.as(ErrorType.class));
 *   } else {
 *     // Discard body.
 *   }
 * }
 * }</pre>
 */
public interface ResponsePayload extends AutoCloseable {

  /** Returns true if this payload has the given media type. */
  boolean is(MediaType mediaType);

  /** Returns true if this payload has any of the given media types. */
  default boolean isAnyOf(MediaType... mediaTypes) {
    for (var mediaType : mediaTypes) {
      if (is(mediaType)) {
        return true;
      }
    }
    return false;
  }

  /** Converts this payload into an object of type {@code T}. */
  default <T> T to(Class<T> type) throws IOException, InterruptedException {
    return to(TypeRef.of(type));
  }

  /** Converts this payload into an object of (possibly generic) type {@code T}. */
  <T> T to(TypeRef<T> typeRef) throws IOException, InterruptedException;

  /** Converts this payload using the given body handler. */
  <T> T handleWith(BodyHandler<T> bodyHandler) throws IOException, InterruptedException;

  /** Asynchronously converts this payload into an object of type {@code T}. */
  default <T> CompletableFuture<T> toAsync(Class<T> type) {
    return toAsync(TypeRef.of(type));
  }

  /** Asynchronously converts this payload into an object of (possibly generic) type {@code T}. */
  <T> CompletableFuture<T> toAsync(TypeRef<T> typeRef);

  /** Asynchronously converts this payload using the given body handler. */
  <T> CompletableFuture<T> handleWithAsync(BodyHandler<T> bodyHandler);

  /**
   * Makes sure the resources held by this payload are released. If the payload has been consumed,
   * this method does nothing.
   */
  @Override
  void close();
}
