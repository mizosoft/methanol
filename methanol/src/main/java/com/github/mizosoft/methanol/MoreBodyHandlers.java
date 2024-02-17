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

import static com.github.mizosoft.methanol.internal.Utils.requirePositiveDuration;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.BodyAdapter.Decoder;
import com.github.mizosoft.methanol.internal.concurrent.Delayer;
import com.github.mizosoft.methanol.internal.extensions.ImmutableResponseInfo;
import java.io.Reader;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Factory for additional {@link BodyHandler} implementations. */
public class MoreBodyHandlers {
  private MoreBodyHandlers() {}

  /**
   * Returns a {@code BodyHandler} that returns a subscriber obtained from {@link
   * MoreBodySubscribers#fromAsyncSubscriber(Subscriber, Function)}.
   */
  public static <T, S extends Subscriber<? super List<ByteBuffer>>>
      BodyHandler<T> fromAsyncSubscriber(
          S downstream, Function<? super S, ? extends CompletionStage<T>> asyncFinisher) {
    requireNonNull(downstream);
    requireNonNull(asyncFinisher);
    return responseInfo -> MoreBodySubscribers.fromAsyncSubscriber(downstream, asyncFinisher);
  }

  /**
   * Returns a {@code BodyHandler} that returns a subscriber obtained from {@link
   * MoreBodySubscribers#withReadTimeout(BodySubscriber, Duration)}.
   */
  public static <T> BodyHandler<T> withReadTimeout(BodyHandler<T> delegate, Duration timeout) {
    return withReadTimeout(delegate, timeout, Delayer.systemDelayer());
  }

  /**
   * Returns a {@code BodyHandler} that returns a subscriber obtained from {@link
   * MoreBodySubscribers#withReadTimeout(BodySubscriber, Duration, ScheduledExecutorService)}.
   */
  public static <T> BodyHandler<T> withReadTimeout(
      BodyHandler<T> delegate, Duration timeout, ScheduledExecutorService scheduler) {
    return withReadTimeout(delegate, timeout, Delayer.of(scheduler));
  }

  static <T> BodyHandler<T> withReadTimeout(
      BodyHandler<T> delegate, Duration timeout, Delayer delayer) {
    requireNonNull(delegate);
    requirePositiveDuration(timeout);
    requireNonNull(delayer);
    return responseInfo ->
        MoreBodySubscribers.withReadTimeout(delegate.apply(responseInfo), timeout, delayer);
  }

  /**
   * Returns a {@code BodyHandler} that returns a subscriber obtained from {@link
   * MoreBodySubscribers#ofByteChannel()}. The response is completed as soon as headers are
   * received.
   */
  public static BodyHandler<ReadableByteChannel> ofByteChannel() {
    return responseInfo -> MoreBodySubscribers.ofByteChannel();
  }

  /**
   * Returns a {@code BodyHandler} that returns a subscriber obtained form {@link
   * MoreBodySubscribers#ofReader(Charset)} using the charset specified by the {@code Content-Type}
   * header, or {@code UTF-8} if not present. The response is completed as soon as headers are
   * received.
   */
  public static BodyHandler<Reader> ofReader() {
    return responseInfo -> MoreBodySubscribers.ofReader(charsetOrUtf8(responseInfo.headers()));
  }

  /**
   * Returns a {@code BodyHandler} that returns a subscriber obtained from {@link
   * MoreBodySubscribers#ofReader(Charset)} using the given charset. The response is completed as
   * soon as headers are received.
   */
  public static BodyHandler<Reader> ofReader(Charset charset) {
    requireNonNull(charset);
    return responseInfo -> MoreBodySubscribers.ofReader(charset);
  }

  /**
   * Returns a {@code BodyHandler} that returns a subscriber obtained from {@link
   * MoreBodySubscribers#ofObject(TypeRef, MediaType)}. The media type is parsed from the {@code
   * Content-Type} response header if present.
   *
   * @throws UnsupportedOperationException if no {@link Decoder} that supports the given type is
   *     installed
   */
  public static <T> BodyHandler<T> ofObject(Class<T> type) {
    return ofObject(TypeRef.of(type));
  }

  /**
   * Returns a {@code BodyHandler} that returns a subscriber obtained from {@link
   * MoreBodySubscribers#ofObject(TypeRef, MediaType)}. The media type is parsed from the {@code
   * Content-Type} response header if present.
   *
   * @throws UnsupportedOperationException if no {@link Decoder} that supports the given type is
   *     installed
   */
  public static <T> BodyHandler<T> ofObject(TypeRef<T> type) {
    requireSupport(type);
    return responseInfo ->
        MoreBodySubscribers.ofObject(type, mediaTypeOrNull(responseInfo.headers()));
  }

