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

import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.MoreBodySubscribers;
import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow.Publisher;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.checkerframework.checker.nullness.qual.Nullable;

/** An adapter for basic types (e.g. {@code String}, {@code byte[]}, etc.). */
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

  private static final class BasicEncoder extends BasicAdapter implements Encoder {
    static final BasicEncoder INSTANCE = new BasicEncoder();

    private static final Map<Class<?>, BiFunction<?, MediaType, BodyPublisher>> ENCODERS;

    static {
      var encoders = new LinkedHashMap<Class<?>, BiFunction<?, MediaType, BodyPublisher>>();
      addEncoder(
          encoders,
          CharSequence.class,
          (value, mediaType) ->
              BodyPublishers.ofString(value.toString(), mediaType.charsetOrDefault(UTF_8)));
      addEncoder(encoders, InputStream.class, (in, __) -> BodyPublishers.ofInputStream(() -> in));
      addEncoder(encoders, byte[].class, (bytes, __) -> BodyPublishers.ofByteArray(bytes));
      addEncoder(encoders, ByteBuffer.class, (buffer, __) -> new ByteBufferBodyPublisher(buffer));
      addEncoder(encoders, Path.class, (file, __) -> encodeFile(file));
      addEncoder(encoders, Supplier.class, (supplier, __) -> encodeInputStreamSupplier(supplier));
      addEncoder(encoders, Iterable.class, (iterable, __) -> encodeByteArrayIterable(iterable));
      ENCODERS = Collections.unmodifiableMap(encoders);
    }

    private static <T> void addEncoder(
        Map<Class<?>, BiFunction<?, MediaType, BodyPublisher>> encoders,
        Class<T> type,
        BiFunction<T, MediaType, BodyPublisher> encoder) {
      encoders.put(type, encoder);
    }

    private BasicEncoder() {}

    @Override
    public boolean supportsType(TypeRef<?> typeRef) {
      return ENCODERS.keySet().stream().anyMatch(type -> type.isAssignableFrom(typeRef.rawType()));
    }

    private static BodyPublisher encodeFile(Path path) {
      try {
        return BodyPublishers.ofFile(path);
      } catch (FileNotFoundException e) {
        throw new UncheckedIOException(e);
      }
    }

    private static BodyPublisher encodeInputStreamSupplier(Supplier<?> supplier) {
      requireNonNull(supplier);
      return BodyPublishers.fromPublisher(
          subscriber -> {
            requireNonNull(subscriber);
            InputStream in;
            try {
              var potentialIn = supplier.get();
              if (!(potentialIn instanceof InputStream)) {
                throw new UnsupportedOperationException(
                    "Expected an InputStream, got: " + potentialIn);
              }
              in = (InputStream) potentialIn;
            } catch (Throwable t) {
              try {
                subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
              } catch (Throwable onSubscribeT) {
                t.addSuppressed(onSubscribeT);
              } finally {
                subscriber.onError(t);
              }
              return;
            }

            var lambdaIn = in;
            BodyPublishers.ofInputStream(() -> lambdaIn).subscribe(subscriber);
          });
    }

    private static BodyPublisher encodeByteArrayIterable(Iterable<?> iterable) {
      return BodyPublishers.ofByteArrays(
          () ->
              new Iterator<>() {
                private final Iterator<?> iterator = iterable.iterator();

                @Override
                public boolean hasNext() {
                  return iterator.hasNext();
                }

                @Override
                public byte[] next() {
                  var next = iterator.next();
                  if (!(next instanceof byte[])) {
                    throw new UnsupportedOperationException("Expected a byte[], got: " + next);
                  }
                  return (byte[]) next;
                }
              });
    }

    @SuppressWarnings("unchecked")
    private static <T> BiFunction<T, MediaType, BodyPublisher> encoderOf(Class<T> type) {
      for (var entry : ENCODERS.entrySet()) {
        if (entry.getKey().isAssignableFrom(type)) {
          return (BiFunction<T, MediaType, BodyPublisher>) entry.getValue();
        }
      }
      throw new UnsupportedOperationException(
          "Unsupported conversion from an object of type <" + type + ">");
    }

    @SuppressWarnings("unchecked")
    @Override
    public BodyPublisher toBody(Object object, @Nullable MediaType mediaType) {
      requireSupport(object.getClass());
      requireCompatibleOrNull(mediaType);
      return attachMediaType(
          encoderOf((Class<Object>) object.getClass())
              .apply(object, mediaType != null ? mediaType : MediaType.ANY),
          mediaType);
    }
  }

  private static final class BasicDecoder extends BasicAdapter implements Decoder {
    static final BasicDecoder INSTANCE = new BasicDecoder();

    private static final Map<TypeRef<?>, Function<MediaType, BodySubscriber<?>>> DECODERS;

    static {
      var decoders = new LinkedHashMap<TypeRef<?>, Function<MediaType, BodySubscriber<?>>>();
      decoders.put(
          new TypeRef<String>() {},
          mediaType -> BodySubscribers.ofString(mediaType.charsetOrDefault(UTF_8)));
      decoders.put(new TypeRef<InputStream>() {}, __ -> BodySubscribers.ofInputStream());
      decoders.put(
          new TypeRef<Reader>() {},
          mediaType -> MoreBodySubscribers.ofReader(mediaType.charsetOrDefault(UTF_8)));
      decoders.put(new TypeRef<byte[]>() {}, __ -> BodySubscribers.ofByteArray());
      decoders.put(
          new TypeRef<ByteBuffer>() {},
          __ -> BodySubscribers.mapping(BodySubscribers.ofByteArray(), ByteBuffer::wrap));
      decoders.put(
          new TypeRef<Stream<String>>() {},
          mediaType -> BodySubscribers.ofLines(mediaType.charsetOrDefault(UTF_8)));
      decoders.put(
          new TypeRef<Publisher<List<ByteBuffer>>>() {}, __ -> BodySubscribers.ofPublisher());
      decoders.put(
          new TypeRef<Stream<String>>() {},
          mediaType -> BodySubscribers.ofLines(mediaType.charsetOrDefault(UTF_8)));
      decoders.put(new TypeRef<Void>() {}, __ -> BodySubscribers.discarding());
      DECODERS = Collections.unmodifiableMap(decoders);
    }

    private BasicDecoder() {}

    @Override
    public boolean supportsType(TypeRef<?> type) {
      return DECODERS.containsKey(type);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> BodySubscriber<T> toObject(TypeRef<T> typeRef, @Nullable MediaType mediaType) {
      requireSupport(typeRef);
      requireCompatibleOrNull(mediaType);
      return (BodySubscriber<T>)
          castNonNull(DECODERS.get(typeRef)).apply(mediaType != null ? mediaType : MediaType.ANY);
    }
  }
}
