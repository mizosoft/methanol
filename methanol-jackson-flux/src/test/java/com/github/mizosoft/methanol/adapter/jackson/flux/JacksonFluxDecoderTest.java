/*
 * Copyright (c) 2021 Moataz Abdelnasser
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

import static com.github.mizosoft.methanol.adapter.jackson.flux.JacksonFluxAdapterFactory.createDecoder;
import static com.github.mizosoft.methanol.testutils.adapter.BodyAdapterVerifier.verifyThat;
import static java.nio.charset.StandardCharsets.UTF_16;
import static org.assertj.core.api.Assertions.from;
import static reactor.adapter.JdkFlowAdapter.flowPublisherToFlux;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.github.mizosoft.methanol.TypeRef;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class JacksonFluxDecoderTest {
  @Test
  void compatibleMediaTypes() {
    verifyThat(createDecoder())
        .isCompatibleWith("application/json")
        .isCompatibleWith("application/json; charset=utf-8")
        .isCompatibleWith("application/*")
        .isCompatibleWith("*/*");
  }

  @Test
  void incompatibleMediaTypes() {
    verifyThat(createDecoder())
        .isNotCompatibleWith("text/html")
        .isNotCompatibleWith("text/*");
  }

  @Test
  void deserializeMono() {
    verifyThat(createDecoder())
        .converting(new TypeRef<Mono<Point>>() {})
        .withBody("{\"x\":1, \"y\":2}")
        .completedBody()
        .returns(new Point(1, 2), from(Mono::block));
  }

  @Test
  void deserializeMonoWithUtf16() {
    verifyThat(createDecoder())
        .converting(new TypeRef<Mono<Point>>() {})
        .withMediaType("application/json; charset=utf-16")
        .withBody("{\"x\":1, \"y\":2}", UTF_16)
        .completedBody()
        .returns(new Point(1, 2), from(Mono::block));
  }

  @Test
  void deserializeMonoWithCustomDeserializer() {
    var mapper = new JsonMapper()
        .registerModule(
            new SimpleModule().addDeserializer(Point.class, new CompactPointDeserializer()));
    verifyThat(createDecoder(mapper))
        .converting(new TypeRef<Mono<Point>>() {})
        .withBody("[1, 2]")
        .completedBody()
        .returns(new Point(1, 2), from(Mono::block));
  }

  @Test
  void deserializeFlux() {
    verifyThat(createDecoder())
        .converting(new TypeRef<Flux<Point>>() {})
        .withBody("[{\"x\":1, \"y\":2}, {\"x\":3, \"y\":4}]")
        .completedBody()
        .extracting(Flux::toIterable, Assertions.ITERABLE)
        .containsExactly(new Point(1, 2), new Point(3, 4));
  }

  @Test
  void deserializeFluxWithUtf16() {
    verifyThat(createDecoder())
        .converting(new TypeRef<Flux<Point>>() {})
        .withMediaType("application/json; charset=utf-16")
        .withBody("[{\"x\":1, \"y\":2}, {\"x\":3, \"y\":4}]", UTF_16)
        .completedBody()
        .extracting(Flux::toIterable, Assertions.ITERABLE)
        .containsExactly(new Point(1, 2), new Point(3, 4));
  }

  @Test
  void deserializeFluxWithCustomDeserializer() {
    var mapper = new JsonMapper()
        .registerModule(
            new SimpleModule().addDeserializer(Point.class, new CompactPointDeserializer()));
    verifyThat(createDecoder(mapper))
        .converting(new TypeRef<Flux<Point>>() {})
        .withBody("[[1, 2], [3, 4]]")
        .completedBody()
        .extracting(Flux::toIterable, Assertions.ITERABLE)
        .containsExactly(new Point(1, 2), new Point(3, 4));
  }

  @Test
  void deserializeReactiveStreamsPublisher() {
    verifyThat(createDecoder())
        .converting(new TypeRef<org.reactivestreams.Publisher<Point>>() {})
        .withBody("[{\"x\":1, \"y\":2}, {\"x\":3, \"y\":4}]")
        .completedBody()
        .extracting(publisher -> Flux.from(publisher).toIterable(), Assertions.ITERABLE)
        .containsExactly(new Point(1, 2), new Point(3, 4));
  }

  @Test
  void deserializeFlowPublisher() {
    verifyThat(createDecoder())
        .converting(new TypeRef<java.util.concurrent.Flow.Publisher<Point>>() {})
        .withBody("[{\"x\":1, \"y\":2}, {\"x\":3, \"y\":4}]")
        .completedBody()
        .extracting(
            publisher -> flowPublisherToFlux(publisher).toIterable(), Assertions.ITERABLE)
        .containsExactly(new Point(1, 2), new Point(3, 4));
  }

  /** Test that the used parser has access to the underlying ObjectMapper. */
  @Test
  void deserializeMonoWithCustomDeserializerThatNeedsParserCodec() {
    var mapper = new ObjectMapper()
        .registerModule(
            new SimpleModule().addDeserializer(Point.class, new PointTreeNodeDeserializer()));
    verifyThat(createDecoder(mapper))
        .converting(new TypeRef<Mono<Point>>() {})
        .withBody("{\"x\":1, \"y\":2}")
        .completedBody()
        .returns(new Point(1, 2), from(Mono::block));
  }

  @Test
  void deserializeMonoWithBadJson() {
    verifyThat(createDecoder())
        .converting(new TypeRef<Mono<Point>>() {})
        .withBody("{x:\"1\", y:\"2\"") // Missing enclosing bracket
        .completedBody()
        .extracting(Mono::toFuture, Assertions.COMPLETABLE_FUTURE)
        .failsWithin(Duration.ofSeconds(20))
        .withThrowableOfType(ExecutionException.class)
        .withCauseInstanceOf(JsonProcessingException.class);
  }

  @Test
  void deserializeFluxWithBadJson() {
    verifyThat(createDecoder())
        .converting(new TypeRef<Mono<Point>>() {})
        .withBody("[{\"x\":1, \"y\":2}, {\"x\":3, \"y\":4}") // Missing enclosing bracket
        .completedBody()
        .extracting(Mono::toFuture, Assertions.COMPLETABLE_FUTURE)
        .failsWithin(Duration.ofSeconds(20))
        .withThrowableOfType(ExecutionException.class)
        .withCauseInstanceOf(JsonProcessingException.class);
  }

  @Test
  void deserializeWithUnsupportedType() {
    class NotAPublisher {}

    verifyThat(createDecoder())
        .converting(NotAPublisher.class)
        .isNotSupported();
  }

  @Test
  void deserializeWithUnsupportedMediaType() {
    verifyThat(createDecoder())
        .converting(new TypeRef<Mono<Point>>() {})
        .withMediaType("application/xml")
        .isNotSupported();
  }
}
