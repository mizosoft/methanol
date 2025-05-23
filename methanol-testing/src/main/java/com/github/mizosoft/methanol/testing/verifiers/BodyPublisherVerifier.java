/*
 * Copyright (c) 2024 Moataz Hussein
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

package com.github.mizosoft.methanol.testing.verifiers;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.from;

import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.MimeBodyPublisher;
import com.github.mizosoft.methanol.testing.ByteBufferCollector;
import com.github.mizosoft.methanol.testing.TestSubscriber;
import java.net.http.HttpRequest.BodyPublisher;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.function.Consumer;
import org.assertj.core.api.AbstractObjectAssert;
import org.assertj.core.api.AbstractStringAssert;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.Assertions;

/** A small DSL for testing {@code BodyPublisher} implementations. */
@SuppressWarnings("UnusedReturnValue")
public final class BodyPublisherVerifier {
  private final BodyPublisher publisher;

  public BodyPublisherVerifier(BodyPublisher publisher) {
    this.publisher = publisher;
  }

  public BodyPublisherVerifier hasMediaType(String mediaType) {
    return hasMediaType(MediaType.parse(mediaType));
  }

  @SuppressWarnings("BadImport")
  public BodyPublisherVerifier hasMediaType(MediaType mediaType) {
    assertThat(publisher)
        .isInstanceOf(MimeBodyPublisher.class)
        .extracting(MimeBodyPublisher.class::cast)
        .returns(mediaType, from(MimeBodyPublisher::mediaType));
    return this;
  }

  public BodyPublisherVerifier hasNoMediaType() {
    assertThat(publisher).isNotInstanceOf(MimeBodyPublisher.class);
    return this;
  }

  public BodyPublisherVerifier hasContentLength(long contentLength) {
    assertThat(publisher.contentLength()).isEqualTo(contentLength);
    return this;
  }

  public AbstractStringAssert<?> succeedsWith(String expected) {
    return succeedsWith(expected, UTF_8);
  }

  public AbstractStringAssert<?> succeedsWith(String expected, Charset charset) {
    return Assertions.assertThat(charset.decode(ByteBufferCollector.collect(publisher)).toString())
        .isEqualTo(expected);
  }

  public AbstractObjectAssert<?, ByteBuffer> succeedsWith(ByteBuffer bytes) {
    return assertThat(ByteBufferCollector.collect(publisher)).isEqualTo(bytes);
  }

  public AbstractStringAssert<?> succeedsWithNormalizingLineEndings(String expected) {
    return assertThat(UTF_8.decode(ByteBufferCollector.collect(publisher)).toString())
        .isEqualToNormalizingNewlines(expected);
  }

  public AbstractThrowableAssert<?, ?> failsWith(Class<? extends Throwable> type) {
    var subscriber = new TestSubscriber<ByteBuffer>();
    publisher.subscribe(subscriber);
    return assertThat(subscriber.awaitError()).isInstanceOf(type);
  }

  /** Repeats the verification twice to ensure result repeatability. */
  public BodyPublisherVerifier repeatedly(Consumer<BodyPublisherVerifier> verification) {
    verification.accept(this);
    verification.accept(this);
    return this;
  }
}
