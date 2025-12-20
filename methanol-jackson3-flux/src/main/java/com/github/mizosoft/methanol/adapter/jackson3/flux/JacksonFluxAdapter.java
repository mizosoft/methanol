/*
 * Copyright (c) 2025 Moataz Hussein
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

package com.github.mizosoft.methanol.adapter.jackson3.flux;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.function.Predicate.not;

import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.MoreBodySubscribers;
import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.reactivestreams.FlowAdapters;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SequenceWriter;

abstract class JacksonFluxAdapter extends AbstractBodyAdapter {
  final ObjectMapper mapper;

  JacksonFluxAdapter(ObjectMapper mapper) {
    super(MediaType.APPLICATION_JSON);
    this.mapper = requireNonNull(mapper);
  }

  private static Optional<TypeRef<?>> firstSpecifiedTypeArgument(
      TypeRef<?> subtypeRef, @Nullable Class<?> supertype) {
    var resolvedSubtypeRef =
        supertype != null ? subtypeRef.resolveSupertype(supertype) : subtypeRef;
    return resolvedSubtypeRef.typeArgumentAt(0).filter(not(TypeRef::isTypeVariable));
  }

  static final class Encoder extends JacksonFluxAdapter implements BaseEncoder {
    Encoder(ObjectMapper mapper) {
      super(mapper);
    }

    @Override
    public boolean supportsType(TypeRef<?> typeRef) {
      return supportsPublisherSupertype(typeRef, org.reactivestreams.Publisher.class)
          || supportsPublisherSupertype(typeRef, Flow.Publisher.class);
    }

    private boolean supportsPublisherSupertype(TypeRef<?> typeRef, Class<?> publisherSupertype) {
      return publisherSupertype.isAssignableFrom(typeRef.rawType());
    }

    @Override
    public <T> BodyPublisher toBody(T value, TypeRef<T> typeRef, Hints hints) {
      requireSupport(typeRef, hints);
      return attachMediaType(
          BodyPublishers.fromPublisher(
              FlowAdapters.toFlowPublisher(
                  encodePublisher(value, typeRef, hints.mediaTypeOrAny().charsetOrUtf8()))),
          hints.mediaTypeOrAny());
    }

    private org.reactivestreams.Publisher<ByteBuffer> encodePublisher(
        Object value, TypeRef<?> typeRef, Charset charset) {
      if (value instanceof Mono<?>) {
        return ((Mono<?>) value)
            .map(
                monoValue ->
                    encodeValue(
                        monoValue,
                        mapper.writerFor(
                            mapper.constructType(
                                firstSpecifiedTypeArgument(typeRef, Mono.class)
                                    .orElseGet(() -> TypeRef.ofRuntimeType(monoValue))
                                    .type())),
                        charset));
      } else {
        Class<?> publisherSupertype;
        Flux<?> valueAsFlux;
        if (value instanceof org.reactivestreams.Publisher<?>) {
          publisherSupertype = org.reactivestreams.Publisher.class;
          valueAsFlux = Flux.from((org.reactivestreams.Publisher<?>) value);
        } else {
          publisherSupertype = Flow.Publisher.class;
          valueAsFlux = JdkFlowAdapter.flowPublisherToFlux((Flow.Publisher<?>) value);
        }
        return encodeValues(
            valueAsFlux,
            firstSpecifiedTypeArgument(typeRef, publisherSupertype)
                .map(valueTypeRef -> mapper.writerFor(mapper.constructType(valueTypeRef.type())))
                .orElseGet(mapper::writer),
            charset);
      }
    }

    private ByteBuffer encodeValue(Object value, ObjectWriter objectWriter, Charset charset) {
      return ByteBuffer.wrap(
          charset.equals(UTF_8)
              ? objectWriter.writeValueAsBytes(value)
              : objectWriter.writeValueAsString(value).getBytes(charset));
    }

    private Flux<ByteBuffer> encodeValues(
        Flux<?> values, ObjectWriter objectWriter, Charset charset) {
      var buffer = new ByteArrayOutputStream();
      var sequenceWriter = objectWriter.writeValuesAsArray(new OutputStreamWriter(buffer, charset));
      return values
          .map(value -> encodeSequenceValue(value, sequenceWriter, buffer))
          .concatWith(Mono.create(sink -> endSequence(sink, sequenceWriter, buffer)))
          .map(ByteBuffer::wrap);
    }

    private byte[] encodeSequenceValue(
        Object value, SequenceWriter sequenceWriter, ByteArrayOutputStream buffer) {
      sequenceWriter.write(value);
      byte[] writtenBytes = buffer.toByteArray();
      buffer.reset();
      return writtenBytes;
    }

    private void endSequence(
        MonoSink<byte[]> sink, SequenceWriter sequenceWriter, ByteArrayOutputStream buffer) {
      try {
        sequenceWriter.close();
      } catch (JacksonIOException e) {
        sink.error(e);
        return;
      }

      // We may have already flushed all data.
      if (buffer.size() > 0) {
        sink.success(buffer.toByteArray());
      } else {
        sink.success();
      }
    }
  }

  static final class Decoder extends JacksonFluxAdapter implements BaseDecoder {
    Decoder(ObjectMapper mapper) {
      super(mapper);
    }

    @Override
    public boolean supportsType(TypeRef<?> typeRef) {
      var rawType = typeRef.rawType();
      return (rawType == Flux.class
          || rawType == Mono.class
          || rawType == org.reactivestreams.Publisher.class
          || rawType == Flow.Publisher.class);
    }

    @Override
    public <T> BodySubscriber<T> toObject(TypeRef<T> typeRef, Hints hints) {
      requireSupport(typeRef, hints);
      // Now we know we have a specified generic type.
      var valueType = firstSpecifiedTypeArgument(typeRef, null).orElseThrow().type();
      var objectReader = mapper.readerFor(mapper.constructType(valueType));
      var asyncParser =
          mapper.tokenStreamFactory().createNonBlockingByteBufferParser(ObjectReadContext.empty());
      var rawPublisherType = typeRef.rawType();
      BodySubscriber<?> subscriber;
      if (rawPublisherType == Mono.class) {
        subscriber =
            MoreBodySubscribers.fromAsyncSubscriber(
                BodySubscribers.mapping(BodySubscribers.ofByteArray(), objectReader::readValue),
                baseSubscriber ->
                    CompletableFuture.completedStage(
                        Mono.fromCompletionStage(baseSubscriber.getBody())));
      } else {
        var fluxSubscriber = new JacksonFluxSubscriber<>(objectReader, asyncParser);
        subscriber =
            rawPublisherType == Flow.Publisher.class
                ? BodySubscribers.mapping(fluxSubscriber, FlowAdapters::toFlowPublisher)
                : fluxSubscriber;
      }
      return BodySubscribers.mapping(
          coerceUtf8(subscriber, hints.mediaTypeOrAny().charsetOrUtf8()), typeRef::uncheckedCast);
    }

    private static <T> BodySubscriber<T> coerceUtf8(BodySubscriber<T> subscriber, Charset charset) {
      return charset.equals(UTF_8) || charset.equals(US_ASCII)
          ? subscriber
          : new CharsetRecodingSubscriber<>(subscriber, charset, UTF_8);
    }
  }
}
