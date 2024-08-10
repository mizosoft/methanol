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

package com.github.mizosoft.methanol;

import com.github.mizosoft.methanol.internal.extensions.BasicAdapter;
import com.github.mizosoft.methanol.internal.spi.BodyAdapterProviders;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpResponse.BodySubscriber;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An object that converts high objects to or from request or response bodies respectively, using a
 * defined format. The two specialized subtypes are {@link Encoder} and {@link Decoder}.
 *
 * <p>A {@code BodyAdapter} communicates the format it uses and the set of types it supports through
 * {@link #isCompatibleWith(MediaType)} and {@link #supportsType(TypeRef)} respectively. For
 * example, a {@code BodyAdapter} that uses JSON is compatible with any {@code application/json}
 * media type, and supports any object type supported by the underlying serializer/deserializer.
 */
public interface BodyAdapter {

  /**
   * Returns {@code true} if the format this adapter uses is {@link
   * MediaType#isCompatibleWith(MediaType) compatible} with the given media type.
   */
  boolean isCompatibleWith(MediaType mediaType);

  /** Returns {@code true} if this adapter supports the given type. */
  boolean supportsType(TypeRef<?> type);

  private static <A extends BodyAdapter> Optional<A> lookupAdapter(
      List<A> installed, TypeRef<?> typeRef, @Nullable MediaType mediaType) {
    return installed.stream()
        .filter(
            adapter ->
                adapter.supportsType(typeRef)
                    && (mediaType == null || adapter.isCompatibleWith(mediaType)))
        .findFirst();
  }

  /** A {@code BodyAdapter} that encodes objects into request bodies. */
  interface Encoder extends BodyAdapter {

    /**
     * Returns a {@code BodyPublisher} that encodes the given object into a request body using the
     * format specified by the given media type. If {@code mediaType} is {@code null}, the encoder's
     * default format parameters (e.g. charset) are be used.
     *
     * @throws UnsupportedOperationException if the given object's runtime type or the given media
     *     type are not supported
     */
    BodyPublisher toBody(Object object, @Nullable MediaType mediaType);

    /** Returns an immutable list containing the installed encoders. */
    static List<Encoder> installed() {
      return BodyAdapterProviders.encoders();
    }

    /**
     * Returns an {@code Optional} containing an {@code Encoder} that supports the given object type
     * and media type. If {@code mediaType} is {@code null}, any encoder supporting the given object
     * type is returned
     */
    static Optional<Encoder> getEncoder(TypeRef<?> objectTypeRef, @Nullable MediaType mediaType) {
      return BodyAdapter.lookupAdapter(installed(), objectTypeRef, mediaType);
    }

    /**
     * Returns the basic encoder. The basic encoder is compatible with any media type, and supports
     * encoding any subtype of:
     *
     * <ul>
     *   <li>{@code CharSequence} (encoded using the media-type's charset, or {@code UTF-8} in case
     *       the former is not present)
     *   <li>{@code InputStream}
     *   <li>{@code byte[]}
     *   <li>{@code ByteBuffer}
     *   <li>{@code Path} (represents a file from which the request content is sent)
     * </ul>
     */
    static Encoder basic() {
      return BasicAdapter.encoder();
    }
  }

  /** A {@code BodyAdapter} that decodes response bodies into objects. */
  interface Decoder extends BodyAdapter {

    /**
     * Returns a {@code BodySubscriber} that decodes the response body into an object of the given
     * type using the format specified by the given media type. If {@code mediaType} is {@code
     * null}, the decoder's default format parameters (e.g. charset) are used.
     */
    <T> BodySubscriber<T> toObject(TypeRef<T> objectType, @Nullable MediaType mediaType);

    /**
     * Returns a completed {@code BodySubscriber} that lazily decodes the response body into an
     * object of the given type using the format specified by the given media type. If {@code
     * mediaType} is {@code null}, the decoder's default format parameters (e.g. charset) are used.
     *
     * <p>The default implementation returns a subscriber completed with a supplier that blocks
     * ,uninterruptedly, on the subscriber returned by {@link #toObject(TypeRef, MediaType)}. Any
     * completion exception raised while blocking is rethrown from the supplier as a {@code
     * CompletionException}. Encoders that support reading from a blocking source should override
     * this method to defer reading from such a source until the supplier is called.
     */
    default <T> BodySubscriber<Supplier<T>> toDeferredObject(
        TypeRef<T> objectType, @Nullable MediaType mediaType) {
      return MoreBodySubscribers.fromAsyncSubscriber(
          toObject(objectType, mediaType),
          subscriber ->
              CompletableFuture.completedStage(
                  () -> subscriber.getBody().toCompletableFuture().join()));
    }

    /** Returns an immutable list containing the installed decoders. */
    static List<Decoder> installed() {
      return BodyAdapterProviders.decoders();
    }

    /**
     * Returns an {@code Optional} containing a {@code Decoder} that supports the given object type
     * and media type. If {@code mediaType} is {@code null}, any decoder supporting the given object
     * type is returned.
     */
    static Optional<Decoder> getDecoder(TypeRef<?> objectType, @Nullable MediaType mediaType) {
      return BodyAdapter.lookupAdapter(installed(), objectType, mediaType);
    }

    /**
     * Returns the basic decoder. The basic decoder is compatible with any media type, and supports
     * decoding:
     *
     * <ul>
     *   <li>{@code String} (decoded using the media-type's charset, or {@code UTF-8} in case the
     *       former is not present)
     *   <li>{@code InputStream}
     *   <li>{@code Reader} (decoded using the media-type's charset, or {@code UTF-8} in case the
     *       former is not present)
     *   <li>{@code byte[]}
     *   <li>{@code ByteBuffer}
     * </ul>
     */
    static Decoder basic() {
      return BasicAdapter.decoder();
    }
  }
}
