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

package com.github.mizosoft.methanol.adapter.jackson;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.MoreBodySubscribers;
import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter;
import com.github.mizosoft.methanol.internal.flow.DelegatingSubscriber;
import com.github.mizosoft.methanol.internal.flow.Prefetcher;
import com.github.mizosoft.methanol.internal.flow.Upstream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Subscription;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;

abstract class JacksonAdapter extends AbstractBodyAdapter {

  public static final MediaType APPLICATION_JSON = MediaType.of("application", "json");

  final ObjectMapper mapper;

  JacksonAdapter(ObjectMapper mapper) {
    super(APPLICATION_JSON);
    this.mapper = requireNonNull(mapper);
  }

  static final class Encoder extends JacksonAdapter implements BodyAdapter.Encoder {

    Encoder(ObjectMapper mapper) {
      super(mapper);
    }

    @Override
    public boolean supportsType(TypeRef<?> type) {
      return mapper.canSerialize(type.rawType());
    }

    @Override
    public BodyPublisher toBody(Object object, @Nullable MediaType mediaType) {
      requireNonNull(object);
      requireSupport(object.getClass());
      requireCompatibleOrNull(mediaType);
      ObjectWriter objWriter = mapper.writerFor(object.getClass());
      ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
      try (Writer writer = new OutputStreamWriter(outBuffer, charsetOrUtf8(mediaType))) {
        objWriter.writeValue(writer, object);
      } catch (JsonProcessingException e) {
        throw new UncheckedIOException(e);
      } catch (IOException ioe) {
        throw new AssertionError(ioe); // writing to a memory buffer
      }
      return attachMediaType(BodyPublishers.ofByteArray(outBuffer.toByteArray()), mediaType);
    }
  }

  static final class Decoder extends JacksonAdapter implements BodyAdapter.Decoder {

    Decoder(ObjectMapper mapper) {
      super(mapper);
    }

    @Override
    public boolean supportsType(TypeRef<?> type) {
      return mapper.canDeserialize(mapper.constructType(type.type()));
    }

    @Override
    public <T> BodySubscriber<T> toObject(TypeRef<T> type, @Nullable MediaType mediaType) {
      requireNonNull(type);
      requireSupport(type);
      requireCompatibleOrNull(mediaType);
      JsonParser asyncParser;
      try {
        asyncParser = mapper.getFactory().createNonBlockingByteArrayParser();
      } catch (IOException | UnsupportedOperationException ignored) {
        // Fallback to de-serializing from byte array
        return BodySubscribers.mapping(
            BodySubscribers.ofByteArray(), bytes -> readValueUnchecked(type, bytes));
      }
      // The non-blocking parser only works with UTF-8 and ASCII
      // https://github.com/FasterXML/jackson-core/issues/596
      Charset charset = charsetOrUtf8(mediaType);
      JacksonSubscriber<T> subscriber = new JacksonSubscriber<>(mapper, type, asyncParser);
      return charset.equals(StandardCharsets.US_ASCII) || charset.equals(StandardCharsets.UTF_8)
          ? subscriber
          : new CharsetCoercingSubscriber<>(subscriber, charset, StandardCharsets.UTF_8);
    }

    @Override
    public <T> BodySubscriber<Supplier<T>> toDeferredObject(
        TypeRef<T> type, @Nullable MediaType mediaType) {
      requireNonNull(type);
      requireSupport(type);
      requireCompatibleOrNull(mediaType);
      return BodySubscribers.mapping(
          MoreBodySubscribers.ofReader(charsetOrUtf8(mediaType)),
          reader -> () -> readValueUnchecked(type, reader));
    }

    private <T> T readValueUnchecked(TypeRef<T> type, byte[] body) {
      try {
        JsonParser parser = mapper.getFactory().createParser(body);
        return mapper.readerFor(mapper.constructType(type.type())).readValue(parser);
      } catch (IOException ioe) {
        throw new UncheckedIOException(ioe);
      }
    }

    private <T> T readValueUnchecked(TypeRef<T> type, Reader reader) {
      try {
        return mapper.readerFor(mapper.constructType(type.type())).readValue(reader);
      } catch (IOException ioe) {
        throw new UncheckedIOException(ioe);
      }
    }
  }

  private static class JacksonSubscriber<T> implements BodySubscriber<T> {

    private final ObjectMapper mapper;
    private final ObjectReader objReader;
    private final JsonParser parser;
    private final ByteArrayFeeder feeder;
    private final TokenBuffer jsonBuffer;
    private final CompletableFuture<T> valueFuture;
    private final Upstream upstream;
    private final Prefetcher prefetcher;

