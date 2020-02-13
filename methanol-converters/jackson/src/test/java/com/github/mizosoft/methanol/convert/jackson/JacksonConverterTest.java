package com.github.mizosoft.methanol.convert.jackson;

import static com.github.mizosoft.methanol.convert.jackson.JacksonConverters.createOfRequest;
import static com.github.mizosoft.methanol.convert.jackson.JacksonConverters.createOfResponse;
import static com.github.mizosoft.methanol.testing.TestUtils.NOOP_SUBSCRIPTION;
import static java.nio.charset.StandardCharsets.UTF_16;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.TypeReference;
import com.github.mizosoft.methanol.testing.BodyCollector;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class JacksonConverterTest {

  @Test
  void isCompatibleWith_anyApplicationJson() {
    for (var c : List.of(createOfRequest(), createOfResponse())) {
      assertTrue(c.isCompatibleWith(MediaType.of("application", "json")));
      assertTrue(c.isCompatibleWith(MediaType.of("application", "json").withCharset(UTF_8)));
      assertTrue(c.isCompatibleWith(MediaType.of("application", "*")));
      assertTrue(c.isCompatibleWith(MediaType.of("*", "*")));
      assertFalse(c.isCompatibleWith(MediaType.of("text", "*")));
    }
  }

  @Test
  void unsupportedConversion_ofRequest() {
    var ofReq = createOfRequest();
    assertThrows(UnsupportedOperationException.class,
        () -> ofReq.toBody(new Point(1, 2), MediaType.of("text", "plain")));
  }

  @Test
  void unsupportedConversion_ofResponse() {
    var ofRes = createOfResponse();
    var textPlain = MediaType.of("text", "plain");
    assertThrows(UnsupportedOperationException.class,
        () -> ofRes.toObject(new TypeReference<Point>() {}, textPlain));
    assertThrows(UnsupportedOperationException.class,
        () -> ofRes.toDeferredObject(new TypeReference<Point>() {}, textPlain));
  }

  @Test
  void serializeJson() {
    var bean = new Bean("beans are boring");
    var body = createOfRequest().toBody(bean, null);
    var expected = "{\"value\":\"beans are boring\"}";
    assertEquals(expected, toUtf8(body));
  }

  @Test
  void serializeJson_utf16() {
    var bean = new Bean("beans are boring");
    var body = createOfRequest().toBody(bean, MediaType.parse("application/json; charset=utf-16"));
    var expected = "{\"value\":\"beans are boring\"}";
    assertEquals(expected, toString(body, UTF_16));
  }

  @Test
  void serializeJson_customSerializer() {
    var mapper = new JsonMapper()
        .registerModule(new SimpleModule().addSerializer(Point.class, new PointSerializer()));
    var point = new Point(1, 2);
    var body = createOfRequest(mapper).toBody(point, null);
    assertEquals("[1,2]", toUtf8(body));
  }

  @Test
  void serializeJson_typeWithGenerics() {
    var mapper = new JsonMapper()
        .registerModule(new SimpleModule().addSerializer(Point.class, new PointSerializer()));
    var pointList = List.of(new Point(1, 2), new Point(2, 1), new Point(0, 0));
    var body = createOfRequest(mapper).toBody(pointList, null);
    assertEquals("[[1,2],[2,1],[0,0]]", toUtf8(body));
  }

  @Test
  void serializeJson_customSettings() {
    var mapper = configuredMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .setDefaultPropertyInclusion(Include.NON_NULL);
    var keanu = new AwesomePerson("Keanu", null, 55); // You know it's Reeves!
    var body = createOfRequest(mapper).toBody(keanu, null);
    var expected =
          "{\r\n"
        + "  \"firstName\" : \"Keanu\",\r\n"
        + "  \"age\" : 55\r\n"
        + "}";
    assertEquals(expected, toUtf8(body));
  }

  @Test
  void deserializeJson() {
    var subscriber = createOfResponse().toObject(
        new TypeReference<Bean>() {}, null);
    var json = "{\"value\":\"beans are boring\"}";
    var bean = publishUtf8(subscriber, json);
    assertEquals(bean.value , "beans are boring");
  }

  @Test
  void deserializeJson_utf16() {
    var subscriber = createOfResponse().toObject(
        new TypeReference<Bean>() {}, MediaType.parse("application/json; charset=utf-16"));
    var json = "{\"value\":\"beans are boring\"}";
    var bean = publish(subscriber, json, UTF_16);
    assertEquals(bean.value, "beans are boring");
  }

  @Test
  void deserializeJson_customDeserializer() {
    var mapper = new JsonMapper()
        .registerModule(new SimpleModule().addDeserializer(Point.class, new PointDeserializer()));
    var subscriber = createOfResponse(mapper).toObject(new TypeReference<Point>() {}, null);
    var point = publishUtf8(subscriber, "[1, 2]");
    assertEquals(point.x, 1);
    assertEquals(point.y, 2);
  }

  @Test
  void deserializeJson_typeWithGenerics() {
    var mapper = new JsonMapper()
        .registerModule(new SimpleModule().addDeserializer(Point.class, new PointDeserializer()));
    var subscriber = JacksonConverters.createOfResponse(mapper)
        .toObject(new TypeReference<List<Point>>() {}, null);
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
    var subscriber = createOfResponse(mapper).toObject(new TypeReference<AwesomePerson>() {}, null);
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
    var subscriber = JacksonConverters.createOfResponse()
        .toDeferredObject(new TypeReference<Bean>() {}, null);
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
    var subscriber = JacksonConverters.createOfResponse()
        .toDeferredObject(new TypeReference<Bean>() {}, null);
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
    var subscriber = createOfResponse().toObject(new TypeReference<Bean>() {}, null);
    var badJson = "{\"value\":\"beans\"+\"are\"+\"boring\"}";
    var ex = assertThrows(CompletionException.class, () -> publishUtf8(subscriber, badJson));
    var cause = ex.getCause();
    assertNotNull(cause);
    assertTrue(JsonProcessingException.class.isAssignableFrom(cause.getClass()));
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
}
