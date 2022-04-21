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

package com.github.mizosoft.methanol.adapter.jackson.internal;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.extensions.ForwardingBodySubscriber;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Decodes body using {@code sourceCharset} then encodes it again to {@code targetCharset}. Used to
 * coerce response into UTF-8 if not already to be parsable by the non-blocking parser.
 */
final class CharsetCoercingSubscriber<T> extends ForwardingBodySubscriber<T> {
  private static final int TEMP_BUFFER_SIZE = 4 * 1024;
  private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

  private final CharsetDecoder decoder;
  private final CharsetEncoder encoder;
  private final CharBuffer tempCharBuff;
  private @Nullable ByteBuffer leftover;

  CharsetCoercingSubscriber(
      BodySubscriber<T> downstream, Charset sourceCharset, Charset targetCharset) {
    super(downstream);
    decoder = sourceCharset.newDecoder();
    encoder = targetCharset.newEncoder();
    tempCharBuff = CharBuffer.allocate(TEMP_BUFFER_SIZE);
  }

  @Override
  public void onNext(List<ByteBuffer> item) {
    requireNonNull(item);
    List<ByteBuffer> processed;
    try {
      processed = processItem(item);
    } catch (Throwable t) {
      upstream.cancel(); // flow interrupted
      super.onError(t);
      return;
    }
    if (!processed.isEmpty()) {
      super.onNext(processed);
    }
  }

  @Override
  public void onComplete() {
    ByteBuffer flushed;
    try {
      flushed = processBuffer(EMPTY_BUFFER, true);
    } catch (Throwable t) {
      super.onError(t);
      return;
    }
    if (flushed.hasRemaining()) {
      super.onNext(List.of(flushed));
    }
    super.onComplete();
  }

  private List<ByteBuffer> processItem(List<ByteBuffer> item) throws CharacterCodingException {
    List<ByteBuffer> processed = new ArrayList<>(item.size());
    for (ByteBuffer buffer : item) {
      ByteBuffer processedBuffer = processBuffer(buffer, false);
      if (processedBuffer.hasRemaining()) {
        processed.add(processedBuffer);
      }
    }
    return processed.isEmpty() ? List.of() : Collections.unmodifiableList(processed);
  }

  private ByteBuffer processBuffer(ByteBuffer input, boolean endOfInput)
      throws CharacterCodingException {
    // add any leftover bytes from previous round
    if (leftover != null) {
      input =
          ByteBuffer.allocate(leftover.remaining() + input.remaining())
              .put(leftover)
              .put(input)
              .flip();
      leftover = null;
    }
    // allocate estimate capacity and grow when full
    int capacity =
        (int) (input.remaining() * decoder.averageCharsPerByte() * encoder.averageBytesPerChar());
    ByteBuffer output = ByteBuffer.allocate(capacity);
    while (true) {
      CoderResult decoderResult = decoder.decode(input, tempCharBuff, endOfInput);
      if (decoderResult.isUnderflow() && endOfInput) {
        decoderResult = decoder.flush(tempCharBuff);
      }
      if (decoderResult.isError()) {
        decoderResult.throwException();
      }
      // it's not eoi for encoder unless decoder also finished (underflow result)
      boolean endOfInputForEncoder = decoderResult.isUnderflow() && endOfInput;
      CoderResult encoderResult = encoder.encode(tempCharBuff.flip(), output, endOfInputForEncoder);
      tempCharBuff.compact(); // might not have been completely consumed
      if (encoderResult.isUnderflow() && endOfInputForEncoder) {
        encoderResult = encoder.flush(output);
      }
      if (encoderResult.isError()) {
        encoderResult.throwException();
      }
      if (encoderResult.isOverflow()) { // need bigger out buffer
        // TODO: not overflow aware?
        capacity = capacity + Math.max(1, capacity >>> 1);
        ByteBuffer newOutput = ByteBuffer.allocate(capacity);
        newOutput.put(output.flip());
        output = newOutput;
      } else if (encoderResult.isUnderflow() && decoderResult.isUnderflow()) { // round finished
        if (input.hasRemaining()) {
          leftover = input.slice(); // save for next round
        }
        return output.flip();
      }
    }
  }
}
