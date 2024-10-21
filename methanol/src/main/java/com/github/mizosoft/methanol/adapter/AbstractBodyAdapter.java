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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.MimeBodyPublisher;
import com.github.mizosoft.methanol.MoreBodyPublishers;
import com.github.mizosoft.methanol.MoreBodySubscribers;
import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.internal.Utils;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.charset.Charset;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An abstract {@link BodyAdapter} that implements {@link BodyAdapter#isCompatibleWith(MediaType)}
 * by allowing subclasses to specify a set of {@link MediaType MediaTypes} the adapter is compatible
 * with.
 */
public abstract class AbstractBodyAdapter implements BodyAdapter {
  private final Set<MediaType> compatibleMediaTypes;

  /** Creates an {@code AbstractBodyAdapter} compatible with the given media types. */
  protected AbstractBodyAdapter(MediaType... compatibleMediaTypes) {
    this.compatibleMediaTypes = Set.of(compatibleMediaTypes);
  }

  @Override
  public final boolean isCompatibleWith(MediaType mediaType) {
    return compatibleMediaTypes.stream().anyMatch(mediaType::isCompatibleWith);
  }

  /** Returns an immutable set containing the media types this adapter is compatible with. */
  protected Set<MediaType> compatibleMediaTypes() {
    return compatibleMediaTypes;
  }

  /**
   * Requires that this adapter {@link BodyAdapter#supportsType(TypeRef) supports} the given type.
   *
   * @throws UnsupportedOperationException if this adapter doesn't support the given type.
   */
  protected void requireSupport(TypeRef<?> typeRef) {
    requireSupport(typeRef, Hints.empty());
  }

  /**
   * Requires that this adapter {@link BodyAdapter#supportsType(TypeRef) supports} the given type.
   *
   * @throws UnsupportedOperationException if this adapter doesn't support the given type.
   */
  protected void requireSupport(Class<?> type) {
    requireSupport(TypeRef.of(type), Hints.empty());
  }

  /**
   * Requires that this adapter {@link BodyAdapter#supportsType(TypeRef) supports} the given type
   * and {@link BodyAdapter#isCompatibleWith(MediaType) is compatible with} the given hints' media
   * type, if any.
   *
   * @throws UnsupportedOperationException if this adapter doesn't support the given type or is not
   *     compatible with the given hints' media type.
   */
  protected void requireSupport(TypeRef<?> typeRef, Hints hints) {
    if (!supportsType(typeRef)) {
      throw new UnsupportedOperationException("Unsupported type: " + typeRef);
    }
    if (!isCompatibleWith(hints.mediaTypeOrAny())) {
      throw new UnsupportedOperationException(
          "This adapter is not compatible with: " + hints.mediaTypeOrAny());
    }
  }

  /**
   * Requires that either this adapter is {@link BodyAdapter#isCompatibleWith(MediaType) compatible}
   * with the given media type, or the given media type is {@code null}.
   *
   * @throws UnsupportedOperationException if this adapter is not compatible with the given media
   *     type.
   */
  protected void requireCompatibleOrNull(@Nullable MediaType mediaType) {
    if (mediaType != null && !isCompatibleWith(mediaType)) {
      throw new UnsupportedOperationException("Adapter not compatible with: " + mediaType);
    }
  }

  /**
   * Returns either the result of {@link MediaType#charsetOrDefault(Charset)}, or the given charset
   * if the given media type is {@code null}.
   */
  public static Charset charsetOrDefault(@Nullable MediaType mediaType, Charset defaultCharset) {
    requireNonNull(defaultCharset);
    return mediaType != null ? mediaType.charsetOrDefault(defaultCharset) : defaultCharset;
  }

  /**
   * Returns either the result of {@link MediaType#charsetOrDefault(Charset)}, or {@code UTF-8} if
   * the given media type is {@code null}.
   */
  public static Charset charsetOrUtf8(@Nullable MediaType mediaType) {
    return charsetOrDefault(mediaType, UTF_8);
  }

  /**
   * Converts the given publisher into a {@link MimeBodyPublisher} that has the given media type
   * only if it is not {@code null} or {@link MediaType#hasWildcard() has a wildcard}, otherwise the
   * given publisher is returned as-is.
   */
  public static BodyPublisher attachMediaType(
      BodyPublisher publisher, @Nullable MediaType mediaType) {
    requireNonNull(publisher);
    if (mediaType != null && !mediaType.hasWildcard()) {
      return MoreBodyPublishers.ofMediaType(publisher, mediaType);
    }
    return publisher;
  }

  /**
   * This interface abstracts the more-capable {@link #toBody(Object, TypeRef, Hints)} method and
   * adds a default implementation for {@link #toBody(Object, MediaType)} that forwards to the
   * former.
   */
  public interface BaseEncoder extends Encoder {

    /**
     * {@inheritDoc}
     *
     * @implNote The default implementation is equivalent to {@code return toBody(value,
     *     TypeRef.ofRuntimeType(value), mediaType != null ? Hints.of(mediaType) : Hints.empty())}.
     */
    @Override
    default BodyPublisher toBody(Object value, @Nullable MediaType mediaType) {
      return toBody(value, TypeRef.ofRuntimeType(value), Utils.hintsOf(mediaType));
    }

    @Override
    <T> BodyPublisher toBody(T value, TypeRef<T> typeRef, Hints hints);
  }

  /**
   * This interface abstracts the more-capable {@link #toObject(TypeRef, Hints)} method and adds a
   * default implementation for {@link #toObject(TypeRef, MediaType)} that forwards to the former.
   * Additionally, this interface specifies a naive default implementation for {@link
   * #toDeferredObject(TypeRef, Hints)}, which streaming decoders should override, and defaults
   * {@link #toDeferredObject(TypeRef, MediaType)} to forward to the former.
   */
  public interface BaseDecoder extends Decoder {

    /**
     * {@inheritDoc}
     *
     * @implSpec The default implementation is equivalent to {@code return toObject(typeRef,
     *     mediaType != null ? Hints.of(mediaType) : Hints.empty())}.
     */
    @Override
    default <T> BodySubscriber<T> toObject(TypeRef<T> typeRef, @Nullable MediaType mediaType) {
      return toObject(typeRef, Utils.hintsOf(mediaType));
    }

    @Override
    <T> BodySubscriber<T> toObject(TypeRef<T> typeRef, Hints hints);

    /**
     * {@inheritDoc}
     *
     * @implNote The default implementation is equivalent to {@code return toDeferredObject(typeRef,
     *     mediaType != null ? Hints.of(mediaType) : Hints.empty())}.
     */
    @Override
    default <T> BodySubscriber<Supplier<T>> toDeferredObject(
        TypeRef<T> typeRef, @Nullable MediaType mediaType) {
      return toDeferredObject(typeRef, Utils.hintsOf(mediaType));
    }

    /**
     * {@inheritDoc}
     *
     * @implSpec The default implementation returns a subscriber completed with a supplier that
     *     blocks, uninterruptedly, on the subscriber returned by {@link #toObject(TypeRef, Hints)}.
     *     Any exception raised while blocking is rethrown from the supplier as a {@link
     *     CompletionException}. Decoders that support reading from a blocking source should
     *     override this method to defer reading from such a source until the supplier is called.
     */
    @Override
    default <T> BodySubscriber<Supplier<T>> toDeferredObject(TypeRef<T> typeRef, Hints hints) {
      return MoreBodySubscribers.fromAsyncSubscriber(
          toObject(typeRef, hints),
          subscriber ->
              CompletableFuture.completedStage(
                  () -> subscriber.getBody().toCompletableFuture().join()));
    }
  }
}
