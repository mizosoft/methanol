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

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.BodyAdapter.Decoder;
import com.github.mizosoft.methanol.internal.extensions.ImmutableResponseInfo;
import java.io.Reader;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Provides additional {@link java.net.http.HttpResponse.BodyHandler} implementations. */
public class MoreBodyHandlers {

  private MoreBodyHandlers() {} // non-instantiable

  /**
   * Returns a {@code BodyHandler} that handles the response with the subscriber returned by {@link
   * MoreBodySubscribers#fromAsyncSubscriber(Subscriber, Function)}.
   *
   * @param downstream the receiver of the response body
   * @param asyncFinisher a function that maps the subscriber to an async task upon which the body
   *     completion is dependant
   * @param <T> the type of the body
   * @param <S> the type of the subscriber
   */
  public static <T, S extends Subscriber<? super List<ByteBuffer>>>
      BodyHandler<T> fromAsyncSubscriber(
          S downstream, Function<? super S, ? extends CompletionStage<T>> asyncFinisher) {
    requireNonNull(downstream, "downstream");
    requireNonNull(asyncFinisher, "asyncFinisher");
    return info -> MoreBodySubscribers.fromAsyncSubscriber(downstream, asyncFinisher);
  }

  /**
   * Returns a {@code BodyHandler} that handles the response with the subscriber returned by {@link
   * MoreBodySubscribers#withReadTimeout(BodySubscriber, Duration)}.
   */
  public static <T> BodyHandler<T> withReadTimeout(BodyHandler<T> baseHandler, Duration timeout) {
    requireNonNull(baseHandler, "baseHandler");
    requireNonNull(timeout, "timeout");
    return info -> MoreBodySubscribers.withReadTimeout(baseHandler.apply(info), timeout);
  }

  /**
   * Returns a {@code BodyHandler} that handles the response with the subscriber returned by {@link
   * MoreBodySubscribers#withReadTimeout(BodySubscriber, Duration, ScheduledExecutorService)}.
   */
  public static <T> BodyHandler<T> withReadTimeout(
      BodyHandler<T> baseHandler, Duration timeout, ScheduledExecutorService scheduler) {
    requireNonNull(baseHandler, "baseHandler");
    requireNonNull(timeout, "timeout");
    requireNonNull(timeout, "scheduler");
    return info -> MoreBodySubscribers.withReadTimeout(baseHandler.apply(info), timeout, scheduler);
  }

  /**
   * Returns a {@code BodyHandler} of {@code ReadableByteChannel} as specified by {@link
   * MoreBodySubscribers#ofByteChannel()}. A response with such a handler is completed after the
   * response headers are received.
   */
  public static BodyHandler<ReadableByteChannel> ofByteChannel() {
    return info -> MoreBodySubscribers.ofByteChannel();
  }

  /**
   * Returns a {@code BodyHandler} of {@code Reader} as specified by {@link
   * MoreBodySubscribers#ofReader(Charset)} using the charset specified by the {@code Content-Type}
   * response header for decoding the response. A response with such a handler is completed after
   * the response headers are received.
   */
  public static BodyHandler<Reader> ofReader() {
    return info -> MoreBodySubscribers.ofReader(getCharsetOrUtf8(info.headers()));
  }

  /**
   * Returns a {@code BodyHandler} of {@code Reader} as specified by {@link
   * MoreBodySubscribers#ofReader(Charset)} using the given charset for decoding the response. A
   * response with such a subscriber is completed after the response headers are received.
   *
   * @param charset the charset used for decoding the response
   */
  public static BodyHandler<Reader> ofReader(Charset charset) {
    requireNonNull(charset);
    return info -> MoreBodySubscribers.ofReader(charset);
  }

  /**
   * Returns a {@code BodyHandler} of {@code T} as specified by {@link
   * MoreBodySubscribers#ofObject(TypeRef, MediaType)}. The media type will be inferred from the
   * {@code Content-Type} response header.
   *
   * @param type the raw type of {@code T}
   * @param <T> the response body type
   * @throws UnsupportedOperationException if no {@code Decoder} that supports the given type is
   *     installed
   */
  public static <T> BodyHandler<T> ofObject(Class<T> type) {
    return ofObject(TypeRef.from(type));
  }

  /**
   * Returns a {@code BodyHandler} of {@code T} as specified by {@link
   * MoreBodySubscribers#ofObject(TypeRef, MediaType)}. The media type will be inferred from the
   * {@code Content-Type} response header.
   *
   * @param type a {@code TypeRef} representing {@code T}
   * @param <T> the response body type
   * @throws UnsupportedOperationException if no {@code Decoder} that supports the given type is
   *     installed
   */
  public static <T> BodyHandler<T> ofObject(TypeRef<T> type) {
    requireSupport(type);
    return info -> MoreBodySubscribers.ofObject(type, mediaTypeOrNull(info.headers()));
  }

