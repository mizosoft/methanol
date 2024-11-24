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

import static com.fasterxml.jackson.core.JsonToken.END_ARRAY;
import static com.fasterxml.jackson.core.JsonToken.NOT_AVAILABLE;
import static com.fasterxml.jackson.core.JsonToken.START_ARRAY;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.github.mizosoft.methanol.internal.extensions.PublisherBodySubscriber;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.flow.ForwardingSubscriber;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.function.Function;
import org.checkerframework.checker.nullness.qual.Nullable;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

class JacksonFluxSubscriber<T> extends ForwardingSubscriber<List<ByteBuffer>>
    implements BodySubscriber<Flux<T>> {
  private final BodySubscriber<Publisher<List<ByteBuffer>>> downstream =
      new PublisherBodySubscriber();
  private final ObjectMapper mapper;
  private final ObjectReader reader;
  private final JsonParser parser;

  JacksonFluxSubscriber(ObjectMapper mapper, ObjectReader reader, JsonParser parser) {
    this.mapper = mapper;
    this.reader = reader;
    this.parser = parser;
  }

  @Override
  protected Subscriber<List<ByteBuffer>> delegate() {
    return downstream;
  }

  @Override
  public CompletionStage<Flux<T>> getBody() {
    return CompletableFuture.completedFuture(
        decode(new PossiblyDelayedPublisher(downstream.getBody())));
  }

  private Flux<T> decode(Flow.Publisher<List<ByteBuffer>> publisher) {
    var tokenizer = new JacksonTokenizer(parser);
    return JdkFlowAdapter.flowPublisherToFlux(publisher)
        .concatMapIterable(tokenizer)
        .concatWith(Mono.create(tokenizer::complete))
        .handle(
            (tokenBuffer, sink) -> {
              try {
                sink.next(reader.readValue(tokenBuffer.asParser(mapper)));
              } catch (IOException e) {
                sink.error(e);
              }
            });
  }

  /** Wraps a possibly non-completed publisher's {@code CompletionStage}. */
  private static final class PossiblyDelayedPublisher implements Flow.Publisher<List<ByteBuffer>> {
    private final CompletionStage<Publisher<List<ByteBuffer>>> publisherFuture;

    PossiblyDelayedPublisher(CompletionStage<Publisher<List<ByteBuffer>>> publisherFuture) {
      this.publisherFuture = publisherFuture;
    }

    @Override
    public void subscribe(Subscriber<? super List<ByteBuffer>> subscriber) {
      requireNonNull(subscriber);
      publisherFuture.whenComplete(
          (publisher, ex) -> {
            if (ex != null) {
              FlowSupport.reject(subscriber, ex);
            } else if (publisher != null) {
              publisher.subscribe(subscriber);
            }
          });
    }
  }

  private static final class JacksonTokenizer
      implements Function<List<ByteBuffer>, List<TokenBuffer>> {
    private final JsonParser parser;
    private final ByteArrayFeeder feeder;
    private @Nullable TokenBuffer currentTokenBuffer;
    private int arrayDepth;
    private int objectDepth;

    JacksonTokenizer(JsonParser parser) {
      this.parser = parser;
      this.feeder = ((ByteArrayFeeder) parser.getNonBlockingInputFeeder());
    }

    @Override
    public List<TokenBuffer> apply(List<ByteBuffer> buffers) {
      var input = collect(buffers);
      var tokenBuffers = new ArrayList<TokenBuffer>();
      try {
        feeder.feedInput(input, 0, input.length);
        TokenBuffer tokenBuffer;
        while ((tokenBuffer = tokenize()) != null) {
          tokenBuffers.add(tokenBuffer);
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return Collections.unmodifiableList(tokenBuffers);
    }

    void complete(MonoSink<TokenBuffer> sink) {
      feeder.endOfInput();
      try {
        var lastToken = tokenize();
        if (lastToken != null) {
          sink.success(lastToken);
        } else {
          sink.success();
        }
      } catch (IOException e) {
        sink.error(e);
      }
    }

    private @Nullable TokenBuffer tokenize() throws IOException {
      while (true) {
        var token = parser.nextToken();
        if (token == null || token == NOT_AVAILABLE) {
          return null;
        }

        switch (token) {
          case START_ARRAY:
            arrayDepth++;
            break;
          case END_ARRAY:
            arrayDepth--;
            break;
          case START_OBJECT:
            objectDepth++;
            break;
          case END_OBJECT:
            objectDepth--;
            break;
          default:
            break;
        }

        // If this is an array document we copy everything but the enclosing `[]`.
        if (objectDepth > 0
            || ((token != START_ARRAY || arrayDepth > 1)
                && (token != END_ARRAY || arrayDepth >= 1))) {
          if (currentTokenBuffer == null) {
            currentTokenBuffer = new TokenBuffer(parser);
          }
          currentTokenBuffer.copyCurrentEvent(parser);
        }

        // Tokenize if this is a direct complete child of the enclosing array.
        if (objectDepth == 0 && arrayDepth <= 1 && (token.isScalarValue() || token.isStructEnd())) {
          var finishedTokenBuffer = currentTokenBuffer;
          currentTokenBuffer = null;
          return finishedTokenBuffer;
        }
      }
    }

    private static byte[] collect(List<ByteBuffer> buffers) {
      int size = buffers.stream().mapToInt(ByteBuffer::remaining).sum();
      byte[] bytes = new byte[size];
      int offset = 0;
      for (var buff : buffers) {
        int remaining = buff.remaining();
        buff.get(bytes, offset, buff.remaining());
        offset += remaining;
      }
      return bytes;
    }
  }
}
