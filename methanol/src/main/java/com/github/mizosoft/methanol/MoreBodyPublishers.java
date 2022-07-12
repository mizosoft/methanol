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

import com.github.mizosoft.methanol.BodyAdapter.Encoder;
import com.github.mizosoft.methanol.function.ThrowingConsumer;
import com.github.mizosoft.methanol.internal.extensions.MimeBodyPublisherAdapter;
import java.io.OutputStream;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Provides additional {@link BodyPublisher} implementations. */
public class MoreBodyPublishers {
  private MoreBodyPublishers() {} // non-instantiable

  /**
   * Returns a {@code BodyPublisher} that reads what's written to the {@code OutputStream} received
   * by the given task. When the returned publisher receives a subscriber (i.e. when the HTTP client
   * starts sending the request body), the given task is executed by the given executor to write the
   * body's content. The latter is asynchronously channeled to the HTTP client.
   */
  public static BodyPublisher ofOutputStream(
      ThrowingConsumer<? super OutputStream> writerTask, Executor executor) {
    return ofBodyWriter(WritableBodyPublisher::outputStream, writerTask, executor);
  }

  /**
   * Returns a {@code BodyPublisher} that reads what's written to the {@code WritableByteChannel}
   * received by the given task. When the returned publisher receives a subscriber (i.e. when the
   * HTTP client starts sending the request body), the given task is executed by the given executor
   * to write the body's content. The latter is asynchronously channeled to the HTTP client.
   */
  public static BodyPublisher ofWritableByteChannel(
      ThrowingConsumer<? super WritableByteChannel> writerTask, Executor executor) {
    return ofBodyWriter(WritableBodyPublisher::byteChannel, writerTask, executor);
  }

  /**
   * Adapts the given {@code BodyPublisher} into a {@link MimeBodyPublisher} with the given media
   * type.
   *
   * @param bodyPublisher the publisher
   * @param mediaType the body's media type
   */
  public static MimeBodyPublisher ofMediaType(BodyPublisher bodyPublisher, MediaType mediaType) {
    return new MimeBodyPublisherAdapter(bodyPublisher, mediaType);
  }

  /**
   * Returns a {@code BodyPublisher} as specified by {@link Encoder#toBody(Object, MediaType)} using
   * an installed encoder.
   *
   * @param object the object
   * @param mediaType the media type
   * @throws UnsupportedOperationException if no {@code } that supports the runtime type of the
   *     given object or the given media type is installed
   */
  public static BodyPublisher ofObject(Object object, @Nullable MediaType mediaType) {
    TypeRef<?> runtimeType = TypeRef.from(object.getClass());
    Encoder encoder =
        Encoder.getEncoder(runtimeType, mediaType)
            .orElseThrow(() -> unsupportedConversion(runtimeType, mediaType));
    return encoder.toBody(object, mediaType);
  }

  private static UnsupportedOperationException unsupportedConversion(
      TypeRef<?> type, @Nullable MediaType mediaType) {
    String message = "unsupported conversion from an object type <" + type + ">";
    if (mediaType != null) {
      message += " with media type <" + mediaType + ">";
    }
    return new UnsupportedOperationException(message);
  }

  private static <T extends AutoCloseable> BodyPublisher ofBodyWriter(
      Function<WritableBodyPublisher, T> extractor,
      ThrowingConsumer<? super T> writerTask,
      Executor executor) {
    requireNonNull(extractor);
    requireNonNull(writerTask);
    requireNonNull(executor);
    return BodyPublishers.fromPublisher(
        subscriber -> {
          requireNonNull(subscriber);

          var publisher = WritableBodyPublisher.create();
          publisher.subscribe(subscriber);

          if (!publisher.isClosed()) {
            try {
              executor.execute(
                  () -> {
                    try (var out = extractor.apply(publisher)) {
                      writerTask.accept(out);
                    } catch (Throwable t) {
                      publisher.closeExceptionally(t);
                    }
                  });
            } catch (Throwable t) {
              publisher.closeExceptionally(t);
            }
          }
        });
  }
}
