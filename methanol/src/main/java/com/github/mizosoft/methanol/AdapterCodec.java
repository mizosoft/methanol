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

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.BodyAdapter.Decoder;
import com.github.mizosoft.methanol.BodyAdapter.Encoder;
import com.github.mizosoft.methanol.internal.spi.BodyAdapterProviders;
import com.github.mizosoft.methanol.internal.spi.ServiceProviders;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A group of {@link BodyAdapter adapters}, typically targeting different mapping formats, that
 * facilitates creating {@link BodyPublisher}, {@link BodyHandler} and {@link BodySubscriber}
 * implementations based on these adapters. The correct adapter is selected based on the object type
 * and a {@link MediaType} specifying the desired format.
 */
public final class AdapterCodec {
  /**
   * Codec for the installed encoders & decoders. This is lazily created in a racy manner, which is
   * OK since {@link ServiceProviders} makes sure we see a constant snapshot of the services.
   */
  @SuppressWarnings("NonFinalStaticField") // Lazily initialized.
  private static @MonotonicNonNull AdapterCodec lazyInstalledCodec;

  private final List<Encoder> encoders;
  private final List<Decoder> decoders;

  private AdapterCodec(List<Encoder> encoders, List<Decoder> decoders) {
    this.encoders = List.copyOf(encoders);
    this.decoders = List.copyOf(decoders);
  }

  /** Returns the list of encoders in this codec. */
  public List<Encoder> encoders() {
    return encoders;
  }

  /** Returns the list of decoders in this codec. */
  public List<Decoder> decoders() {
    return decoders;
  }

  /**
   * Returns a {@code BodyPublisher} that encodes the given object into a request body.
   *
   * @throws UnsupportedOperationException if no encoder supporting the given object's runtime type
   *     or the given media type is found
   */
  public BodyPublisher publisherOf(Object object, MediaType mediaType) {
    var objectType = TypeRef.of(object.getClass());
    return lookup(encoders, objectType, mediaType)
        .orElseThrow(() -> unsupportedConversionFrom(objectType, mediaType))
        .toBody(object, mediaType);
  }

  /**
   * Returns a {@code BodySubscriber} that decodes the response body into an object of the given
   * type.
   *
   * @throws UnsupportedOperationException if no decoder supporting the given object type or media
   *     type is found
   */
  public <T> BodySubscriber<T> subscriberOf(TypeRef<T> objectType, MediaType mediaType) {
    return lookup(decoders, objectType, mediaType)
        .orElseThrow(() -> unsupportedConversionTo(objectType, mediaType))
        .toObject(objectType, mediaType);
  }

  /**
   * Returns a {@code BodySubscriber} that lazily decodes the response body into an object of the
   * given type.
   *
   * @throws UnsupportedOperationException if no decoder supporting the given object type or media
   *     type is found
   */
  public <T> BodySubscriber<Supplier<T>> deferredSubscriberOf(
      TypeRef<T> objectType, MediaType mediaType) {
    return lookup(decoders, objectType, mediaType)
        .orElseThrow(() -> unsupportedConversionTo(objectType, mediaType))
        .toDeferredObject(objectType, mediaType);
  }

  /**
   * Returns a {@code BodyHandler} that decodes the response body into an object of the given type.
   *
   * @throws UnsupportedOperationException if no decoder supporting the given object type is found
   */
  public <T> BodyHandler<T> handlerOf(TypeRef<T> objectType) {
    requireDecoderSupport(decoders, objectType);
    return responseInfo -> subscriberOf(objectType, mediaTypeOrAny(responseInfo.headers()));
  }

  /**
   * Returns a {@code BodyHandler} that lazily decodes the response body into an object of the given
   * type.
   *
   * @throws UnsupportedOperationException if no decoder supporting the given object type is found
   */
  public <T> BodyHandler<Supplier<T>> deferredHandlerOf(TypeRef<T> objectType) {
    requireDecoderSupport(decoders, objectType);
    return responseInfo -> deferredSubscriberOf(objectType, mediaTypeOrAny(responseInfo.headers()));
  }

  private static <T extends BodyAdapter> Optional<T> lookup(
      List<T> adapters, TypeRef<?> objectType, MediaType mediaType) {
    requireNonNull(objectType);
    requireNonNull(mediaType);
    return adapters.stream()
        .filter(encoder -> encoder.supportsType(objectType) && encoder.isCompatibleWith(mediaType))
        .findFirst();
  }

  private static UnsupportedOperationException unsupportedConversionFrom(
      TypeRef<?> objectType, MediaType mediaType) {
    var message =
        "Unsupported conversion from an object type <"
            + objectType
            + "> with media "
            + "type <"
            + mediaType
            + ">";
    return new UnsupportedOperationException(message);
  }

  private static UnsupportedOperationException unsupportedConversionTo(
      TypeRef<?> objectType, MediaType mediaType) {
    var message =
        "Unsupported conversion to an object of type <"
            + objectType
            + "> with media type <"
            + mediaType
            + ">";
    return new UnsupportedOperationException(message);
  }

  private static void requireDecoderSupport(List<Decoder> decoders, TypeRef<?> objectType) {
    if (decoders.stream().noneMatch(decoder -> decoder.supportsType(objectType))) {
      throw new UnsupportedOperationException(
          "Unsupported conversion to an object of type <" + objectType + ">");
    }
  }

  private static MediaType mediaTypeOrAny(HttpHeaders headers) {
    return headers.firstValue("Content-Type").map(MediaType::parse).orElse(MediaType.ANY);
  }

  public static AdapterCodec installed() {
    var installedCodec = lazyInstalledCodec;
    if (installedCodec == null) {
      installedCodec =
          new AdapterCodec(BodyAdapterProviders.encoders(), BodyAdapterProviders.decoders());
      lazyInstalledCodec = installedCodec;
    }
    return installedCodec;
  }

  /** Returns a new {@code AdapterCodec.Builder}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** A builder of {@code AdapterCodec}. */
  public static final class Builder {
    private final List<Encoder> encoders = new ArrayList<>();
    private final List<Decoder> decoders = new ArrayList<>();

    Builder() {}

    /** Adds the given encoder. */
    @CanIgnoreReturnValue
    public Builder encoder(Encoder encoder) {
      encoders.add(requireNonNull(encoder));
      return this;
    }

    /** Adds the given decoder. */
    @CanIgnoreReturnValue
    public Builder decoder(Decoder decoder) {
      decoders.add(requireNonNull(decoder));
      return this;
    }

    /** Adds the {@link Encoder#basic() basic encoder}. */
    @CanIgnoreReturnValue
    public Builder basicEncoder() {
      return encoder(Encoder.basic());
    }

    /** Adds the {@link Decoder#basic() basic decoder}. */
    @CanIgnoreReturnValue
    public Builder basicDecoder() {
      return decoder(Decoder.basic());
    }

    /**
     * Adds both the basic {@link Encoder#basic() encoder} {@code &} {@link Decoder#basic() decoder}
     * pair.
     */
    @CanIgnoreReturnValue
    public Builder basicCodec() {
      return basicEncoder().basicDecoder();
    }

    /** Returns a new {@code AdapterCodec} for the added encoders and decoders. */
    public AdapterCodec build() {
      return new AdapterCodec(encoders, decoders);
    }
  }
}
