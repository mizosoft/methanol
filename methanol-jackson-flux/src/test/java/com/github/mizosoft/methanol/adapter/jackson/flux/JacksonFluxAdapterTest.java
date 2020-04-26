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

import static com.github.mizosoft.methanol.adapter.jackson.flux.JacksonFluxAdapterFactory.createDecoder;
import static com.github.mizosoft.methanol.adapter.jackson.flux.JacksonFluxAdapterFactory.createEncoder;
import static com.github.mizosoft.methanol.testutils.TestUtils.lines;
import static java.nio.charset.StandardCharsets.UTF_16;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonCreator.Mode;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.testutils.BodyCollector;
import com.github.mizosoft.methanol.testutils.TestSubscriber;
import java.io.IOException;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.reactivestreams.FlowAdapters;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class JacksonFluxAdapterTest {

  @Test
  void isCompatibleWith_anyApplicationJson() {
    for (var c : List.of(createEncoder(), createDecoder())) {
      assertTrue(c.isCompatibleWith(MediaType.of("application", "json")));
      assertTrue(c.isCompatibleWith(MediaType.of("application", "json").withCharset(UTF_8)));
      assertTrue(c.isCompatibleWith(MediaType.of("application", "*")));
      assertTrue(c.isCompatibleWith(MediaType.of("*", "*")));
      assertFalse(c.isCompatibleWith(MediaType.of("text", "*")));
    }
  }

  @Test
  void unsupportedConversion_encoder() {
    var encoder = createEncoder();
    assertThrows(UnsupportedOperationException.class,
        () -> encoder.toBody(Mono.just("Mono text"), MediaType.of("text", "plain")));
    assertThrows(UnsupportedOperationException.class,
        () -> encoder.toBody(List.of("Not even a publisher!"), MediaType.of("application", "json")));
  }

  @Test
  void unsupportedConversion_decoder() {
    var decoder = createDecoder();
    var textPlain = MediaType.of("text", "plain");
    assertThrows(UnsupportedOperationException.class,
        () -> decoder.toObject(new TypeRef<Mono<String>>() {}, textPlain));
    assertThrows(UnsupportedOperationException.class,
        () -> decoder.toDeferredObject(new TypeRef<Flux<String>>() {}, textPlain));
    assertThrows(UnsupportedOperationException.class,
        () -> decoder.toObject(new TypeRef<List<String>>() {}, MediaType.of("application", "json")));
  }

  @Test
  void serializeMono() {
    var mono = Mono.just(new Bean("beans are boring"));
    var body = createEncoder().toBody(mono, null);
    var expected = "{\"value\":\"beans are boring\"}";
    assertEquals(expected, toUtf8(body));
  }

  @Test
  void serializeFlux() {
    var flux = Flux.just(
        new Bean("beans are boring"),
        new Bean("beans are super-duper boring"),
        new Bean("beans are flippin-dippin finger-sniffin boring"));
    var body = createEncoder().toBody(flux, null);
    var expected =
              "["
            + "{\"value\":\"beans are boring\"},"
            + "{\"value\":\"beans are super-duper boring\"},"
            + "{\"value\":\"beans are flippin-dippin finger-sniffin boring\"}"
            + "]";
    assertEquals(expected, toUtf8(body));
  }

  @Test
  void serializeFlowPublisher() {
    var flux = Flux.just(
        new Bean("beans are boring"),
        new Bean("beans are super-duper boring"),
        new Bean("beans are flippin-dippin finger-sniffin boring"));
    var body = createEncoder().toBody(FlowAdapters.toFlowPublisher(flux), null);
    var expected =
              "["
            + "{\"value\":\"beans are boring\"},"
            + "{\"value\":\"beans are super-duper boring\"},"
            + "{\"value\":\"beans are flippin-dippin finger-sniffin boring\"}"
            + "]";
    assertEquals(expected, toUtf8(body));
  }

  @Test
  void serializeFlux_utf16() {
    var flux = Flux.just(
        new Bean("beans are boring"),
        new Bean("beans are super-duper boring"),
        new Bean("beans are flippin-dippin finger-sniffin boring"));
    var body = createEncoder().toBody(flux, MediaType.parse("application/json; charset=utf-16"));
    var expected =
              "["
            + "{\"value\":\"beans are boring\"},"
            + "{\"value\":\"beans are super-duper boring\"},"
            + "{\"value\":\"beans are flippin-dippin finger-sniffin boring\"}"
            + "]";
    assertEquals(expected, toString(body, UTF_16));
  }

  @Test
  void serializeMono_customSerializer() {
    var mapper = new JsonMapper()
        .registerModule(new SimpleModule().addSerializer(Point.class, new PointSerializer()));
    var mono = Mono.just(new Point(1, 2));
    var body = createEncoder(mapper).toBody(mono, null);
    assertEquals("[1,2]", toUtf8(body));
  }

  @Test
  void serializeMono_customSettings() {
    var mapper = configuredMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .setDefaultPropertyInclusion(Include.NON_NULL);
    var keanuMono = Mono.just(new AwesomePerson("Keanu", null, 55)); // You know it's Reeves!
    var body = createEncoder(mapper).toBody(keanuMono, null);
    var expected =
                "{\n"
              + "  \"firstName\" : \"Keanu\",\n"
              + "  \"age\" : 55\n"
              + "}";
    assertLinesMatch(lines(expected), lines(toUtf8(body)));
  }

  @Test
  void serializeWithError() {
    class NotSerializableByJacksonByDefault {
      final int i = 1;

      private NotSerializableByJacksonByDefault() {}
    }
    var mono = Mono.just(new NotSerializableByJacksonByDefault());
    var body = createEncoder().toBody(mono, null);
    var subscriber = new TestSubscriber<ByteBuffer>();
    body.subscribe(subscriber);
    subscriber.awaitComplete();
    assertEquals(1, subscriber.errors);
    assertTrue(subscriber.lastError instanceof InvalidDefinitionException,
        String.valueOf(subscriber.lastError));
  }

  @Test
  void deserializeMono() {
    var subscriber = createDecoder().toObject(new TypeRef<Mono<Bean>>() {}, null);
    var json = "{\"value\":\"beans are boring\"}";
    var mono = getBodyOrNull(subscriber);
    assertNotNull(mono);

    CompletableFuture.runAsync(() -> publishUtf8(subscriber, json));
    assertEquals(Optional.of("beans are boring"), mono.blockOptional().map(Bean::getValue));
  }

  @Test
  void deserializeMono_utf16() {
    var subscriber = createDecoder()
        .toObject(new TypeRef<Mono<Bean>>() {}, MediaType.parse("application/json; charset=utf-16"));
    var json = "{\"value\":\"beans are boring\"}";
    var mono = getBodyOrNull(subscriber);
    assertNotNull(mono);

    CompletableFuture.runAsync(() -> publish(subscriber, json, UTF_16));
    assertEquals(Optional.of("beans are boring"), mono.blockOptional().map(Bean::getValue));
  }

  @Test
  void deserializeMono_customSettings() {
    var mapper = configuredMapper()
        .enable(Feature.ALLOW_COMMENTS)
        .enable(Feature.ALLOW_SINGLE_QUOTES)
        .enable(Feature.ALLOW_UNQUOTED_FIELD_NAMES);
    var subscriber = createDecoder(mapper).toObject(new TypeRef<Mono<AwesomePerson>>() {}, null);
    var nonStdJson =
              "{\n"
            + "  firstName: 'Keanu',\n"
            + "  lastName: 'Reeves',\n"
            + "  age: 55 // bruuhh, he hasn't aged a bit\n"
            + "}";
    var keanuMono = getBodyOrNull(subscriber);
    assertNotNull(keanuMono);

    CompletableFuture.runAsync(() -> publishUtf8(subscriber, nonStdJson));
    var keanu = keanuMono.block();
    assertNotNull(keanu);
    assertEquals("Keanu", keanu.firstName);
    assertEquals("Reeves", keanu.lastName);
    assertEquals(55, keanu.age);
  }

  @Test
  void deserializeFlux() {
    var mapper = new JsonMapper()
        .registerModule(new SimpleModule().addDeserializer(Point.class, new PointDeserializer()));
    var subscriber = createDecoder(mapper).toObject(new TypeRef<Flux<Point>>() {}, null);
    var flux = getBodyOrNull(subscriber);
    assertNotNull(flux);

    CompletableFuture.runAsync(() -> publishUtf8(subscriber, "[[1,2],[2,1],[0,0]]"));
    var expected = List.of(new Point(1, 2), new Point(2, 1), new Point(0, 0));
    assertEquals(expected, flux.collectList().block());
  }

  @Test
  void deserializeAsRSPublisher() {
    var mapper = new JsonMapper()
        .registerModule(new SimpleModule().addDeserializer(Point.class, new PointDeserializer()));
    var subscriber = createDecoder(mapper)
        .toObject(new TypeRef<org.reactivestreams.Publisher<Point>>() {}, null);
    var publisher = getBodyOrNull(subscriber);
    assertNotNull(publisher);

    CompletableFuture.runAsync(() -> publishUtf8(subscriber, "[[1,2],[2,1],[0,0]]"));
    var expected = List.of(new Point(1, 2), new Point(2, 1), new Point(0, 0));
    assertEquals(expected, Flux.from(publisher).collectList().block());
  }

  @Test
  void deserializeAsFlowPublisher() {
    var mapper = new JsonMapper()
        .registerModule(new SimpleModule().addDeserializer(Point.class, new PointDeserializer()));
    var subscriber = createDecoder(mapper)
        .toObject(new TypeRef<Flow.Publisher<Point>>() {}, null);
    var flowPublisher = getBodyOrNull(subscriber);
    assertNotNull(flowPublisher);

    CompletableFuture.runAsync(() -> publishUtf8(subscriber, "[[1,2],[2,1],[0,0]]"));
    var expected = List.of(new Point(1, 2), new Point(2, 1), new Point(0, 0));
    assertEquals(expected, JdkFlowAdapter.flowPublisherToFlux(flowPublisher).collectList().block());
  }

  @Test
  void deserializeFlux_withNestedObjects() {
    var subscriber = createDecoder().toObject(new TypeRef<Flux<Bean>>() {}, null);
    var flux = getBodyOrNull(subscriber);
    assertNotNull(flux);

    var json =
              "["
            + "{\"value\":\"beans are boring\"},"
            + "{\"value\":\"beans are super-duper boring\"},"
            + "{\"value\":\"beans are flippin-dippin finger-sniffin boring\"}"
            + "]";
    CompletableFuture.runAsync(() -> publishUtf8(subscriber, json));
    var expectedValues = List.of(
        "beans are boring",
        "beans are super-duper boring",
        "beans are flippin-dippin finger-sniffin boring");
    assertEquals(expectedValues, flux.map(Bean::getValue).collectList().block());
  }

  @Test
  void deserializeMono_badJson() {
    var bodySubscriber = createDecoder().toObject(new TypeRef<Mono<Bean>>() {}, null);
    var mono = getBodyOrNull(bodySubscriber);
    assertNotNull(mono);

    var badJson = "{\"value\":\"beans\"+\"are\"+\"boring\"}";
    var testSubscriber = new TestSubscriber<Bean>();
    mono.subscribe(FlowAdapters.toSubscriber(testSubscriber));
    CompletableFuture.runAsync(() -> publishUtf8(bodySubscriber, badJson));
    testSubscriber.awaitComplete();
    assertEquals(1, testSubscriber.errors);
    assertTrue(testSubscriber.lastError instanceof JsonProcessingException);
  }

  @Test
  void deserializeFlux_badJson() {
    var mapper = new JsonMapper()
        .registerModule(new SimpleModule().addDeserializer(Point.class, new PointDeserializer()));
    var bodySubscriber = createDecoder(mapper).toObject(new TypeRef<Flux<Point>>() {}, null);
    var flux = getBodyOrNull(bodySubscriber);
    assertNotNull(flux);

    var badJson = "[[1,2],[2,1],[0,0],[1,,1]";
    var testSubscriber = new TestSubscriber<Point>();
    flux.subscribe(FlowAdapters.toSubscriber(testSubscriber));
    CompletableFuture.runAsync(() -> publishUtf8(bodySubscriber, badJson));
    testSubscriber.awaitComplete();
    assertEquals(1, testSubscriber.errors);
    assertTrue(testSubscriber.lastError instanceof JsonProcessingException);
  }

  @Test
  void deserializeMono_fromDeserializerThatNeedsParserCodec() {
    var mapper = new ObjectMapper()
        .registerModule(new SimpleModule().addDeserializer(Bean.class, new BeanNodeDeserializer()));
    var subscriber = createDecoder(mapper).toObject(new TypeRef<Mono<Bean>>() {}, null);
    var mono = getBodyOrNull(subscriber);
    assertNotNull(mono);

    CompletableFuture.runAsync(() -> publishUtf8(subscriber, "{\"value\": \"beans are boring\"}"));
    assertEquals(Optional.of("beans are boring"), mono.blockOptional().map(Bean::getValue));
  }

  private static <T> T getBodyOrNull(BodySubscriber<T> subscriber) {
    return subscriber.getBody().toCompletableFuture().getNow(null);
  }

  private static void publishUtf8(BodySubscriber<?> subscriber, String body) {
    publish(subscriber, body, UTF_8);
  }

  private static void publish(BodySubscriber<?> subscriber, String body, Charset charset) {
    Mono.just(List.of(charset.encode(body))).subscribe(FlowAdapters.toSubscriber(subscriber));
  }

  private static String toUtf8(BodyPublisher publisher) {
    return toString(publisher, UTF_8);
  }

  private static String toString(BodyPublisher publisher, Charset charset) {
    return charset.decode(BodyCollector.collect(publisher)).toString();
  }

  private static ObjectMapper configuredMapper() {
    return new JsonMapper()
        .disable(MapperFeature.AUTO_DETECT_GETTERS)
        .disable(MapperFeature.AUTO_DETECT_SETTERS)
        .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
        .setVisibility(PropertyAccessor.ALL, Visibility.ANY);
  }

  // [de]serializable by jackson without configuration
  private static class Bean {

    private String value;

    public Bean() {}

    public Bean(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }

    public void setValue(String value) {
      this.value = value;
    }
  }

  private static class AwesomePerson {

    String firstName;
    String lastName;
    int age;

    @JsonCreator(mode = Mode.PROPERTIES)
    AwesomePerson(
        @JsonProperty("firstName") String firstName,
        @JsonProperty("lastName") String lastName,
        @JsonProperty("age") int age) {
      this.firstName = firstName;
      this.lastName = lastName;
      this.age = age;
    }
  }

  private static class Point {

    int x, y;

    Point(int x, int y) {
      this.x = x;
      this.y = y;
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof Point && ((Point) obj).x == x && ((Point) obj).y == y;
    }

    @Override
    public String toString() {
      return "[" + x + ", " + y + "]";
    }
  }

  private static final class PointSerializer extends StdSerializer<Point> {

    PointSerializer() {
      super(Point.class);
    }

    @Override
    public void serialize(Point value, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      gen.writeStartArray();
      gen.writeNumber(value.x);
      gen.writeNumber(value.y);
      gen.writeEndArray();
    }
  }

  private static final class PointDeserializer extends StdDeserializer<Point> {

    PointDeserializer() {
      super(Point.class);
    }

    @Override
    public Point deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      assertTrue(p.isExpectedStartArrayToken());
      var point = new Point(p.nextIntValue(-1), p.nextIntValue(-1));
      p.nextToken();
      return point;
    }
  }

  // Reads Bean from JsonNode that needs JsonParser to be have an ObjectCodec
  private static final class BeanNodeDeserializer extends StdDeserializer<Bean> {

    BeanNodeDeserializer() {
      super(Bean.class);
    }

    @Override
    public Bean deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      JsonNode node = p.readValueAsTree();
      return new Bean(node.get("value").textValue());
    }
  }
}
