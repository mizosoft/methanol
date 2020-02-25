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

package com.github.mizosoft.methanol.adapter;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.TypeReference;
import java.nio.charset.Charset;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Abstract {@link BodyAdapter} that implements {@link BodyAdapter#isCompatibleWith(MediaType)} by
 * specifying a set of {@code MediaTypes} the adapter is compatible with.
 */
public abstract class AbstractBodyAdapter implements BodyAdapter {

  private final Set<MediaType> compatibleMediaTypes;

  /** Creates an {@code AbstractBodyAdapter} compatible with the given media types. */
  protected AbstractBodyAdapter(MediaType... compatibleMediaTypes) {
    this.compatibleMediaTypes = Set.of(compatibleMediaTypes);
  }

  @Override
  public final boolean isCompatibleWith(MediaType mediaType) {
    requireNonNull(mediaType);
    for (MediaType supported : compatibleMediaTypes) {
      if (supported.isCompatibleWith(mediaType)) {
        return true;
      }
    }
    return false;
  }

  /** Returns an immutable set containing the media types this adapter is compatible with. */
  protected Set<MediaType> compatibleMediaTypes() {
    return compatibleMediaTypes;
  }

  /**
   * @throws UnsupportedOperationException if this adapter doesn't {@link
   *     BodyAdapter#supportsType(TypeReference) support} the given type.
   */
  protected void requireSupport(TypeReference<?> type) {
    if (!supportsType(type)) {
      throw new UnsupportedOperationException("unsupported type: " + type);
    }
  }

  /**
   * @throws UnsupportedOperationException if this adapter doesn't {@link
   *     BodyAdapter#supportsType(TypeReference) support} the given raw type.
   */
  protected void requireSupport(Class<?> type) {
    requireSupport(TypeReference.from(type));
  }

  /**
   * @throws UnsupportedOperationException if this adapter is not {@link
   *     BodyAdapter#isCompatibleWith(MediaType) compatible} the given type.
   */
  protected void requireCompatibleOrNull(@Nullable MediaType mediaType) {
    if (mediaType != null && !isCompatibleWith(mediaType)) {
      throw new UnsupportedOperationException("adapter not compatible with: " + mediaType);
    }
  }

  /**
   * Returns either the result of {@link MediaType#charsetOrDefault(Charset)} or {@code
   * defaultCharset} directly if {@code mediaType} is null.
   */
  public static Charset charsetOrDefault(@Nullable MediaType mediaType, Charset defaultCharset) {
    return mediaType != null ? mediaType.charsetOrDefault(defaultCharset) : defaultCharset;
  }
}