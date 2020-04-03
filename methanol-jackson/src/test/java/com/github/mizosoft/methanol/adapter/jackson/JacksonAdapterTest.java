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

import static com.github.mizosoft.methanol.adapter.jackson.JacksonAdapterFactory.createDecoder;
import static com.github.mizosoft.methanol.adapter.jackson.JacksonAdapterFactory.createEncoder;
import static com.github.mizosoft.methanol.testutils.TestUtils.NOOP_SUBSCRIPTION;
import static com.github.mizosoft.methanol.testutils.TestUtils.lines;
import static com.github.mizosoft.methanol.testutils.TestUtils.load;
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
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.testutils.BodyCollector;
import com.github.mizosoft.methanol.testutils.BufferTokenizer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.SubmissionPublisher;
import org.junit.jupiter.api.Test;

class JacksonAdapterTest {

  @Test
  void isCompatibleWith_anyApplicationJson() {
    for (var c : List.of(JacksonAdapterFactory.createEncoder(), JacksonAdapterFactory
        .createDecoder())) {
      assertTrue(c.isCompatibleWith(MediaType.of("application", "json")));
      assertTrue(c.isCompatibleWith(MediaType.of("application", "json").withCharset(UTF_8)));
      assertTrue(c.isCompatibleWith(MediaType.of("application", "*")));
      assertTrue(c.isCompatibleWith(MediaType.of("*", "*")));
      assertFalse(c.isCompatibleWith(MediaType.of("text", "*")));
    }
  }

  @Test
  void unsupportedConversion_encoder() {
    var encoder = JacksonAdapterFactory.createEncoder();
    assertThrows(UnsupportedOperationException.class,
        () -> encoder.toBody(new Point(1, 2), MediaType.of("text", "plain")));
  }

  @Test
  void unsupportedConversion_decoder() {
    var decoder = JacksonAdapterFactory.createDecoder();
    var textPlain = MediaType.of("text", "plain");
    assertThrows(UnsupportedOperationException.class,
        () -> decoder.toObject(new TypeRef<Point>() {}, textPlain));
    assertThrows(UnsupportedOperationException.class,
        () -> decoder.toDeferredObject(new TypeRef<Point>() {}, textPlain));
  }

  @Test
  void serializeJson() {
    var bean = new Bean("beans are boring");
    var body = JacksonAdapterFactory.createEncoder().toBody(bean, null);
    var expected = "{\"value\":\"beans are boring\"}";
    assertEquals(expected, toUtf8(body));
  }

  @Test
  void serializeJson_utf16() {
    var bean = new Bean("beans are boring");
    var body = JacksonAdapterFactory.createEncoder().toBody(bean, MediaType.parse("application/json; charset=utf-16"));
    var expected = "{\"value\":\"beans are boring\"}";
    assertEquals(expected, toString(body, UTF_16));
  }

  @Test
  void serializeJson_customSerializer() {
    var mapper = new JsonMapper()
        .registerModule(new SimpleModule().addSerializer(Point.class, new PointSerializer()));
    var point = new Point(1, 2);
    var body = createEncoder(mapper).toBody(point, null);
    assertEquals("[1,2]", toUtf8(body));
  }

  @Test
  void serializeJson_typeWithGenerics() {
    var mapper = new JsonMapper()
        .registerModule(new SimpleModule().addSerializer(Point.class, new PointSerializer()));
    var pointList = List.of(new Point(1, 2), new Point(2, 1), new Point(0, 0));
    var body = createEncoder(mapper).toBody(pointList, null);
    assertEquals("[[1,2],[2,1],[0,0]]", toUtf8(body));
  }

  @Test
  void serializeJson_customSettings() {
    var mapper = configuredMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .setDefaultPropertyInclusion(Include.NON_NULL);
    var keanu = new AwesomePerson("Keanu", null, 55); // You know it's Reeves!
    var body = createEncoder(mapper).toBody(keanu, null);
    var expected =
             "{\n"
            + "  \"firstName\" : \"Keanu\",\n"
            + "  \"age\" : 55\n"
            + "}";
    assertLinesMatch(lines(expected), lines(toUtf8(body)));
  }

  @Test
  void deserializeJson() {
    var subscriber = JacksonAdapterFactory.createDecoder().toObject(
        new TypeRef<Bean>() {}, null);
    var json = "{\"value\":\"beans are boring\"}";
    var bean = publishUtf8(subscriber, json);
    assertEquals(bean.value , "beans are boring");
  }

  @Test
  void deserializeJson_utf16() {
    var subscriber = JacksonAdapterFactory.createDecoder().toObject(
        new TypeRef<Bean>() {}, MediaType.parse("application/json; charset=utf-16"));
    var json = "{\"value\":\"beans are boring\"}";
    var bean = publish(subscriber, json, UTF_16);
    assertEquals(bean.value, "beans are boring");
  }