  /**
   * Returns a {@code BodyHandler} of {@code Supplier<T>} as specified by {@link
   * MoreBodySubscribers#ofDeferredObject(TypeRef, MediaType)}. The media type will be inferred from
   * the {@code Content-Type} response header.
   *
   * @param type the raw type of {@code T}
   * @param <T> the response body type
   * @throws UnsupportedOperationException if no {@code Decoder} that supports the given type is
   *     installed
   */
  public static <T> BodyHandler<Supplier<T>> ofDeferredObject(Class<T> type) {
    return ofDeferredObject(TypeRef.from(type));
  }

  /**
   * Returns a {@code BodyHandler} of {@code Supplier<T>} as specified by {@link
   * MoreBodySubscribers#ofDeferredObject(TypeRef, MediaType)}. The media type will be inferred from
   * the {@code Content-Type} response header.
   *
   * @param type a {@code TypeRef} representing {@code T}
   * @param <T> the response body type
   * @throws UnsupportedOperationException if no {@code Decoder} that supports the given type is
   *     installed
   */
  public static <T> BodyHandler<Supplier<T>> ofDeferredObject(TypeRef<T> type) {
    requireSupport(type);
    return info -> MoreBodySubscribers.ofDeferredObject(type, mediaTypeOrNull(info.headers()));
  }

  /**
   * Returns a {@code BodyHandler} that wraps the result of the given handler in a {@link
   * BodyDecoder} if required. The decoder is created using the factory corresponding to the value
   * of the {@code Content-Type} header, throwing {@code UnsupportedOperationException} if no such
   * factory is registered. If the header is not present, the result of the given handler is
   * returned directly.
   *
   * <p>The {@code Content-Encoding} and {@code Content-Length} headers are removed when invoking
   * the given handler to avoid recursive decompression attempts or using the wrong body length.
   *
   * @param downstreamHandler the handler returning the downstream
   * @param <T> the subscriber's body type
   */
  public static <T> BodyHandler<T> decoding(BodyHandler<T> downstreamHandler) {
    requireNonNull(downstreamHandler);
    return new DecodingHandler<>(downstreamHandler, null);
  }

  /**
   * Returns a {@code BodyHandler} that wraps the result of the given handler in a {@link
   * BodyDecoder} with the given executor if required. The decoder is created using the factory
   * corresponding to the value of the {@code Content-Type} header, throwing {@code
   * UnsupportedOperationException} if no such factory is registered. If the header is not present,
   * the result of the given handler is returned directly.
   *
   * <p>The {@code Content-Encoding} and {@code Content-Length} headers are removed when invoking
   * the given handler to avoid recursive decompression attempts or using the wrong body length.
   *
   * @param downstreamHandler the handler returning the downstream
   * @param executor the executor used to supply downstream items
   * @param <T> the subscriber's body type
   */
  public static <T> BodyHandler<T> decoding(BodyHandler<T> downstreamHandler, Executor executor) {
    requireNonNull(downstreamHandler, "downstreamHandler");
    requireNonNull(executor, "executor");
    return new DecodingHandler<>(downstreamHandler, executor);
  }

  private static Charset getCharsetOrUtf8(HttpHeaders headers) {
    return headers
        .firstValue("Content-Type")
        .map(s -> MediaType.parse(s).charsetOrDefault(StandardCharsets.UTF_8))
        .orElse(StandardCharsets.UTF_8);
  }

  // Require that at least an adapter exists for the given type
  // (the media type cannot be known until the headers arrive)
  private static void requireSupport(TypeRef<?> type) {
    if (Decoder.installed().stream().noneMatch(d -> d.supportsType(type))) {
      throw new UnsupportedOperationException(
          "unsupported conversion to an object of type <" + type + ">");
    }
  }

  private static @Nullable MediaType mediaTypeOrNull(HttpHeaders headers) {
    return headers.firstValue("Content-Type").map(MediaType::parse).orElse(null);
  }

  private static final class DecodingHandler<T> implements BodyHandler<T> {

    private final BodyHandler<T> downstreamHandler;
    private final @Nullable Executor executor;

    DecodingHandler(BodyHandler<T> downstreamHandler, @Nullable Executor executor) {
      this.downstreamHandler = downstreamHandler;
      this.executor = executor;
    }

    @Override
    public BodySubscriber<T> apply(ResponseInfo info) {
      Optional<String> encHeader = info.headers().firstValue("Content-Encoding");
      if (encHeader.isEmpty()) {
        return downstreamHandler.apply(info); // No decompression needed
      }
      String enc = encHeader.get();
      BodyDecoder.Factory factory =
          BodyDecoder.Factory.getFactory(enc)
              .orElseThrow(() -> new UnsupportedOperationException("unsupported encoding: " + enc));
      HttpHeaders headersCopy =
          HttpHeaders.of(
              info.headers().map(),
              (n, v) ->
                  !"Content-Encoding".equalsIgnoreCase(n) && !"Content-Length".equalsIgnoreCase(n));
      BodySubscriber<T> downstream =
          downstreamHandler.apply(
              new ImmutableResponseInfo(info.statusCode(), headersCopy, info.version()));
      return executor != null ? factory.create(downstream, executor) : factory.create(downstream);
    }
  }
}
