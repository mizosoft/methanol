/*
 * MIT License
 *
 * Copyright (c) 2019 Moataz Abdelnasser
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

import com.github.mizosoft.methanol.internal.extensions.ForwardingMimeBodyPublisher;
import java.net.http.HttpRequest.BodyPublisher;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Provides additional {@link BodyPublisher} implementations. */
public class MoreBodyPublishers {

  private MoreBodyPublishers() {} // non-instantiable

  /**
   * Adapts the given {@code BodyPublisher} into a {@link MimeBodyPublisher} with the given media
   * type.
   *
   * @param bodyPublisher the publisher
   * @param mediaType the body's media type
   */
  public static MimeBodyPublisher ofMediaType(BodyPublisher bodyPublisher, MediaType mediaType) {
    return new ForwardingMimeBodyPublisher(bodyPublisher, mediaType);
  }

  /**
   * Returns a {@code BodyPublisher} as specified by {@link Converter.OfRequest#toBody(Object,
   * MediaType)} using an installed converter.
   *
   * @param object the object
   * @param mediaType the media type
   * @throws UnsupportedOperationException if no {@code Converter.OfRequest} that supports the
   *     runtime type of the given object or the given media type is installed
   */
  public static BodyPublisher ofObject(Object object, @Nullable MediaType mediaType) {
    TypeReference<?> runtimeType = TypeReference.from(object.getClass());
    Converter.OfRequest converter =
        Converter.OfRequest.getConverter(runtimeType, mediaType)
            .orElseThrow(() -> unsupportedConversion(runtimeType, mediaType));
    return converter.toBody(runtimeType, mediaType);
  }

  private static UnsupportedOperationException unsupportedConversion(
      TypeReference<?> type, @Nullable MediaType mediaType) {
    String message = "unsupported conversion from an object type <" + type + ">";
    if (mediaType != null) {
      message += " with media type <" + mediaType + ">";
    }
    return new UnsupportedOperationException(message);
  }
}
