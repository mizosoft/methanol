package com.github.mizosoft.methanol.convert.jackson;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.github.mizosoft.methanol.Converter;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.MoreBodySubscribers;
import com.github.mizosoft.methanol.TypeReference;
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

abstract class JacksonConverter implements Converter {

  public static final MediaType APPLICATION_JSON = MediaType.of("application", "json");
  public static final Charset DEFAULT_ENCODING = StandardCharsets.UTF_8;

  final ObjectMapper mapper;

  JacksonConverter(ObjectMapper mapper) {
    this.mapper = requireNonNull(mapper);
  }

  @Override
  public boolean isCompatibleWith(MediaType mediaType) {
    return APPLICATION_JSON.isCompatibleWith(mediaType);
  }

  void requireCompatibleOrNull(@Nullable MediaType mediaType) {
    if (mediaType != null && !isCompatibleWith(mediaType)) {
      throw new UnsupportedOperationException("media type not compatible with JSON: " + mediaType);
    }
  }

  void requireSupport(TypeReference<?> type) {
    if (!supportsType(type)) {
      throw new UnsupportedOperationException("type not supported by jackson: " + type);
    }
  }

  static Charset charsetOrDefault(@Nullable MediaType mediaType) {
    return mediaType != null ? mediaType.charsetOrDefault(DEFAULT_ENCODING) : DEFAULT_ENCODING;
  }

  static final class OfRequest extends JacksonConverter implements Converter.OfRequest {

    OfRequest(ObjectMapper mapper) {
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
      requireSupport(TypeReference.from(object.getClass()));
      requireCompatibleOrNull(mediaType);
      ObjectWriter objWriter = mapper.writerFor(object.getClass());
      ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
      try (Writer writer = new OutputStreamWriter(outBuffer, charsetOrDefault(mediaType))) {
        objWriter.writeValue(writer, object);
      } catch (JsonProcessingException e) {
        throw new UncheckedIOException(e);
      } catch (IOException ioe) {
        throw new AssertionError(ioe); // writing to a memory buffer
      }
      return BodyPublishers.ofByteArray(outBuffer.toByteArray());
    }
  }

  static final class OfResponse extends JacksonConverter implements Converter.OfResponse {

    OfResponse(ObjectMapper mapper) {
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
      ObjectReader objReader = mapper.readerFor(mapper.constructType(type.type()));
      Charset charset = charsetOrDefault(mediaType);
      // The non-blocking parser only works with UTF-8 and ASCII
      // https://github.com/FasterXML/jackson-core/issues/596
      if (charset.equals(StandardCharsets.UTF_8)
          || charset.equals(StandardCharsets.US_ASCII)) {
        try {
          return new JacksonSubscriber<>(mapper, type,
              mapper.getFactory().createNonBlockingByteArrayParser());
        } catch (IOException | UnsupportedOperationException ignored) {
          // Fallback to de-serializing from byte array
        }
      }
      return BodySubscribers.mapping(
          BodySubscribers.ofByteArray(),
          bytes -> readValueUnchecked(objReader,
              new InputStreamReader(new ByteArrayInputStream(bytes), charset)));
    }

    @Override
    public <T> BodySubscriber<Supplier<T>> toDeferredObject(
        TypeReference<T> type, @Nullable MediaType mediaType) {
      requireNonNull(type);
      requireSupport(type);
      requireCompatibleOrNull(mediaType);
      ObjectReader objReader = mapper.readerFor(mapper.constructType(type.type()));
      return BodySubscribers.mapping(
          MoreBodySubscribers.ofReader(charsetOrDefault(mediaType)),
          reader -> () -> readValueUnchecked(objReader, reader));
    }

    private static <T> T readValueUnchecked(ObjectReader objReader, Reader reader) {
      try {
        return objReader.readValue(reader);
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
