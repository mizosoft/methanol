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

package com.github.mizosoft.methanol.adapter.jackson.flux;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.extensions.ForwardingBodySubscriber;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A middle-ware {@link BodySubscriber} that recodes the response body into a different charset from
 * a source charset.
 */
final class CharsetRecodingSubscriber<T> extends ForwardingBodySubscriber<T> {
  private static final int TEMP_CHAR_BUFFER = 8 * 1024;
  private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

  private final CharBuffer tempCharBuffer = CharBuffer.allocate(TEMP_CHAR_BUFFER);
  private final CharsetDecoder decoder;
  private final CharsetEncoder encoder;
  private @Nullable ByteBuffer leftover;

  CharsetRecodingSubscriber(
      BodySubscriber<T> downstream, Charset sourceCharset, Charset targetCharset) {
    super(downstream);
    decoder = sourceCharset.newDecoder();
    encoder = targetCharset.newEncoder();
  }

  @Override
  public void onNext(List<ByteBuffer> item) {
    requireNonNull(item);
    List<ByteBuffer> recoded;
    try {
      recoded = recode(item);
    } catch (Throwable t) {
      upstream.cancel();
      super.onError(t);
      return;
    }
    if (!recoded.isEmpty()) {
      super.onNext(recoded);
    }
  }

  @Override
  public void onComplete() {
    ByteBuffer flushed;
    try {
      flushed = recode(EMPTY_BUFFER, true);
    } catch (Throwable t) {
      super.onError(t);
      return;
    }

    if (flushed.hasRemaining()) {
      try {
        super.onNext(List.of(flushed));
      } catch (Throwable t) {
        super.onError(t);
        return;
      }
    }
    super.onComplete();
  }

  private List<ByteBuffer> recode(List<ByteBuffer> buffers) throws CharacterCodingException {
    List<ByteBuffer> recodedBuffers = new ArrayList<>(buffers.size());
    for (var buffer : buffers) {
      var recodedBuffer = recode(buffer, false);
      if (recodedBuffer.hasRemaining()) {
        recodedBuffers.add(recodedBuffer);
      }
    }
    return recodedBuffers.isEmpty() ? List.of() : Collections.unmodifiableList(recodedBuffers);
  }

  private ByteBuffer recode(ByteBuffer buffer, boolean endOfInput) throws CharacterCodingException {
    // Add any leftover bytes from the previous round.
    if (leftover != null) {
      buffer =
          ByteBuffer.allocate(leftover.remaining() + buffer.remaining())
              .put(leftover)
              .put(buffer)
              .flip();
      leftover = null;
    }

    int estimatedCapacity =
        (int) (buffer.remaining() * decoder.averageCharsPerByte() * encoder.averageBytesPerChar());
    var recodedBuffer = ByteBuffer.allocate(estimatedCapacity);
    while (true) {
      var decodeResult = decoder.decode(buffer, tempCharBuffer, endOfInput);
      if (decodeResult.isUnderflow() && endOfInput) {
        decodeResult = decoder.flush(tempCharBuffer);
      }
      if (decodeResult.isError()) {
        decodeResult.throwException();
      }

      // It's not end of input for the encoder unless the decoder reports an underflow.
      boolean endOfInputForEncoder = endOfInput && decodeResult.isUnderflow();
      var encodeResult = encoder.encode(tempCharBuffer.flip(), recodedBuffer, endOfInputForEncoder);
      tempCharBuffer.compact(); // Might not have been completely consumed.
      if (encodeResult.isUnderflow() && endOfInputForEncoder) {
        encodeResult = encoder.flush(recodedBuffer);
      }
      if (encodeResult.isError()) {
        encodeResult.throwException();
      }

      if (encodeResult.isOverflow()) {
        // We need a bigger output buffer.
        int newCapacity = recodedBuffer.capacity() + Math.max(1, recodedBuffer.capacity() >>> 1);
        recodedBuffer = ByteBuffer.allocate(newCapacity).put(recodedBuffer.flip());
      } else if (encodeResult.isUnderflow() && decodeResult.isUnderflow()) {
        // This round is done.
        if (buffer.hasRemaining()) {
          leftover = buffer.slice(); // Save unconsumed data for next round.
        }
        return recodedBuffer.flip();
      }
    }
  }
}
