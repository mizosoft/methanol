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

import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.BodyAdapter.Decoder;
import com.github.mizosoft.methanol.BodyAdapter.Encoder;
import com.github.mizosoft.methanol.BodyAdapter.Hints;
import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.spi.ServiceProviders;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A group of {@link BodyAdapter adapters} ({@link Encoder encoders} & {@link Decoder decoders}),
 * typically targeting different mapping schemes, that facilitates creating corresponding {@link
 * BodyPublisher}, {@link BodyHandler} and {@link BodySubscriber} implementations. The correct
 * adapter is selected based on the object type and caller's {@link Hints}, typically containing the
 * {@link MediaType} of the mapping format.
 */
public final class AdapterCodec {
  private static final ServiceProviders<Encoder> installedEncoders =
      new ServiceProviders<>(Encoder.class);
  private static final ServiceProviders<Decoder> installedDecoders =
      new ServiceProviders<>(Decoder.class);

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
   * Returns a {@link BodyPublisher} that encodes the given object into a request body.
   *
   * @throws UnsupportedOperationException if no encoder that supports encoding the given object or
   *     is compatible with the given hints' media type (if any) is found
   */
  public <T> BodyPublisher publisherOf(T value, Hints hints) {
    return publisherOf(value, TypeRef.ofRuntimeType(value), hints);
  }

  /**
   * Returns a {@link BodyPublisher} that encodes the given object into a request body with respect
   * to the given {@code TypeRef}.
   *
   * @throws UnsupportedOperationException if no encoder that supports encoding the given object
   *     type or is compatible with the given hints' media type (if any) is found
   * @throws IllegalArgumentException if the given object is not an instance of the given {@code
   *     TypeRef}'s raw type
   */
  public <T> BodyPublisher publisherOf(T value, TypeRef<T> typeRef, Hints hints) {
    requireArgument(
        typeRef.rawType().isInstance(requireNonNull(value)),
        "Expected %s to be an instance of %s",
        value,
        typeRef);
    return lookupEncoder(typeRef, hints)
        .orElseThrow(() -> unsupportedConversion("from", typeRef, hints))
        .toBody(value, typeRef, hints);
  }

  /**
   * Returns a {@link BodySubscriber} that decodes the response body into an object of the given
   * type.
   *
   * @throws UnsupportedOperationException if no decoder that supports decoding to the given type or
   *     is compatible with the given hints' media type (if any) is found
   */
  public <T> BodySubscriber<T> subscriberOf(TypeRef<T> typeRef, Hints hints) {
    return lookupDecoder(typeRef, hints)
        .orElseThrow(() -> unsupportedConversion("to", typeRef, hints))
        .toObject(typeRef, hints);
  }

  /**
   * Returns a {@link BodySubscriber} that lazily decodes the response body into an object of the
   * given type.
   *
   * @throws UnsupportedOperationException if no decoder that supports decoding to the given type or
   *     is compatible with the given hints' media type (if any) is found
   */
  public <T> BodySubscriber<Supplier<T>> deferredSubscriberOf(TypeRef<T> typeRef, Hints hints) {
    return lookupDecoder(typeRef, hints)
        .orElseThrow(() -> unsupportedConversion("to", typeRef, hints))
        .toDeferredObject(typeRef, hints);
  }

  /**
   * Returns a {@link BodyHandler} that decodes the response body into an object of the given type.
   * The decoder is selected based on the response body's media type as specified by the {@code
   * Content-Type} header. If no such header exists, any decoder that supports decoding to the given
   * type is selected.
   *
   * @throws UnsupportedOperationException if no decoder that supports decoding to the given type is
   *     found
   */
  public <T> BodyHandler<T> handlerOf(TypeRef<T> typeRef, Hints hints) {
    requireDecoderSupport(decoders, typeRef);
    return responseInfo -> subscriberOf(typeRef, hints.mutate().forDecoder(responseInfo).build());
  }

  /**
   * Returns a {@code BodyHandler} that lazily decodes the response body into an object of the given
   * type. The decoder is selected based on the response body's media type as specified by the
   * {@code Content-Type} header. If no such header exists, any decoder that supports decoding to
   * the given type is selected.
   *
   * @throws UnsupportedOperationException if no decoder that supports decoding to the given type is
   *     found
   */
  public <T> BodyHandler<Supplier<T>> deferredHandlerOf(TypeRef<T> typeRef, Hints hints) {
    requireDecoderSupport(decoders, typeRef);
    return responseInfo ->
        deferredSubscriberOf(typeRef, hints.mutate().forDecoder(responseInfo).build());
  }

  Optional<Encoder> lookupEncoder(TypeRef<?> typeRef, Hints hints) {
    return lookup(encoders, typeRef, hints);
  }

  Optional<Decoder> lookupDecoder(TypeRef<?> typeRef, Hints hints) {
    return lookup(decoders, typeRef, hints);
  }

  @Override
  public String toString() {
    return Utils.toStringIdentityPrefix(this)
        + "[encoders="
        + encoders
        + ", decoders="
        + decoders
        + "]";
  }

  private static <T extends BodyAdapter> Optional<T> lookup(
      List<T> adapters, TypeRef<?> typeRef, Hints hints) {
    requireNonNull(typeRef);
    requireNonNull(hints);
    return adapters.stream()
        .filter(
            encoder ->
                encoder.supportsType(typeRef) && encoder.isCompatibleWith(hints.mediaTypeOrAny()))
        .findFirst();
  }

  private static void requireDecoderSupport(List<Decoder> decoders, TypeRef<?> typeRef) {
    requireNonNull(typeRef);
    if (decoders.stream().noneMatch(decoder -> decoder.supportsType(typeRef))) {
      throw unsupportedConversion("to", typeRef, Hints.empty());
    }
  }

  private static UnsupportedOperationException unsupportedConversion(
      String preposition, TypeRef<?> typeRef, Hints hints) {
    return new UnsupportedOperationException(
        "Unsupported conversion "
            + preposition
            + " an object of type <"
            + typeRef
            + "> with <"
            + hints
            + ">");
  }

  public static AdapterCodec installed() {
    var installedCodec = lazyInstalledCodec;
    if (installedCodec == null) {
      installedCodec = new AdapterCodec(installedEncoders.get(), installedDecoders.get());
      lazyInstalledCodec = installedCodec;
    }
    return installedCodec;
  }

  /** Returns a new {@code AdapterCodec.Builder}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** A builder of {@code AdapterCodec} instances. */
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
    public Builder basic() {
      return basicEncoder().basicDecoder();
    }

    /** Returns a new {@code AdapterCodec} containing the encoders and decoders added so far. */
    public AdapterCodec build() {
      return new AdapterCodec(encoders, decoders);
    }
  }
}
