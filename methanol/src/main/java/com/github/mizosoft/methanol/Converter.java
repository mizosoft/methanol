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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpResponse.BodySubscriber;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An object that uses a defined format for converting high level objects to or from {@code
 * ByteBuffer} streams. The two specialized subtypes are {@link OfRequest} and {@link OfResponse},
 * implementations of which are normally registered as service-providers by means described in the
 * {@link java.util.ServiceLoader} class.
 *
 * <p>A converter communicates the format it uses and the set of types it supports through {@link
 * #isCompatibleWith(MediaType)} and {@link #supportsType(TypeReference)} respectively. For example,
 * a {@code Converter} that uses the JSON format is compatible with any {@code application/json}
 * media type, and supports any object type supported by the underlying serializer/deserializer.
 */
public interface Converter {

  /**
   * Returns {@code true} if the format this converter uses is {@link
   * MediaType#isCompatibleWith(MediaType) compatible} with the given media type.
   *
   * @param mediaType the media type
   */
  boolean isCompatibleWith(MediaType mediaType);

  /**
   * Returns {@code true} if this converter supports the given type.
   *
   * @param type the object type
   */
  boolean supportsType(TypeReference<?> type);

  /**
   * {@code Converter} specialization for serializing objects into request bodies.
   */
  interface OfRequest extends Converter {

    /**
     * Returns a {@code BodyPublisher} that serializes the given object into a request body using
     * the format specified by the given media type. If {@code mediaType} is {@code null}, the
     * converter's default format settings will be used.
     *
     * @param object    the object
     * @param mediaType the media type
     * @throws UnsupportedOperationException if the given object's type or the given media type are
     *                                       not supported
     */
    BodyPublisher toBody(Object object, @Nullable MediaType mediaType);

    /**
     * Returns an immutable list containing the installed {@code OfRequest} converters.
     */
    static List<OfRequest> installed() {
      throw new UnsupportedOperationException("not implemented");
    }

    /**
     * Returns an {@code Optional} containing an {@code OfRequest} converter that supports the given
     * object type and media type. If {@code mediaType} is {@code null}, any converter supporting
     * the given type will be returned.
     *
     * @param type      the object type
     * @param mediaType an optional media type defining the serialization format
     */
    static Optional<OfRequest> getConverter(TypeReference<?> type, @Nullable MediaType mediaType) {
      return Converter.lookupConverter(installed(), type, mediaType);
    }
  }

  /**
   * {@code Converter} specialization for de-serializing response bodies into objects.
   *
   * <p>TODO: explain why there exists T and Supplier of T variants
   */
  interface OfResponse extends Converter {

    /**
     * Returns a {@code BodySubscriber} that deserializes the response body into an object of the
     * given type using the format specified by the given media type. If {@code mediaType} is {@code
     * null}, the converter's default format settings will be used.
     *
     * @param type      the object type
     * @param mediaType the media type
     * @param <T>       the type represent by {@code type}
     */
    <T> BodySubscriber<T> toObject(TypeReference<T> type, @Nullable MediaType mediaType);

    /**
     * Returns a completed {@code BodySubscriber} that lazily deserializes the response body into an
     * object of the given type using the format specified by the given media type. If {@code
     * mediaType} is {@code null}, the converter's default format settings will be used.
     *
     * <p>The default implementation returns a subscriber completed with a supplier that blocks
     * (uninterruptedly) on the subscriber returned by {@link #toObject(TypeReference, MediaType)}.
     * Any completion exception raised while blocking will be rethrown from the supplier as an
     * {@code UncheckedIOException}. {@code OfResponse} converters that support reading from a
     * blocking source should override this method to defer reading from such a source until the
     * supplier is called.
     *
     * @param type      the object type
     * @param mediaType the media type
     * @param <T>       the type represent by {@code type}
     */
    default <T> BodySubscriber<Supplier<T>> toDeferredObject(
        TypeReference<T> type, @Nullable MediaType mediaType) {
      return MoreBodySubscribers.fromAsyncSubscriber(
          toObject(type, mediaType), s -> CompletableFuture.completedStage(defer(s.getBody())));
    }

    private <T> Supplier<T> defer(CompletionStage<T> stage) {
      return () -> {
        try {
          return stage.toCompletableFuture().join();
        } catch (CompletionException e) {
          // TODO: try to clone if cause is RuntimeException or Error
          Throwable t = e.getCause();
          if (t instanceof IOException) {
            throw new UncheckedIOException(((IOException) t));
          }
          throw new UncheckedIOException(new IOException(t));
        }
      };
    }

    /**
     * Returns an immutable list containing the installed {@code OfResponse} converters.
     */
    static List<OfResponse> installed() {
      throw new UnsupportedOperationException("not implemented");
    }

    /**
     * Returns an {@code Optional} containing an {@code OfResponse} converter that supports the
     * given object type and media type. If {@code mediaType} is {@code null}, any converter
     * supporting the given type will be returned.
     *
     * @param type      the object type
     * @param mediaType an optional media type defining the deserialization format
     */
    static Optional<OfResponse> getConverter(TypeReference<?> type, @Nullable MediaType mediaType) {
      return Converter.lookupConverter(installed(), type, mediaType);
    }
  }

  private static <C extends Converter> Optional<C> lookupConverter(
      List<C> installed, TypeReference<?> type, @Nullable MediaType mediaType) {
    return installed.stream()
        .filter(c -> c.supportsType(type)
            && (mediaType == null || c.isCompatibleWith(mediaType)))
        .findFirst();
  }
}
