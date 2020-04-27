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

package com.github.mizosoft.methanol;

import com.github.mizosoft.methanol.internal.spi.BodyAdapterFinder;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpResponse.BodySubscriber;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An object that uses a defined format for converting high level objects to or from {@code
 * ByteBuffer} streams. The two specializations are {@link Encoder} and {@link Decoder},
 * implementations of which are normally registered as service-providers by means described in the
 * {@link java.util.ServiceLoader} class.
 *
 * <p>A {@code BodyAdapter} communicates the format it uses and the set of types it supports through
 * {@link #isCompatibleWith(MediaType)} and {@link #supportsType(TypeRef)} respectively. For
 * example, a {@code BodyAdapter} that uses the JSON format is compatible with any {@code
 * application/json} media type, and supports any object type supported by the underlying
 * serializer/deserializer.
 */
public interface BodyAdapter {

  /**
   * Returns {@code true} if the format this adapter uses is {@link
   * MediaType#isCompatibleWith(MediaType) compatible} with the given media type.
   *
   * @param mediaType the media type
   */
  boolean isCompatibleWith(MediaType mediaType);

  /**
   * Returns {@code true} if this adapter supports the given type.
   *
   * @param type the object type
   */
  boolean supportsType(TypeRef<?> type);

  private static <A extends BodyAdapter> Optional<A> lookupAdapter(
      List<A> installed, TypeRef<?> type, @Nullable MediaType mediaType) {
    return installed.stream()
        .filter(a -> a.supportsType(type) && (mediaType == null || a.isCompatibleWith(mediaType)))
        .findFirst();
  }

  /** {@code BodyAdapter} specialization for converting objects into request bodies. */
  interface Encoder extends BodyAdapter {

    /**
     * Returns a {@code BodyPublisher} that encodes the given object into a request body using the
     * format specified by the given media type. If {@code mediaType} is {@code null}, the encoder's
     * default format settings will be used.
     *
     * @param object the object
     * @param mediaType the media type
     * @throws UnsupportedOperationException if the given object's type or the given media type are
     *     not supported
     */
    BodyPublisher toBody(Object object, @Nullable MediaType mediaType);

    /** Returns an immutable list containing the installed encoders. */
    static List<Encoder> installed() {
      return BodyAdapterFinder.findInstalledEncoders();
    }

    /**
     * Returns an {@code Optional} containing an {@code Encoder} that supports the given object type
     * and media type. If {@code mediaType} is {@code null}, any encoder supporting the given type
     * will be returned.
     *
     * @param type the object type
     * @param mediaType an optional media type defining the serialization format
     */
    static Optional<Encoder> getEncoder(TypeRef<?> type, @Nullable MediaType mediaType) {
      return BodyAdapter.lookupAdapter(installed(), type, mediaType);
    }
  }

  /**
   * {@code BodyAdapter} specialization for converting response bodies into objects.
   *
   * @see <a href="https://github.com/mizosoft/methanol/wiki/ConversionWiki#t-vs-suppliert">
   *   {@code T} vs {@code Supplier<}{@code T>}</a>
   */
  interface Decoder extends BodyAdapter {

    /**
     * Returns a {@code BodySubscriber} that decodes the response body into an object of the given
     * type using the format specified by the given media type. If {@code mediaType} is {@code
     * null}, the decoders's default format settings will be used.
     *
     * @param type the object type
     * @param mediaType the media type
     * @param <T> the type represent by {@code type}
     */
    <T> BodySubscriber<T> toObject(TypeRef<T> type, @Nullable MediaType mediaType);

    /**
     * Returns a completed {@code BodySubscriber} that lazily decodes the response body into an
     * object of the given type using the format specified by the given media type. If {@code
     * mediaType} is {@code null}, the decoder's default format settings will be used.
     *
     * <p>The default implementation returns a subscriber completed with a supplier that blocks
     * (uninterruptedly) on the subscriber returned by {@link #toObject(TypeRef, MediaType)}.
     * Any completion exception raised while blocking is rethrown from the supplier as a
     * {@code CompletionException}. Encoders that support reading from a blocking source should
     * override this method to defer reading from such a source until the supplier is called.
     *
     * @param type the object type
     * @param mediaType the media type
     * @param <T> the type represent by {@code type}
     */
    default <T> BodySubscriber<Supplier<T>> toDeferredObject(
        TypeRef<T> type, @Nullable MediaType mediaType) {
      return MoreBodySubscribers.fromAsyncSubscriber(
          toObject(type, mediaType),
          s -> CompletableFuture.completedStage(() -> s.getBody().toCompletableFuture().join()));
    }

    /** Returns an immutable list containing the installed decoders. */
    static List<Decoder> installed() {
      return BodyAdapterFinder.findInstalledDecoders();
    }

    /**
     * Returns an {@code Optional} containing a {@code Decoder} that supports the given object type
     * and media type. If {@code mediaType} is {@code null}, any decoder supporting the given type
     * will be returned.
     *
     * @param type the object type
     * @param mediaType an optional media type defining the deserialization format
     */
    static Optional<Decoder> getDecoder(TypeRef<?> type, @Nullable MediaType mediaType) {
      return BodyAdapter.lookupAdapter(installed(), type, mediaType);
    }
  }
}
