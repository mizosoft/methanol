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
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.extensions.BasicAdapter;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An object that converts objects to or from request or response bodies respectively, using a
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
  boolean supportsType(TypeRef<?> typeRef);

  /** A {@code BodyAdapter} that encodes objects into request bodies. */
  interface Encoder extends BodyAdapter {

    /**
     * Returns a {@link BodyPublisher} that encodes the given object into a request body using the
     * format specified by the given media type. If the given media type is {@code null}, the
     * encoder uses its default format parameters (e.g., charset).
     *
     * @throws UnsupportedOperationException if the given object's runtime type or the given media
     *     type is not supported
     */
    BodyPublisher toBody(Object value, @Nullable MediaType mediaType);

    /**
     * Returns a {@link BodyPublisher} that encodes the given object into a request body based on
     * the given {@link TypeRef}, using the given hints {@link Hints} for encoder-specific
     * customization.
     *
     * @implSpec The default implementation is equivalent to {@code return toBody(value,
     *     hints.mediaTypeOrAny())}. This doesn't take the given {@code TypeRef} into account.
     *     Implementations can override this method to encode the given object based on the type
     *     advertised by the caller, or customize encoding based on the given hints. The former can
     *     be different from the runtime type in case of generics and/or canonical polymorphic types
     *     that have arbitrary runtime implementations (e.g., {@code List<T>}).
     * @throws UnsupportedOperationException if any of the given type or hints' media type is not
     *     supported
     * @throws IllegalArgumentException if the given object is not an instance of the given type
     */
    default <T> BodyPublisher toBody(T value, TypeRef<T> typeRef, Hints hints) {
      return toBody(value, hints.mediaTypeOrAny());
    }

    /** Returns an immutable list containing the installed encoders. */
    static List<Encoder> installed() {
      return AdapterCodec.installed().encoders();
    }

    /**
     * Returns the encoder that supports the given object type and media type. If the given media
     * type is {@code null}, any encoder supporting the given object type is returned.
     */
    static Optional<Encoder> getEncoder(TypeRef<?> typeRef, @Nullable MediaType mediaType) {
      return AdapterCodec.installed().lookupEncoder(typeRef, Utils.hintsOf(mediaType));
    }

    /**
     * Returns the basic encoder. The basic encoder is compatible with any media type, and supports
     * encoding any subtype of:
     *
     * <ul>
     *   <li>{@code CharSequence} (encoded using {@link Hints#effectiveCharsetOrUtf8()})
     *   <li>{@code InputStream}
     *   <li>{@code byte[]}
     *   <li>{@code ByteBuffer}
     *   <li>{@code Supplier<InputStream>}
     *   <li>{@code Supplier<ByteBuffer>}
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
     * Returns a {@link BodySubscriber} that decodes the response body into an object of the given
     * type using the format specified by the given media type. If the given media type is {@code
     * null}, the decoder's default format parameters (e.g., charset) are used.
     *
     * @throws UnsupportedOperationException if any of the given type or media type is not supported
     */
    <T> BodySubscriber<T> toObject(TypeRef<T> typeRef, @Nullable MediaType mediaType);

    /**
     * Returns a {@link BodySubscriber} that decodes the response body into an object of the given
     * type using the given {@link Hints}.
     *
     * @implSpec The default implementation is equivalent to {@code return toObject(typeRef,
     *     hints.mediaTypeOrAny())}. Implementations can override this method to customize decoding
     *     based on the given hints.
     * @throws UnsupportedOperationException if any of the given type or hint's media type is not
     *     supported
     */
    default <T> BodySubscriber<T> toObject(TypeRef<T> typeRef, Hints hints) {
      return toObject(typeRef, hints.mediaTypeOrAny());
    }

    /**
     * Returns a completed {@link BodySubscriber} that lazily decodes the response body into an
     * object of the given type using the format specified by the given media type. If {@code
     * mediaType} is {@code null}, the decoder uses its default format parameters (e.g., charset).
     *
     * @implSpec The default implementation returns a subscriber completed with a supplier that
     *     blocks, uninterruptedly, on the subscriber returned by {@link #toObject(TypeRef,
     *     MediaType)}. Any exception raised while blocking is rethrown from the supplier as a
     *     {@link CompletionException}. Decoders that support reading from a blocking source should
     *     override this method to defer reading from such a source until the supplier is called.
     * @throws UnsupportedOperationException if any of the given type or media type is not supported
     */
    default <T> BodySubscriber<Supplier<T>> toDeferredObject(
        TypeRef<T> typeRef, @Nullable MediaType mediaType) {
      return MoreBodySubscribers.fromAsyncSubscriber(
          toObject(typeRef, mediaType),
          subscriber ->
              CompletableFuture.completedStage(
                  () -> subscriber.getBody().toCompletableFuture().join()));
    }

    /**
     * Returns a completed {@link BodySubscriber} that lazily decodes the response body into an
     * object of the given type using the given {@link Hints}.
     *
     * @implSpec The default implementation is equivalent to {@code return toDeferredObject(typeRef,
     *     hints.mediaTypeOrAny())}. Implementations may override this method to customize decoding
     *     based on the given hints
     * @throws UnsupportedOperationException if any of the given type or media type is not supported
     */
    default <T> BodySubscriber<Supplier<T>> toDeferredObject(TypeRef<T> typeRef, Hints hints) {
      return toDeferredObject(typeRef, hints.mediaTypeOrAny());
    }

    /** Returns an immutable list containing the installed decoders. */
    static List<Decoder> installed() {
      return AdapterCodec.installed().decoders();
    }

    /**
     * Returns a decoder that supports the given object type and media type. If the given media type
     * is {@code null}, any decoder supporting the given object type is returned.
     */
    static Optional<Decoder> getDecoder(TypeRef<?> typeRef, @Nullable MediaType mediaType) {
      return AdapterCodec.installed().lookupDecoder(typeRef, Utils.hintsOf(mediaType));
    }

    /**
     * Returns the basic decoder. The basic decoder is compatible with any media type, and supports
     * decoding:
     *
     * <ul>
     *   <li>{@code String} (decoded using {@link Hints#effectiveCharsetOrUtf8()})
     *   <li>{@code InputStream}
     *   <li>{@code Reader} (decoded using {@link Hints#effectiveCharsetOrUtf8()})
     *   <li>{@code byte[]}
     *   <li>{@code ByteBuffer}
     *   <li>{@code Stream<String>} (response body lines; decoded using {@link
     *       Hints#effectiveCharsetOrUtf8()})
     *   <li>{@code Publisher<List<ByteBuffer>>}
     *   <li>{@code Void} (discards the response body)
     * </ul>
     */
    static Decoder basic() {
      return BasicAdapter.decoder();
    }
  }

  /**
   * A collections of hints that provide additional context to customize an adapter's
   * encoding/decoding behavior. Typically, an adapter receives a {@code Hints} object that contains
   * the {@link MediaType} of the intended format as advertised by the request or response.
   * Additionally, an encoder receives a {@code Hints} objects containing the {@link #request()},
   * and a decoder receives one that contains the {@link #responseInfo()}. Hints, however, are
   * optional, and adapters should exhibit default behavior in the absence thereof (for instance, a
   * JSON adapter should use UTF-8 in the absence of a {@code MediaType} containing a charset
   * parameter).
   */
  interface Hints {

    /** Returns an immutable map of all hints. */
    Map<Class<?>, Object> toMap();

    /** Returns the {@code MediaType} hint. */
    default Optional<MediaType> mediaType() {
      return get(MediaType.class);
    }

    /** Returns either the {@code MediaType} hint or {@link MediaType#ANY} if absent. */
    default MediaType mediaTypeOrAny() {
      return mediaType().orElse(MediaType.ANY);
    }

    /**
     * Returns the effective charset to be used for encoding or decoding text formats. The effective
     * charset is either a hint with type {@link Charset}, or the {@link MediaType#charset()} of the
     * {@link #mediaType() media type hint}.
     */
    default Optional<Charset> effectiveCharset() {
      return get(Charset.class).or(() -> mediaTypeOrAny().charset());
    }

    /**
     * Returns either the {@link #effectiveCharset() effective charset}, or {@link
     * java.nio.charset.StandardCharsets#UTF_8 UTF-8} if the latter doesn't exist.
     */
    default Charset effectiveCharsetOrUtf8() {
      return effectiveCharset().orElse(UTF_8);
    }

    /** Returns the {@code HttpRequest} hint. */
    default Optional<HttpRequest> request() {
      return get(HttpRequest.class);
    }

    /** Returns the {@code ResponseInfo} hint. */
    default Optional<ResponseInfo> responseInfo() {
      return get(ResponseInfo.class);
    }

    /** Returns the hint mapped to by the given type. */
    <T> Optional<T> get(Class<T> type);

    /** Returns a new {@code Builder} containing this object's hints. */
    default Builder mutate() {
      return new Builder(this);
    }

    @Override
    boolean equals(Object obj);

    @Override
    int hashCode();

    /** Returns a new {@code Builder}. */
    static Builder newBuilder() {
      return new Builder();
    }

    /** Returns a {@code Hints} object containing no hints. */
    static Hints empty() {
      return Builder.EMPTY_HINTS;
    }

    /** Returns a {@code Hints} object containing the given media type. */
    static Hints of(MediaType mediaType) {
      return new Builder.KnownHints(requireNonNull(mediaType), null, null);
    }

    /** A builder of {@code Hint} objects. */
    class Builder {
      static final Hints EMPTY_HINTS =
          new Builder.AbstractHints() {
            @Override
            public Map<Class<?>, Object> toMap() {
              return Map.of();
            }

            @Override
            public Optional<MediaType> mediaType() {
              return Optional.empty();
            }

            @Override
            public MediaType mediaTypeOrAny() {
              return MediaType.ANY;
            }

            @Override
            public Optional<HttpRequest> request() {
              return Optional.empty();
            }

            @Override
            public Optional<ResponseInfo> responseInfo() {
              return Optional.empty();
            }

            @Override
            public <T> Optional<T> get(Class<T> type) {
              return Optional.empty();
            }

            @Override
            public Builder mutate() {
              return new Builder();
            }
          };

      private final Map<Class<?>, Object> unknownHints = new HashMap<>();
      private @Nullable MediaType mediaType;
      private @Nullable HttpRequest request;
      private @Nullable ResponseInfo responseInfo;

      Builder() {}

      Builder(Hints hints) {
        putAll(hints);
      }

      @CanIgnoreReturnValue
      Builder putAll(Hints hints) {
        if (hints instanceof KnownHints) {
          var knownHints = (KnownHints) hints;
          mediaType = knownHints.mediaType.orElse(null);
          request = knownHints.request.orElse(null);
          responseInfo = knownHints.responseInfo.orElse(null);
        } else {
          unknownHints.putAll(hints.toMap());
          mediaType = (MediaType) unknownHints.remove(MediaType.class);
          request = (HttpRequest) unknownHints.remove(HttpRequest.class);
          responseInfo = (ResponseInfo) unknownHints.remove(ResponseInfo.class);
        }
        return this;
      }

      @CanIgnoreReturnValue
      Builder putAll(Builder other) {
        unknownHints.putAll(other.unknownHints);
        mediaType = other.mediaType;
        request = other.request;
        responseInfo = other.responseInfo;
        return this;
      }

      /**
       * Adds the given request as a hint and a media type hint extracted from either the request's
       * body or {@code Content-Type} header, whichever is present first.
       */
      @CanIgnoreReturnValue
      public Builder forEncoder(HttpRequest request) {
        this.request = requireNonNull(request);
        this.mediaType =
            (request instanceof MimeAwareRequest
                    ? ((MimeAwareRequest) request).mimeBody().map(MimeBody::mediaType)
                    : Optional.<MediaType>empty())
                .or(() -> request.headers().firstValue("Content-Type").map(MediaType::parse))
                .orElse(null);
        return this;
      }

      /**
       * Adds the given {@code ResponseInfo} as a hint and a media type hint extracted from the
       * response headers if present.
       */
      @CanIgnoreReturnValue
      public Builder forDecoder(ResponseInfo responseInfo) {
        this.responseInfo = requireNonNull(responseInfo);
        this.mediaType =
            responseInfo.headers().firstValue("Content-Type").map(MediaType::parse).orElse(null);
        return this;
      }

      /** Maps the given type to the given hint. */
      @CanIgnoreReturnValue
      public <T> Builder put(Class<T> type, T value) {
        requireArgument(type.isInstance(value), "Expected %s to be an instance of %s", value, type);
        if (type == MediaType.class) {
          mediaType = (MediaType) value;
        } else if (type == HttpRequest.class) {
          request = (HttpRequest) value;
        } else if (type == ResponseInfo.class) {
          responseInfo = (ResponseInfo) value;
        } else {
          unknownHints.put(type, value);
        }
        return this;
      }

      /** Removes the hint mapped to by the given type. */
      @CanIgnoreReturnValue
      public <T> Builder remove(Class<T> type) {
        if (type == MediaType.class) {
          mediaType = null;
        } else if (type == HttpRequest.class) {
          request = null;
        } else if (type == ResponseInfo.class) {
          responseInfo = null;
        } else {
          unknownHints.remove(type);
        }
        return this;
      }

      /** Removes all hints added so far. */
      @CanIgnoreReturnValue
      public Builder removeAll() {
        unknownHints.clear();
        mediaType = null;
        request = null;
        responseInfo = null;
        return this;
      }

      /** Returns a new {@code Hints} object containing the hints added so far. */
      public Hints build() {
        if (unknownHints.isEmpty()) {
          return mediaType != null || request != null || responseInfo != null
              ? new KnownHints(mediaType, request, responseInfo)
              : EMPTY_HINTS;
        } else {
          var hints = new HashMap<>(unknownHints);
          if (mediaType != null) {
            hints.put(MediaType.class, mediaType);
          }
          if (request != null) {
            hints.put(HttpRequest.class, request);
          }
          if (responseInfo != null) {
            hints.put(ResponseInfo.class, responseInfo);
          }
          return new MapHints(hints);
        }
      }

      private abstract static class AbstractHints implements Hints {
        AbstractHints() {}

        @Override
        public String toString() {
          return "Hints" + toMap();
        }

        @Override
        public boolean equals(@Nullable Object obj) {
          if (this == obj) {
            return true;
          }
          return obj instanceof Hints && toMap().equals(((Hints) obj).toMap());
        }

        @Override
        public int hashCode() {
          return toMap().hashCode() * 31;
        }
      }

      @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
      private static final class KnownHints extends AbstractHints {
        private final Optional<MediaType> mediaType;
        private final Optional<HttpRequest> request;
        private final Optional<ResponseInfo> responseInfo;
        private @MonotonicNonNull Map<Class<?>, Object> lazyMap;

        KnownHints(
            @Nullable MediaType mediaType,
            @Nullable HttpRequest request,
            @Nullable ResponseInfo responseInfo) {
          this.mediaType = Optional.ofNullable(mediaType);
          this.request = Optional.ofNullable(request);
          this.responseInfo = Optional.ofNullable(responseInfo);
        }

        @Override
        public Map<Class<?>, Object> toMap() {
          var map = lazyMap;
          if (map == null) {
            var lambdaMap = map = new HashMap<>();
            mediaType.ifPresent(mediaType -> lambdaMap.put(MediaType.class, mediaType));
            request.ifPresent(request -> lambdaMap.put(HttpRequest.class, request));
            responseInfo.ifPresent(responseInfo -> lambdaMap.put(ResponseInfo.class, responseInfo));
            lazyMap = map;
          }
          return map;
        }

        @Override
        public <T> Optional<T> get(Class<T> type) {
          Optional<?> result;
          if (type == MediaType.class) {
            result = mediaType;
          } else if (type == HttpRequest.class) {
            result = request;
          } else if (type == ResponseInfo.class) {
            result = responseInfo;
          } else {
            result = Optional.empty();
          }
          @SuppressWarnings("unchecked")
          var castResult = (Optional<T>) result;
          return castResult;
        }

        @Override
        public Optional<MediaType> mediaType() {
          return mediaType;
        }

        @Override
        public Optional<HttpRequest> request() {
          return request;
        }

        @Override
        public Optional<ResponseInfo> responseInfo() {
          return responseInfo;
        }

        @Override
        public Optional<Charset> effectiveCharset() {
          return mediaTypeOrAny().charset();
        }

        @Override
        public Charset effectiveCharsetOrUtf8() {
          return mediaTypeOrAny().charsetOrUtf8();
        }
      }

      private static final class MapHints extends AbstractHints {
        private final Map<Class<?>, Object> hints;

        MapHints(Map<Class<?>, Object> hints) {
          this.hints = Map.copyOf(hints);
        }

        @Override
        public Map<Class<?>, Object> toMap() {
          return hints; // Map is immutable, OK to return directly.
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> Optional<T> get(Class<T> type) {
          return Optional.ofNullable((T) hints.get(type));
        }
      }
    }
  }
}
