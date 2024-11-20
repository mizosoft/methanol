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
import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.concurrent.Delayer;
import com.github.mizosoft.methanol.internal.extensions.AsyncSubscriberAdapter;
import com.github.mizosoft.methanol.internal.extensions.ByteChannelBodySubscriber;
import com.github.mizosoft.methanol.internal.extensions.TimeoutBodySubscriber;
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

/** Static factories for additional {@link BodySubscriber} implementations. */
public class MoreBodySubscribers {
  private MoreBodySubscribers() {}

  /**
   * Returns a {@code BodySubscriber} that forwards the response body to the given downstream. The
   * response body's completion depends on the completion of the {@code CompletionStage} returned by
   * the given function. Unlike {@link BodySubscribers#fromSubscriber(Subscriber, Function)}, the
   * given subscriber's {@code onComplete} or {@code onError} need not be called for the body to
   * complete.
   */
  public static <T, S extends Subscriber<? super List<ByteBuffer>>>
      BodySubscriber<T> fromAsyncSubscriber(
          S downstream, Function<? super S, ? extends CompletionStage<T>> asyncFinisher) {
    return new AsyncSubscriberAdapter<>(downstream, asyncFinisher);
  }

  /**
   * Returns a {@code BodySubscriber} that completes the given downstream with {@link
   * HttpReadTimeoutException} if a requested signal is not received within the given timeout. A
   * system-wide scheduler is used to schedule timeout events.
   *
   * @throws IllegalArgumentException if the timeout is non-positive
   */
  public static <T> BodySubscriber<T> withReadTimeout(
      BodySubscriber<T> downstream, Duration timeout) {
    return withReadTimeout(downstream, timeout, Delayer.systemDelayer());
  }

  /**
   * Returns a {@code BodySubscriber} that completes the given downstream with {@link
   * HttpReadTimeoutException} if a requested signal is not received within the given timeout. The
   * given {@code ScheduledExecutorService} is used to schedule timeout events.
   *
   * @throws IllegalArgumentException if the timeout is non-positive
   */
  public static <T> BodySubscriber<T> withReadTimeout(
      BodySubscriber<T> downstream, Duration timeout, ScheduledExecutorService scheduler) {
    return withReadTimeout(downstream, timeout, Delayer.of(scheduler));
  }

  static <T> BodySubscriber<T> withReadTimeout(
      BodySubscriber<T> downstream, Duration timeout, Delayer delayer) {
    return new TimeoutBodySubscriber<>(downstream, timeout, delayer);
  }

  /**
   * Returns a completed {@code BodySubscriber} of {@link ReadableByteChannel} that reads the
   * response body. The channel returned by the subscriber is {@link InterruptibleChannel
   * interruptible}.
   *
   * <p>To ensure proper release of resources, the channel should be fully consumed until EOF is
   * reached. If such consumption cannot be guaranteed, either the channel should be eventually
   * closed or the thread which is blocked on reading the channel should be interrupted. Note,
   * however, that doing so will render the underlying connection unusable for subsequent requests.
   */
  public static BodySubscriber<ReadableByteChannel> ofByteChannel() {
    return new ByteChannelBodySubscriber();
  }

  /**
   * Returns a completed {@code BodySubscriber} of {@link Reader} that reads the response body as a
   * stream of characters decoded using the given charset.
   *
   * <p>To ensure proper release of resources, the reader should be fully consumed until EOF is
   * reached. If such consumption cannot be guaranteed, the reader should be eventually closed.
   * Note, however, that doing so will render the underlying connection unusable for subsequent
   * requests.
   */
  public static BodySubscriber<Reader> ofReader(Charset charset) {
    requireNonNull(charset);
    return BodySubscribers.mapping(
        ofByteChannel(), channel -> Channels.newReader(channel, charset));
  }

  /**
   * Returns a {@code BodySubscriber} that decodes the response body into an object of the given
   * type using an installed {@link Decoder#toObject(TypeRef, MediaType) decoder}.
   *
   * @throws UnsupportedOperationException if no {@link Decoder} that supports the given object type
   *     or media type is installed
   */
  public static <T> BodySubscriber<T> ofObject(TypeRef<T> type, @Nullable MediaType mediaType) {
    return AdapterCodec.installed().subscriberOf(type, Utils.hintsOf(mediaType));
  }

  /**
   * Returns a {@code BodySubscriber} that decodes the response body into an object of the given
   * type using an installed {@link Decoder#toDeferredObject(TypeRef, MediaType) decoder}.
   *
   * @throws UnsupportedOperationException if no {@link Decoder} that supports the given object type
   *     or media type is installed
   */
  public static <T> BodySubscriber<Supplier<T>> ofDeferredObject(
      TypeRef<T> type, @Nullable MediaType mediaType) {
    return AdapterCodec.installed().deferredSubscriberOf(type, Utils.hintsOf(mediaType));
  }
}
