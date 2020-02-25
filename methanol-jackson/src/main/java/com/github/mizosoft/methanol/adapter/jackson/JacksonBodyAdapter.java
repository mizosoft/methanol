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
import com.github.mizosoft.methanol.TypeReference;
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;

abstract class JacksonBodyAdapter extends AbstractBodyAdapter {

  public static final MediaType APPLICATION_JSON = MediaType.of("application", "json");
  public static final Charset DEFAULT_ENCODING = StandardCharsets.UTF_8;

  final ObjectMapper mapper;

  JacksonBodyAdapter(ObjectMapper mapper) {
    super(APPLICATION_JSON);
    this.mapper = requireNonNull(mapper);
  }

  static final class Encoder extends JacksonBodyAdapter implements BodyAdapter.Encoder {

    Encoder(ObjectMapper mapper) {
      super(mapper);
    }

    @Override
    public boolean supportsType(TypeReference<?> type) {
      requireNonNull(type);
      return mapper.canSerialize(type.rawType());
    }

    @Override
    public BodyPublisher toBody(Object object, @Nullable MediaType mediaType) {
      requireNonNull(object);
      requireSupport(object.getClass());
      requireCompatibleOrNull(mediaType);
      ObjectWriter objWriter = mapper.writerFor(object.getClass());
      ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
      try (Writer writer =
          new OutputStreamWriter(outBuffer, charsetOrDefault(mediaType, DEFAULT_ENCODING))) {
        objWriter.writeValue(writer, object);
      } catch (JsonProcessingException e) {
        throw new UncheckedIOException(e);
      } catch (IOException ioe) {
        throw new AssertionError(ioe); // writing to a memory buffer
      }
      return BodyPublishers.ofByteArray(outBuffer.toByteArray());
    }
  }

  static final class Decoder extends JacksonBodyAdapter implements BodyAdapter.Decoder {

    Decoder(ObjectMapper mapper) {
      super(mapper);
    }

    @Override
    public boolean supportsType(TypeReference<?> type) {
      return mapper.canDeserialize(mapper.constructType(type.type()));
    }

    @Override
    public <T> BodySubscriber<T> toObject(TypeReference<T> type, @Nullable MediaType mediaType) {
      requireNonNull(type);
      requireSupport(type);
      requireCompatibleOrNull(mediaType);
      Charset charset = charsetOrDefault(mediaType, DEFAULT_ENCODING);
      // The non-blocking parser only works with UTF-8 and ASCII
      // https://github.com/FasterXML/jackson-core/issues/596
      if (charset.equals(StandardCharsets.UTF_8) || charset.equals(StandardCharsets.US_ASCII)) {
        try {
          return new JacksonSubscriber<>(
              mapper, type, mapper.getFactory().createNonBlockingByteArrayParser());
        } catch (IOException | UnsupportedOperationException ignored) {
          // Fallback to de-serializing from byte array
        }
      }
      return BodySubscribers.mapping(
          BodySubscribers.ofByteArray(),
          bytes ->
              readValueUnchecked(
                  type, new InputStreamReader(new ByteArrayInputStream(bytes), charset)));
    }

    @Override
    public <T> BodySubscriber<Supplier<T>> toDeferredObject(
        TypeReference<T> type, @Nullable MediaType mediaType) {
      requireNonNull(type);
      requireSupport(type);
      requireCompatibleOrNull(mediaType);
      return BodySubscribers.mapping(
          MoreBodySubscribers.ofReader(charsetOrDefault(mediaType, DEFAULT_ENCODING)),
          reader -> () -> readValueUnchecked(type, reader));
    }

    private <T> T readValueUnchecked(TypeReference<T> type, Reader reader) {
      try {
        return mapper.readerFor(mapper.constructType(type.type())).readValue(reader);
      } catch (IOException ioe) {
        throw new UncheckedIOException(ioe);
      }
    }

    private static final class JacksonSubscriber<T> implements BodySubscriber<T> {

      private final ObjectMapper mapper;
      private final ObjectReader objReader;
      private final JsonParser parser;
      private final ByteArrayFeeder feeder;
      private final TokenBuffer jsonBuffer;
      private final CompletableFuture<T> valueFuture;
      private final AtomicReference<@Nullable Subscription> upstream;
      private final int prefetch;
      private final int prefetchThreshold;
      private int upstreamWindow;

      JacksonSubscriber(ObjectMapper mapper, TypeReference<T> type, JsonParser parser) {
        this.mapper = mapper;
        this.objReader = mapper.readerFor(mapper.constructType(type.type()));
        this.parser = parser;
        feeder = (ByteArrayFeeder) parser.getNonBlockingInputFeeder();
        jsonBuffer = new TokenBuffer(this.parser);
        valueFuture = new CompletableFuture<>();
        upstream = new AtomicReference<>();
        prefetch = FlowSupport.prefetch();
        prefetchThreshold = FlowSupport.prefetchThreshold();
      }

      @Override
      public CompletionStage<T> getBody() {
        return valueFuture;
      }

      @Override
      public void onSubscribe(Subscription subscription) {
        requireNonNull(subscription);
        if (upstream.compareAndSet(null, subscription)) {
          upstreamWindow = prefetch;
          subscription.request(prefetch);
        } else {
          subscription.cancel();
        }
      }

      @Override
      public void onNext(List<ByteBuffer> item) {
        requireNonNull(item);
        byte[] bytes = collectBytes(item);
        try {
          feeder.feedInput(bytes, 0, bytes.length);
          flushParser();
        } catch (Throwable ioe) {
          complete(ioe, true);
          return;
        }

        // See if more should be requested w.r.t prefetch logic
        Subscription s = upstream.get();
        if (s != null) {
          int update = upstreamWindow - 1;
          if (update <= prefetchThreshold) {
            upstreamWindow = prefetch;
            s.request(prefetch - update);
          } else {
            upstreamWindow = update;
          }
        }
      }

      @Override
      public void onError(Throwable throwable) {
        requireNonNull(throwable);
        complete(throwable, false);
      }

      @Override
      public void onComplete() {
        complete(null, false);
      }

      private void complete(@Nullable Throwable error, boolean cancelUpstream) {
        if (cancelUpstream) {
          Subscription s = upstream.getAndSet(FlowSupport.NOOP_SUBSCRIPTION);
          if (s != null) {
            s.cancel();
          }
        } else {
          upstream.set(FlowSupport.NOOP_SUBSCRIPTION);
        }
        if (error != null) {
          valueFuture.completeExceptionally(error);
        } else {
          feeder.endOfInput();
          try {
            flushParser(); // Flush parser after endOfInput even
            valueFuture.complete(objReader.readValue(jsonBuffer.asParser(mapper)));
          } catch (Throwable ioe) {
            valueFuture.completeExceptionally(ioe);
          }
        }
      }

      private void flushParser() throws IOException {
        JsonToken token;
        while ((token = parser.nextToken()) != null && token != JsonToken.NOT_AVAILABLE) {
          jsonBuffer.copyCurrentEvent(parser);
        }
      }

      private byte[] collectBytes(List<ByteBuffer> buffs) {
        int size = buffs.stream().mapToInt(ByteBuffer::remaining).sum();
        byte[] bytes = new byte[size];
        int offset = 0;
        for (ByteBuffer buff : buffs) {
          int remaining = buff.remaining();
          buff.get(bytes, offset, buff.remaining());
          offset += remaining;
        }
        return bytes;
      }
    }
  }
}
