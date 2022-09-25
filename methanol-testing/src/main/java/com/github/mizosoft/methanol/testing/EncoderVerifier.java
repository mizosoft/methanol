/*
 * Copyright (c) 2022 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.testing;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.github.mizosoft.methanol.BodyAdapter.Encoder;
import com.github.mizosoft.methanol.MediaType;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import org.assertj.core.api.AbstractObjectAssert;
import org.assertj.core.api.AbstractStringAssert;
import org.assertj.core.api.ThrowableAssertAlternative;
import org.checkerframework.checker.nullness.qual.Nullable;

/** A small DSL for testing {@link Encoder} implementations. */
@SuppressWarnings("UnusedReturnValue")
public final class EncoderVerifier extends BodyAdapterVerifier<Encoder, EncoderVerifier> {
  public EncoderVerifier(Encoder encoder) {
    super(encoder);
  }

  @Override
  EncoderVerifier self() {
    return this;
  }

  public <T> ObjectConversionStep<T> converting(T obj) {
    return new ObjectConversionStep<>(adapter, obj);
  }

  public static final class ObjectConversionStep<T> {
    private final Encoder encoder;
    private final T obj;
    private final @Nullable MediaType mediaType;

    ObjectConversionStep(Encoder encoder, T obj) {
      this(encoder, obj, null);
    }

    ObjectConversionStep(Encoder encoder, T obj, @Nullable MediaType mediaType) {
      this.encoder = encoder;
      this.obj = obj;
      this.mediaType = mediaType;
    }

    public ObjectConversionStep<T> withMediaType(MediaType mediaType) {
      return new ObjectConversionStep<>(encoder, obj, mediaType);
    }

    public ObjectConversionStep<T> withMediaType(String mediaType) {
      return withMediaType(MediaType.parse(mediaType));
    }

    public ThrowableAssertAlternative<UnsupportedOperationException> isNotSupported() {
      return assertThatExceptionOfType(UnsupportedOperationException.class)
          .isThrownBy(() -> encoder.toBody(obj, mediaType));
    }

    public BodyPublisherVerifier asBodyPublisher() {
      return new BodyPublisherVerifier(encoder.toBody(obj, mediaType));
    }

    public AbstractStringAssert<?> succeedsWith(String expected) {
      return succeedsWith(expected, UTF_8);
    }

    public AbstractStringAssert<?> succeedsWith(String expected, Charset charset) {
      return asBodyPublisher().succeedsWith(expected, charset);
    }

    public AbstractStringAssert<?> succeedsWithNormalizingLineEndings(String expected) {
      return asBodyPublisher().succeedsWithNormalizingLineEndings(expected);
    }

    public AbstractObjectAssert<?, ByteBuffer> succeedsWith(ByteBuffer bytes) {
      return asBodyPublisher().succeedsWith(bytes);
    }

    public ThrowableAssertAlternative<?> failsWith(Class<? extends Throwable> type) {
      return asBodyPublisher().failsWith(type);
    }
  }
}
