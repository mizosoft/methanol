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
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.BodyAdapter.Decoder;
import com.github.mizosoft.methanol.internal.extensions.AsyncSubscriberAdapter;
import com.github.mizosoft.methanol.internal.extensions.ByteChannelSubscriber;
import com.github.mizosoft.methanol.internal.extensions.TimeoutSubscriber;
import java.io.Reader;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Provides additional {@link BodySubscriber} implementations. */
public class MoreBodySubscribers {

  private MoreBodySubscribers() {} // non-instantiable

  /**
   * Returns a {@code BodySubscriber} that forwards the response body to the given downstream. The
   * body's completion depends on the completion of the {@code CompletionStage} returned by the
   * given function. Unlike {@link BodySubscribers#fromSubscriber(Subscriber, Function)}, the given
   * subscriber's {@code onComplete} or {@code onError} need not be called for the body to complete.
   *
   * @param downstream the receiver of the response body
   * @param asyncFinisher a function that maps the subscriber to an async task upon which the body
   *     completion is dependant
   * @param <T> the type of the body
   * @param <S> the type of the subscriber
   */
  public static <T, S extends Subscriber<? super List<ByteBuffer>>>
      BodySubscriber<T> fromAsyncSubscriber(
          S downstream, Function<? super S, ? extends CompletionStage<T>> asyncFinisher) {
    return new AsyncSubscriberAdapter<>(downstream, asyncFinisher);
  }

  /**
   * Returns a {@code BodySubscriber<T>} that completes the given downstream with {@link
   * HttpReadTimeoutException} if a requested signal is not received within the given timeout. A
   * system-wide scheduler is used to schedule timeout events.
   *
   * @throws IllegalArgumentException if the timeout is non-positive
   */
  public static <T> BodySubscriber<T> withReadTimeout(BodySubscriber<T> base, Duration timeout) {
    requireNonNull(base, "base");
    requireNonNull(timeout, "timeout");
    requirePositiveDuration(timeout);
    return new TimeoutSubscriber<>(base, timeout, null);
  }

  /**
   * Returns a {@code BodySubscriber<T>} that completes the given downstream with {@link
   * HttpReadTimeoutException} if a requested signal is not received within the given timeout. The
   * given {@code ScheduledExecutorService} is used to schedule timeout events.
   *
   * @throws IllegalArgumentException if the timeout is non-positive
   */
  public static <T> BodySubscriber<T> withReadTimeout(
      BodySubscriber<T> base, Duration timeout, ScheduledExecutorService scheduler) {
    requireNonNull(base, "base");
    requireNonNull(timeout, "timeout");
    requireNonNull(timeout, "scheduler");
    requirePositiveDuration(timeout);
    return new TimeoutSubscriber<>(base, timeout, scheduler);
  }

  /**
   * Returns a completed {@code BodySubscriber} of {@link ReadableByteChannel} that reads the
   * response body. The channel returned by the subscriber is {@link InterruptibleChannel
   * interruptible}.
   *
   * <p>To ensure proper release of resources, the channel should be fully consumed until EOF is
   * reached. If such consumption cannot be guaranteed, either the channel should be eventually
   * closed or the thread which is blocked on reading the channel should be interrupted. Note
   * however that doing so will render the underlying connection unusable for subsequent requests.
   */
  public static BodySubscriber<ReadableByteChannel> ofByteChannel() {
    return new ByteChannelSubscriber();
  }

  /**
   * Returns a completed {@code BodySubscriber} of {@link Reader} that reads the response body as a
   * stream of characters decoded using the given charset.
   *
   * <p>To ensure proper release of resources, the reader should be fully consumed until EOF is
   * reached. If such consumption cannot be guaranteed, the reader should be eventually closed. Note
   * however that doing so will render the underlying connection unusable for subsequent requests.
   *
   * @param charset the charset with which the response bytes are decoded
   */
  public static BodySubscriber<Reader> ofReader(Charset charset) {
    requireNonNull(charset);
    return BodySubscribers.mapping(ofByteChannel(), ch -> Channels.newReader(ch, charset));
  }

  /**
   * Returns a {@code BodySubscriber} of {@code T} as specified by {@link
   * Decoder#toObject(TypeRef, MediaType)} using an installed decoder.
   *
   * @param type a {@code TypeRef} representing {@code T}
   * @param mediaType the media type
   * @param <T> the response body type
   * @throws UnsupportedOperationException if no {@code Decoder} that supports the given type or
   *     media type is installed
   */
  public static <T> BodySubscriber<T> ofObject(
      TypeRef<T> type, @Nullable MediaType mediaType) {
    return requireDecoder(type, mediaType).toObject(type, mediaType);
  }

  /**
   * Returns a {@code BodySubscriber} of {@code Supplier<T>} as specified by {@link
   * Decoder#toDeferredObject(TypeRef, MediaType)} using an installed decoder.
   *
   * @param type a {@code TypeRef} representing {@code T}
   * @param mediaType the media type
   * @param <T> the response body type
   * @throws UnsupportedOperationException if no {@code Decoder} that supports the given type or
   *     media type is installed
   */
  public static <T> BodySubscriber<Supplier<T>> ofDeferredObject(
      TypeRef<T> type, @Nullable MediaType mediaType) {
    return requireDecoder(type, mediaType).toDeferredObject(type, mediaType);
  }

  private static Decoder requireDecoder(TypeRef<?> type, @Nullable MediaType mediaType) {
    return Decoder.getDecoder(type, mediaType)
        .orElseThrow(() -> unsupportedConversion(type, mediaType));
  }

  private static UnsupportedOperationException unsupportedConversion(
      TypeRef<?> type, @Nullable MediaType mediaType) {
    String message = "unsupported conversion to an object of type <" + type + ">";
    if (mediaType != null) {
      message += " with media type <" + mediaType + ">";
    }
    return new UnsupportedOperationException(message);
  }
}
