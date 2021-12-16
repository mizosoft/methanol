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

package com.github.mizosoft.methanol.adapter.jackson.flux;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SequenceWriter;
import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.MoreBodySubscribers;
import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter;
import com.github.mizosoft.methanol.adapter.jackson.ObjectReaderFactory;
import com.github.mizosoft.methanol.adapter.jackson.internal.JacksonAdapterUtils;
import com.github.mizosoft.methanol.adapter.jackson.internal.JacksonSubscriber;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.reactivestreams.FlowAdapters;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

abstract class JacksonFluxAdapter extends AbstractBodyAdapter {
  final ObjectMapper mapper;

  JacksonFluxAdapter(ObjectMapper mapper) {
    super(MediaType.APPLICATION_JSON);
    this.mapper = requireNonNull(mapper);
  }

  static final class Encoder extends JacksonFluxAdapter implements BodyAdapter.Encoder {
    Encoder(ObjectMapper mapper) {
      super(mapper);
    }

    @Override
    public boolean supportsType(TypeRef<?> type) {
      Class<?> clazz = type.rawType();
      return org.reactivestreams.Publisher.class.isAssignableFrom(clazz)
          || Flow.Publisher.class.isAssignableFrom(clazz);
    }

    @Override
    public BodyPublisher toBody(Object object, @Nullable MediaType mediaType) {
      requireNonNull(object);
      requireSupport(object.getClass());
      requireCompatibleOrNull(mediaType);
      Charset charset = charsetOrUtf8(mediaType);
      org.reactivestreams.Publisher<ByteBuffer> body;
      if (object instanceof Mono) {
        body = ((Mono<?>) object).map(o -> encodeValue(o, charset));
      } else {
        Flux<?> flux =
            object instanceof org.reactivestreams.Publisher<?>
                ? Flux.from((org.reactivestreams.Publisher<?>) object)
                : JdkFlowAdapter.flowPublisherToFlux((Flow.Publisher<?>) object);
        body = encodeValues(flux, charset);
      }
      return attachMediaType(
          BodyPublishers.fromPublisher(FlowAdapters.toFlowPublisher(body)), mediaType);
    }

    private ByteBuffer encodeValue(Object object, Charset charset) {
      ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
      try (Writer writer = new OutputStreamWriter(outputBuffer, charset)) {
        mapper.writeValue(writer, object);
      } catch (IOException ioe) {
        throw JacksonAdapterUtils.throwUnchecked(ioe);
      }
      return ByteBuffer.wrap(outputBuffer.toByteArray());
    }

    private Flux<ByteBuffer> encodeValues(Flux<?> values, Charset charset) {
      ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
      SequenceWriter sequenceWriter;
      try {
        sequenceWriter =
            mapper.writer().writeValuesAsArray(new OutputStreamWriter(outputBuffer, charset));
      } catch (IOException ioe) {
        throw new UncheckedIOException(ioe);
      }
      return values
          .map(o -> encodeSequenceValue(o, sequenceWriter, outputBuffer))
          .concatWith(Mono.create(sink -> endSequence(sequenceWriter, outputBuffer, sink)))
          .map(ByteBuffer::wrap);
    }

    private byte[] encodeSequenceValue(
        Object value, SequenceWriter sequenceWriter, ByteArrayOutputStream outBuffer) {
      try {
        sequenceWriter.write(value);
      } catch (IOException ioe) {
        throw JacksonAdapterUtils.throwUnchecked(ioe);
      }
      byte[] writtenBytes = outBuffer.toByteArray();
      outBuffer.reset();
      return writtenBytes;
    }

    private void endSequence(
        SequenceWriter sequenceWriter, ByteArrayOutputStream outBuffer, MonoSink<byte[]> sink) {
      try {
        sequenceWriter.close();
      } catch (IOException ioe) {
        sink.error(ioe);
        return;
      }
      // may have flushed data
      if (outBuffer.size() > 0) {
        sink.success(outBuffer.toByteArray());
      } else {
        sink.success();
      }
    }
  }

  static final class Decoder extends JacksonFluxAdapter implements BodyAdapter.Decoder {
    Decoder(ObjectMapper mapper) {
      super(mapper);
    }

    @Override
    public boolean supportsType(TypeRef<?> type) {
      Class<?> clazz = type.rawType();
      return clazz == Flux.class
          || clazz == Mono.class
          || clazz == org.reactivestreams.Publisher.class
          || clazz == Flow.Publisher.class;
    }

    @Override
    public <T> BodySubscriber<T> toObject(TypeRef<T> type, @Nullable MediaType mediaType) {
      requireNonNull(type);
      requireSupport(type);
      requireCompatibleOrNull(mediaType);
      Type elementType = getFirstTypeArgumentOrParameter(type.type());
      JsonParser asyncParser = createAsyncParser();
      Class<? super T> rawPublisherType = type.rawType();
      BodySubscriber<?> subscriber;
      if (rawPublisherType == Mono.class) {
        subscriber =
            MoreBodySubscribers.fromAsyncSubscriber(
                new JacksonSubscriber<>(
                    mapper,
                    TypeRef.from(elementType),
                    ObjectReaderFactory.getDefault(),
                    asyncParser),
                s -> CompletableFuture.completedStage(Mono.fromCompletionStage(s.getBody())));
      } else {
        JacksonFluxSubscriber<?> fluxSubscriber =
            new JacksonFluxSubscriber<>(mapper, elementType, asyncParser);
        subscriber =
            rawPublisherType == Flow.Publisher.class
                ? BodySubscribers.mapping(fluxSubscriber, FlowAdapters::toFlowPublisher)
                : fluxSubscriber;
      }
      return BodySubscribers.mapping(
          JacksonAdapterUtils.coerceUtf8(subscriber, charsetOrUtf8(mediaType)),
          p -> {
            @SuppressWarnings("unchecked")
            T publisher = (T) rawPublisherType.cast(p);
            return publisher;
          });
    }

    private JsonParser createAsyncParser() {
      try {
        return mapper.getFactory().createNonBlockingByteArrayParser();
      } catch (IOException ioe) {
        throw new UnsupportedOperationException("couldn't create non-blocking parser", ioe);
      }
    }

    private static Type getFirstTypeArgumentOrParameter(Type type) {
      return type instanceof ParameterizedType
          ? ((ParameterizedType) type).getActualTypeArguments()[0]
          : ((Class<?>) type).getTypeParameters()[0];
    }
  }
}