    JacksonSubscriber(ObjectMapper mapper, TypeRef<T> type, JsonParser parser) {
      this.mapper = mapper;
      this.objReader = mapper.readerFor(mapper.constructType(type.type()));
      this.parser = parser;
      feeder = (ByteArrayFeeder) parser.getNonBlockingInputFeeder();
      jsonBuffer = new TokenBuffer(this.parser);
      valueFuture = new CompletableFuture<>();
      upstream = new Upstream();
      prefetcher = new Prefetcher();
    }

    @Override
    public CompletionStage<T> getBody() {
      return valueFuture;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      requireNonNull(subscription);
      if (upstream.setOrCancel(subscription)) {
        prefetcher.update(upstream);
      }
    }

    @Override
    public void onNext(List<ByteBuffer> item) {
      requireNonNull(item);
      byte[] bytes = collectBytes(item);
      try {
        feeder.feedInput(bytes, 0, bytes.length);
        flushParser();
      } catch (Throwable t) {
        valueFuture.completeExceptionally(t);
        upstream.cancel();
        return;
      }
      prefetcher.update(upstream);
    }

    @Override
    public void onError(Throwable throwable) {
      requireNonNull(throwable);
      upstream.clear();
      valueFuture.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
      upstream.clear();
      feeder.endOfInput();
      try {
        flushParser();
        valueFuture.complete(objReader.readValue(jsonBuffer.asParser(mapper)));
      } catch (Throwable ioe) {
        valueFuture.completeExceptionally(ioe);
      }
    }

    private void flushParser() throws IOException {
      JsonToken token;
      while ((token = parser.nextToken()) != null && token != JsonToken.NOT_AVAILABLE) {
        jsonBuffer.copyCurrentEvent(parser);
      }
    }

    private byte[] collectBytes(List<ByteBuffer> item) {
      int size = item.stream().mapToInt(ByteBuffer::remaining).sum();
      byte[] bytes = new byte[size];
      int offset = 0;
      for (ByteBuffer buff : item) {
        int remaining = buff.remaining();
        buff.get(bytes, offset, buff.remaining());
        offset += remaining;
      }
      return bytes;
    }
  }

  /**
   * Decodes body using {@code sourceCharset} then encodes it again to {@code targetCharset}. Used
   * to coerce response into UTF-8 if not already to be parsable by the non-blocking parser.
   */
  public static final class CharsetCoercingSubscriber<T>
      extends DelegatingSubscriber<List<ByteBuffer>, BodySubscriber<T>>
      implements BodySubscriber<T> {

    private static final int TEMP_BUFFER_SIZE = 4 * 1024;
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

    private final CharsetDecoder decoder;
    private final CharsetEncoder encoder;
    private final CharBuffer tempCharBuff;
    private @Nullable ByteBuffer leftover;

    public CharsetCoercingSubscriber(BodySubscriber<T> downstream,
        Charset sourceCharset, Charset targetCharset) {
      super(downstream);
      decoder = sourceCharset.newDecoder();
      encoder = targetCharset.newEncoder();
      tempCharBuff = CharBuffer.allocate(TEMP_BUFFER_SIZE);
    }

    @Override
    public CompletionStage<T> getBody() {
      return downstream.getBody();
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
      super.onNext(processed);
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
          (int)
              (input.remaining()
                  * decoder.averageCharsPerByte()
                  * encoder.averageBytesPerChar());
      ByteBuffer out = ByteBuffer.allocate(capacity);
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
        CoderResult encoderResult =
            encoder.encode(tempCharBuff.flip(), out, endOfInputForEncoder);
        tempCharBuff.compact(); // might not have been completely consumed
        if (encoderResult.isUnderflow() && endOfInputForEncoder) {
          encoderResult = encoder.flush(out);
        }
        if (encoderResult.isError()) {
          encoderResult.throwException();
        }
        if (encoderResult.isOverflow()) { // need bigger out buffer
          // TODO: not overflow aware?
          capacity = capacity + Math.max(1, capacity >> 1);
          ByteBuffer newOut = ByteBuffer.allocate(capacity);
          newOut.put(out.flip());
          out = newOut;
        } else if (encoderResult.isUnderflow() && decoderResult.isUnderflow()) { // round finished
          if (input.hasRemaining()) {
            leftover = input.slice(); // save for next round
          }
          return out.flip();
        }
      }
    }
  }
}