  /**
   * Returns a {@code BodyHandler} that returns a subscriber obtained from {@link
   * MoreBodySubscribers#ofDeferredObject(TypeRef, MediaType)}. The media type is parsed from the
   * {@code Content-Type} response header if present. The response is completed as soon as headers
   * are received.
   *
   * @throws UnsupportedOperationException if no {@link Decoder} that supports the given type is
   *     installed
   */
  public static <T> BodyHandler<Supplier<T>> ofDeferredObject(Class<T> type) {
    return ofDeferredObject(TypeRef.of(type));
  }

  /**
   * Returns a {@code BodyHandler} that returns a subscriber obtained from {@link
   * MoreBodySubscribers#ofDeferredObject(TypeRef, MediaType)}. The media type is parsed from the
   * {@code Content-Type} response header if present. The response is completed as soon as headers
   * are received.
   *
   * @throws UnsupportedOperationException if no {@link Decoder} that supports the given type is
   *     installed
   */
  public static <T> BodyHandler<Supplier<T>> ofDeferredObject(TypeRef<T> type) {
    requireSupport(type);
    return responseInfo ->
        MoreBodySubscribers.ofDeferredObject(type, mediaTypeOrNull(responseInfo.headers()));
  }

  /**
   * Returns a {@code BodyHandler} that decompresses the response body for the given handler using a
   * {@link BodyDecoder}. The decoder is created using the factory corresponding to the response's
   * {@code Content-Encoding} header, throwing an {@code UnsupportedOperationException} if no such
   * factory is installed. If the header is not present, the result of the given handler is returned
   * as is.
   *
   * <p>If the response is compressed, the {@code Content-Encoding} and {@code Content-Length}
   * headers are stripped before forwarding the {@code ResponseInfo} to the given handler.
   */
  public static <T> BodyHandler<T> decoding(BodyHandler<T> downstreamBodyHandler) {
    return new DecodingHandler<>(downstreamBodyHandler, null);
  }

  /**
   * Returns a {@code BodyHandler} that decompresses the response body for the given handler using a
   * {@link BodyDecoder}. The decoder is created using the factory corresponding to the response's
   * {@code Content-Encoding} header, throwing an {@code UnsupportedOperationException} if no such
   * factory is installed. If the header is not present, the result of the given handler is returned
   * as is.
   *
   * <p>If the response is compressed, the {@code Content-Encoding} and {@code Content-Length}
   * headers are stripped before forwarding the {@code ResponseInfo} to the given handler.
   */
  public static <T> BodyHandler<T> decoding(
      BodyHandler<T> downstreamHandler, @Nullable Executor executor) {
    return new DecodingHandler<>(downstreamHandler, executor);
  }

  private static Charset charsetOrUtf8(HttpHeaders headers) {
    return headers
        .firstValue("Content-Type")
        .map(contentType -> MediaType.parse(contentType).charsetOrDefault(UTF_8))
        .orElse(UTF_8);
  }

  private static void requireSupport(TypeRef<?> type) {
    if (Decoder.installed().stream().noneMatch(decoder -> decoder.supportsType(type))) {
      throw new UnsupportedOperationException(
          "unsupported conversion to an object of type <" + type + ">");
    }
  }

  private static @Nullable MediaType mediaTypeOrNull(HttpHeaders headers) {
    return headers.firstValue("Content-Type").map(MediaType::parse).orElse(null);
  }

  private static final class DecodingHandler<T> implements BodyHandler<T> {
    private final BodyHandler<T> downstreamBodyHandler;
    private final @Nullable Executor executor;

    DecodingHandler(BodyHandler<T> downstreamBodyHandler, @Nullable Executor executor) {
      this.downstreamBodyHandler = requireNonNull(downstreamBodyHandler);
      this.executor = executor;
    }

    @Override
    public BodySubscriber<T> apply(ResponseInfo responseInfo) {
      return responseInfo
          .headers()
          .firstValue("Content-Encoding")
          .map(encoding -> wrapDownstream(encoding, responseInfo))
          .orElseGet(() -> downstreamBodyHandler.apply(responseInfo));
    }

    private BodySubscriber<T> wrapDownstream(String encoding, ResponseInfo responseInfo) {
      var factory =
          BodyDecoder.Factory.getFactory(encoding)
              .orElseThrow(() -> new UnsupportedOperationException("unsupported encoding"));

      // Don't pass on outdated headers to downstream.
      var strippedHeaders =
          HttpHeaders.of(
              responseInfo.headers().map(),
              (name, value) ->
                  !"Content-Encoding".equalsIgnoreCase(name)
                      && !"Content-Length".equalsIgnoreCase(name));

      var downstreamSubscriber =
          downstreamBodyHandler.apply(
              new ImmutableResponseInfo(
                  responseInfo.statusCode(), strippedHeaders, responseInfo.version()));
      return executor != null
          ? factory.create(downstreamSubscriber, executor)
          : factory.create(downstreamSubscriber);
    }
  }
}
