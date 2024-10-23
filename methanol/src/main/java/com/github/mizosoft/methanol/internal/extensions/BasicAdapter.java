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

package com.github.mizosoft.methanol.internal.extensions;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.MoreBodySubscribers;
import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;

/** An adapter for basic types (e.g., {@code String}, {@code byte[]}). */
public abstract class BasicAdapter extends AbstractBodyAdapter {
  BasicAdapter() {
    super(MediaType.ANY);
  }

  public static Encoder encoder() {
    return BasicEncoder.INSTANCE;
  }

  public static Decoder decoder() {
    return BasicDecoder.INSTANCE;
  }

  private static final class BasicEncoder extends BasicAdapter implements BaseEncoder {
    static final BasicEncoder INSTANCE = new BasicEncoder();

    private static final Map<TypeRef<?>, BiFunction<?, ? super Charset, ? extends BodyPublisher>>
        ENCODERS;

    static {
      var encoders =
          new LinkedHashMap<TypeRef<?>, BiFunction<?, ? super Charset, ? extends BodyPublisher>>();
      putEncoder(
          encoders,
          CharSequence.class,
          (value, charset) -> BodyPublishers.ofString(value.toString(), charset));
      putEncoder(encoders, InputStream.class, (in, __) -> BodyPublishers.ofInputStream(() -> in));
      putEncoder(encoders, byte[].class, (bytes, __) -> BodyPublishers.ofByteArray(bytes));
      putEncoder(encoders, ByteBuffer.class, (buffer, __) -> new ByteBufferBodyPublisher(buffer));
      putEncoder(encoders, Path.class, (file, __) -> encodeFile(file));
      putEncoder(
          encoders,
          new TypeRef<Supplier<? extends InputStream>>() {},
          (supplier, __) -> BodyPublishers.ofInputStream(supplier));
      putEncoder(
          encoders,
          new TypeRef<Iterable<byte[]>>() {},
          (bytes, __) -> BodyPublishers.ofByteArrays(bytes));
      ENCODERS = Collections.unmodifiableMap(encoders);
    }

    private static <T> void putEncoder(
        Map<TypeRef<?>, BiFunction<?, ? super Charset, ? extends BodyPublisher>> encoders,
        Class<T> type,
        BiFunction<? super T, ? super Charset, ? extends BodyPublisher> encoder) {
      encoders.put(TypeRef.of(type), encoder);
    }

    private static <T> void putEncoder(
        Map<TypeRef<?>, BiFunction<?, ? super Charset, ? extends BodyPublisher>> encoders,
        TypeRef<T> typeRef,
        BiFunction<? super T, ? super Charset, ? extends BodyPublisher> encoder) {
      encoders.put(typeRef, encoder);
    }

    private BasicEncoder() {}

    @Override
    public boolean supportsType(TypeRef<?> typeRef) {
      return encoderOf(typeRef) != null;
    }

    private static BodyPublisher encodeFile(Path path) {
      try {
        return BodyPublishers.ofFile(path);
      } catch (FileNotFoundException e) {
        throw new UncheckedIOException(e);
      }
    }

    @SuppressWarnings("unchecked")
    private static <T> @Nullable BiFunction<T, Charset, BodyPublisher> encoderOf(TypeRef<T> right) {
      for (var entry : ENCODERS.entrySet()) {
        var left = entry.getKey();
        if (left.rawType().isAssignableFrom(right.rawType())) {
          if (left.isRawType()) {
            return (BiFunction<T, Charset, BodyPublisher>) entry.getValue();
          }

          // If left has generics we only accept right if it compares covariantly. Note that this
          // is an ad-hoc comparison that only works for current usage, where encodeable generic
          // supertypes have only one non-generic argument.
          assert left.isParameterizedType();
          if (right
              .resolveSupertype(left.rawType())
              .typeArgumentAt(0)
              .flatMap(
                  rightArg ->
                      left.typeArgumentAt(0)
                          .map(leftArg -> leftArg.rawType().isAssignableFrom(rightArg.rawType())))
              .orElse(false)) {
            return (BiFunction<T, Charset, BodyPublisher>) entry.getValue();
          }
        }
      }
      return null;
    }

    @Override
    public <T> BodyPublisher toBody(T value, TypeRef<T> typeRef, Hints hints) {
      requireCompatibleOrNull(hints.mediaTypeOrAny());
      var encoder = encoderOf(typeRef);
      if (encoder == null) {
        throw new UnsupportedOperationException(
            "Unsupported conversion from an object of type <" + typeRef + ">");
      }
      return attachMediaType(
          encoder.apply(value, hints.mediaTypeOrAny().charsetOrUtf8()), hints.mediaTypeOrAny());
    }
  }

  private static final class BasicDecoder extends BasicAdapter implements BaseDecoder {
    static final BasicDecoder INSTANCE = new BasicDecoder();

    private static final Map<TypeRef<?>, Function<? super Charset, ? extends BodySubscriber<?>>>
        DECODERS;

    static {
      var decoders =
          new LinkedHashMap<TypeRef<?>, Function<? super Charset, ? extends BodySubscriber<?>>>();
      putDecoder(decoders, String.class, BodySubscribers::ofString);
      putDecoder(decoders, InputStream.class, __ -> BodySubscribers.ofInputStream());
      putDecoder(decoders, Reader.class, MoreBodySubscribers::ofReader);
      putDecoder(decoders, byte[].class, __ -> BodySubscribers.ofByteArray());
      putDecoder(
          decoders,
          ByteBuffer.class,
          __ -> BodySubscribers.mapping(BodySubscribers.ofByteArray(), ByteBuffer::wrap));
      putDecoder(decoders, new TypeRef<>() {}, BodySubscribers::ofLines);
      putDecoder(decoders, new TypeRef<>() {}, __ -> new PublisherBodySubscriber());
      putDecoder(decoders, Void.class, __ -> BodySubscribers.discarding());
      DECODERS = Collections.unmodifiableMap(decoders);
    }

    private static <T> void putDecoder(
        Map<TypeRef<?>, Function<? super Charset, ? extends BodySubscriber<?>>> decoders,
        Class<T> type,
        Function<? super Charset, ? extends BodySubscriber<T>> decoder) {
      decoders.put(TypeRef.of(type), decoder);
    }

    private static <T> void putDecoder(
        Map<TypeRef<?>, Function<? super Charset, ? extends BodySubscriber<?>>> decoders,
        TypeRef<T> typeRef,
        Function<? super Charset, ? extends BodySubscriber<T>> decoder) {
      decoders.put(typeRef, decoder);
    }

    private BasicDecoder() {}

    @Override
    public boolean supportsType(TypeRef<?> typeRef) {
      return DECODERS.containsKey(typeRef);
    }

    @Override
    public <T> BodySubscriber<T> toObject(TypeRef<T> typeRef, Hints hints) {
      requireNonNull(typeRef);
      requireCompatibleOrNull(hints.mediaTypeOrAny());
      var decoder = decoderOf(typeRef);
      if (decoder == null) {
        throw new UnsupportedOperationException(
            "Unsupported conversion to an object of type <" + typeRef + ">");
      }
      return decoder.apply(hints.mediaTypeOrAny().charsetOrUtf8());
    }

    @SuppressWarnings("unchecked")
    private static <T> Function<? super Charset, ? extends BodySubscriber<T>> decoderOf(
        TypeRef<T> typeRef) {
      return (Function<? super Charset, ? extends BodySubscriber<T>>) DECODERS.get(typeRef);
    }
  }
}