  @Test
  void deserializeJson_utf16_stressed() {
    var aladinText = new String(load(getClass(), "/aladin_utf8.txt"), UTF_8);
    var jsonUtf16 = UTF_16.encode("{\"aladin\":\"" + aladinText + "\"}");
    var mapper = new JsonMapper.Builder(new JsonMapper())
        .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
        .build();
    var subscriber = JacksonAdapterFactory.createDecoder(mapper)
        .toObject(new TypeRef<Map<String, String>>() {}, MediaType.parse("application/json; charset=utf-16"));
    var executor = Executors.newFixedThreadPool(8);
    int[] buffSizes = {1, 32, 555, 1024, 21};
    int[] listSizes = {1, 3, 1};
    try (var publisher = new SubmissionPublisher<List<ByteBuffer>>(executor, Integer.MAX_VALUE)) {
      publisher.subscribe(subscriber);
      BufferTokenizer.tokenizeToLists(jsonUtf16, buffSizes, listSizes)
          .forEach(publisher::submit);
    } finally {
      executor.shutdown();
    }
    var body = subscriber.getBody().toCompletableFuture().join();
    var receivedText = body.get("aladin");
    assertNotNull(body);
    assertLinesMatch(lines(aladinText), lines(receivedText));
  }

  @Test
  void deserializeJson_customDeserializer() {
    var mapper = new JsonMapper()
        .registerModule(new SimpleModule().addDeserializer(Point.class, new PointDeserializer()));
    var subscriber = createDecoder(mapper).toObject(new TypeRef<Point>() {}, null);
    var point = publishUtf8(subscriber, "[1, 2]");
    assertEquals(point.x, 1);
    assertEquals(point.y, 2);
  }

  @Test
  void deserializeJson_typeWithGenerics() {
    var mapper = new JsonMapper()
        .registerModule(new SimpleModule().addDeserializer(Point.class, new PointDeserializer()));
    var subscriber = JacksonAdapterFactory.createDecoder(mapper)
        .toObject(new TypeRef<List<Point>>() {}, null);
    var pointList = publishUtf8(subscriber, "[[1,2],[2,1],[0,0]]");
    var expected = List.of(new Point(1, 2), new Point(2, 1), new Point(0, 0));
    assertEquals(expected, pointList);
  }

  @Test
  void deserializeJson_customSettings() {
    var mapper = configuredMapper()
        .enable(Feature.ALLOW_COMMENTS)
        .enable(Feature.ALLOW_SINGLE_QUOTES)
        .enable(Feature.ALLOW_UNQUOTED_FIELD_NAMES);
    var subscriber = createDecoder(mapper).toObject(new TypeRef<AwesomePerson>() {}, null);
    var nonStdJson =
             "{\n"
            + "  firstName: 'Keanu',\n"
            + "  lastName: 'Reeves',\n"
            + "  age: 55 // bruuhh, he hasn't aged a bit\n"
            + "}";
    var keanu = publishUtf8(subscriber, nonStdJson);
    assertEquals(keanu.firstName, "Keanu");
    assertEquals(keanu.lastName, "Reeves");
    assertEquals(keanu.age, 55);
  }

  @Test
  void deserializeJson_deferred() {
    var subscriber = JacksonAdapterFactory.createDecoder()
        .toDeferredObject(new TypeRef<Bean>() {}, null);
    var beanSupplier = subscriber.getBody().toCompletableFuture().getNow(null);
    assertNotNull(beanSupplier);
    var json = "{\"value\":\"beans are boring\"}";
    new Thread(() -> {
      subscriber.onSubscribe(NOOP_SUBSCRIPTION);
      subscriber.onNext(List.of(UTF_8.encode(json)));
      subscriber.onComplete();
    }).start();
    var bean = beanSupplier.get();
    assertEquals(bean.value, "beans are boring");
  }

  @Test
  void deserializeJson_deferredWithError() {
    var subscriber = JacksonAdapterFactory.createDecoder()
        .toDeferredObject(new TypeRef<Bean>() {}, null);
    var beanSupplier = subscriber.getBody().toCompletableFuture().getNow(null);
    assertNotNull(beanSupplier);
    new Thread(() -> {
      subscriber.onSubscribe(NOOP_SUBSCRIPTION);
      subscriber.onError(new IOException("Ops"));
    }).start();
    assertThrows(UncheckedIOException.class, beanSupplier::get);
  }

  @Test
  void deserializeJson_badJson() {
    var subscriber = JacksonAdapterFactory
        .createDecoder().toObject(new TypeRef<Bean>() {}, null);
    var badJson = "{\"value\":\"beans\"+\"are\"+\"boring\"}";
    var ex = assertThrows(CompletionException.class, () -> publishUtf8(subscriber, badJson));
    var cause = ex.getCause();
    assertNotNull(cause);
    assertTrue(JsonProcessingException.class.isAssignableFrom(cause.getClass()));
  }

  @Test
  void deserializeJson_fromDeserializerThatNeedsParserCodec() {
    var mapper = new ObjectMapper()
        .registerModule(new SimpleModule().addDeserializer(Bean.class, new BeanNodeDeserializer()));
    var subscriber = createDecoder(mapper).toObject(TypeRef.from(Bean.class), null);
    var bean = publishUtf8(subscriber, "{\"value\": \"beans are boring\"}");
    assertEquals("beans are boring", bean.value);
  }

  private static String toUtf8(BodyPublisher publisher) {
    return toString(publisher, UTF_8);
  }

  private static String toString(BodyPublisher publisher, Charset charset) {
    return charset.decode(BodyCollector.collect(publisher)).toString();
  }

  private static <T> T publishUtf8(BodySubscriber<T> subscriber, String body) {
    return publish(subscriber, body, UTF_8);
  }

  private static <T> T publish(BodySubscriber<T> subscriber, String body, Charset charset) {
    subscriber.onSubscribe(NOOP_SUBSCRIPTION);
    subscriber.onNext(List.of(charset.encode(body)));
    subscriber.onComplete();
    return subscriber.getBody().toCompletableFuture().join();
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

    public Bean() {
    }

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
