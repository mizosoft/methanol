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

import static com.fasterxml.jackson.core.JsonToken.END_ARRAY;
import static com.fasterxml.jackson.core.JsonToken.NOT_AVAILABLE;
import static com.fasterxml.jackson.core.JsonToken.START_ARRAY;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.github.mizosoft.methanol.adapter.jackson.internal.JacksonAdapterUtils;
import com.github.mizosoft.methanol.internal.extensions.PublisherBodySubscriber;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.flow.ForwardingSubscriber;
import java.io.IOException;
import java.lang.reflect.Type;
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

  JacksonFluxSubscriber(ObjectMapper mapper, Type elementType, JsonParser parser) {
    this.mapper = mapper;
    this.reader = mapper.readerFor(mapper.constructType(elementType));
    this.parser = parser;
  }

  @Override
  protected Subscriber<List<ByteBuffer>> downstream() {
    return downstream;
  }

  @Override
  public CompletionStage<Flux<T>> getBody() {
    return CompletableFuture.completedFuture(
        decodeFlow(new PossiblyDelayedPublisher(downstream.getBody())));
  }

  private Flux<T> decodeFlow(Flow.Publisher<List<ByteBuffer>> publisher) {
    JacksonTokenizer tokenizer = new JacksonTokenizer(parser);
    return JdkFlowAdapter.flowPublisherToFlux(publisher)
        .concatMapIterable(tokenizer)
        .concatWith(Mono.create(tokenizer::complete))
        .handle(
            (tokenBuffer, sink) -> {
              try {
                sink.next(reader.readValue(tokenBuffer.asParser(mapper)));
              } catch (IOException ioe) {
                sink.error(ioe);
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
          (p, t) -> {
            if (t != null) {
              subscribeWithError(subscriber, t);
            } else if (p != null) {
              p.subscribe(subscriber);
            }
          });
    }

    private static void subscribeWithError(
        Subscriber<? super List<ByteBuffer>> subscriber, Throwable error) {
      try {
        subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
      } catch (Throwable t) {
        error.addSuppressed(t);
      } finally {
        subscriber.onError(error);
      }
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
      feeder = ((ByteArrayFeeder) parser.getNonBlockingInputFeeder());
    }

    @Override
    public List<TokenBuffer> apply(List<ByteBuffer> buffers) {
      byte[] input = JacksonAdapterUtils.collectBytes(buffers);
      List<TokenBuffer> tokenized = new ArrayList<>();
      try {
        feeder.feedInput(input, 0, input.length);
        TokenBuffer tokenizedStream;
        while ((tokenizedStream = tokenizeStream()) != null) {
          tokenized.add(tokenizedStream);
        }
      } catch (IOException ioe) {
        throw JacksonAdapterUtils.throwUnchecked(ioe);
      }
      return Collections.unmodifiableList(tokenized);
    }

    void complete(MonoSink<TokenBuffer> sink) {
      feeder.endOfInput();
      try {
        TokenBuffer lastTokenizedStream = tokenizeStream();
        if (lastTokenizedStream != null) {
          sink.success(lastTokenizedStream);
        } else {
          sink.success();
        }
      } catch (IOException ioe) {
        sink.error(ioe);
      }
    }

    /** Appends tokens to current {@code TokenBuffer}, returning it if current stream tokenized. */
    private @Nullable TokenBuffer tokenizeStream() throws IOException {
      while (true) {
        JsonToken token = parser.nextToken();
        if (token == null || token == NOT_AVAILABLE) {
          return null;
        }

        // update depth counters
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
            break; // not interested
        }

        // if this is an array document we copy everything but the enclosing `[]`
        if (objectDepth > 0
            || ((token != START_ARRAY || arrayDepth > 1)
                && (token != END_ARRAY || arrayDepth >= 1))) {
          // check if it's a new tokenBuffer
          if (currentTokenBuffer == null) {
            currentTokenBuffer = new TokenBuffer(parser);
          }
          currentTokenBuffer.copyCurrentEvent(parser);
        }

        // tokenize if it's a "complete" direct child of the document
        if (objectDepth == 0 && arrayDepth <= 1 && (token.isScalarValue() || token.isStructEnd())) {
          TokenBuffer finishedTokenBuffer = currentTokenBuffer;
          currentTokenBuffer = null;
          return finishedTokenBuffer;
        }
      }
    }
  }
}
