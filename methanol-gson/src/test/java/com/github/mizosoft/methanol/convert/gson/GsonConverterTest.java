/*
 * MIT License
 *
 * Copyright (c) 2019 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.convert.gson;

import static com.github.mizosoft.methanol.convert.gson.GsonConverterFactory.createOfRequest;
import static com.github.mizosoft.methanol.convert.gson.GsonConverterFactory.createOfResponse;
import static com.github.mizosoft.methanol.testing.TestUtils.NOOP_SUBSCRIPTION;
import static java.nio.charset.StandardCharsets.UTF_16;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.TypeReference;
import com.github.mizosoft.methanol.testing.BodyCollector;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.charset.Charset;
import java.util.List;
import org.junit.jupiter.api.Test;

class GsonConverterTest {

  @Test
  void isCompatibleWith_anyApplicationJson() {
    for (var c : List.of(GsonConverterFactory.createOfRequest(), GsonConverterFactory.createOfResponse())) {
      assertTrue(c.isCompatibleWith(MediaType.of("application", "json")));
      assertTrue(c.isCompatibleWith(MediaType.of("application", "json").withCharset(UTF_8)));
      assertTrue(c.isCompatibleWith(MediaType.of("application", "*")));
      assertTrue(c.isCompatibleWith(MediaType.of("*", "*")));
      assertFalse(c.isCompatibleWith(MediaType.of("text", "*")));
    }
  }

  @Test
  void unsupportedConversion_ofRequest() {
    var ofReq = GsonConverterFactory.createOfRequest();
    assertThrows(UnsupportedOperationException.class,
        () -> ofReq.toBody(new Point(1, 2), MediaType.of("text", "plain")));
  }

  @Test
  void unsupportedConversion_ofResponse() {
    var ofRes = GsonConverterFactory.createOfResponse();
    var textPlain = MediaType.of("text", "plain");
    assertThrows(UnsupportedOperationException.class,
        () -> ofRes.toObject(new TypeReference<Point>() {}, textPlain));
    assertThrows(UnsupportedOperationException.class,
        () -> ofRes.toDeferredObject(new TypeReference<Point>() {}, textPlain));
  }

  @Test
  void serializeJson() {
    var obama = new AwesomePerson("Barack", "Obama", 58);
    var body = GsonConverterFactory.createOfRequest().toBody(obama, null);
    var expected = "{\"firstName\":\"Barack\",\"lastName\":\"Obama\",\"age\":58}";
    assertEquals(expected, toUtf8(body));
  }

  @Test
  void serializeJson_utf16() {
    var obama = new AwesomePerson("Barack", "Obama", 58);
    var body = GsonConverterFactory.createOfRequest().toBody(obama, MediaType.parse("application/json; charset=utf-16"));
    var expected = "{\"firstName\":\"Barack\",\"lastName\":\"Obama\",\"age\":58}";
    assertEquals(expected, toString(body, UTF_16));
  }

  @Test
  void serializeJson_customAdapter() {
    var gson = new GsonBuilder()
        .registerTypeAdapter(Point.class, new PointAdapter())
        .create();
    var point = new Point(1, 2);
    var body = createOfRequest(gson).toBody(point, null);
    assertEquals("[1,2]", toUtf8(body));
  }

  @Test
  void serializeJson_typeWithGenerics() {
    var gson = new GsonBuilder()
        .registerTypeAdapter(Point.class, new PointAdapter())
        .create();
    var pointList = List.of(new Point(1, 2), new Point(2, 1), new Point(0, 0));
    var body = createOfRequest(gson).toBody(pointList, null);
    assertEquals("[[1,2],[2,1],[0,0]]", toUtf8(body));
  }

  @Test
  void serializeJson_customSettings() {
    var gson = new GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .create();
    var elon = new AwesomePerson("Elon", null, 48); // You know it's Musk!
    var body = createOfRequest(gson).toBody(elon, null);
    var expected =
          "{\n"
        + "  \"firstName\": \"Elon\",\n"
        + "  \"lastName\": null,\n"
        + "  \"age\": 48\n"
        + "}";
    assertEquals(expected, toUtf8(body));
  }

  @Test
  void deserializeJson() {
    var json = "{\"firstName\":\"Barack\",\"lastName\":\"Obama\",\"age\":58}";
    var subscriber = GsonConverterFactory
        .createOfResponse().toObject(new TypeReference<AwesomePerson>() {}, null);
    var obama = publishUtf8(subscriber, json);
    assertEquals(obama.firstName, "Barack");
    assertEquals(obama.lastName, "Obama");
    assertEquals(obama.age, 58);
  }

  @Test
  void deserializeJson_utf16() {
    var json = "{\"firstName\":\"Barack\",\"lastName\":\"Obama\",\"age\":58}";
    var subscriber = GsonConverterFactory.createOfResponse().toObject(
        new TypeReference<AwesomePerson>() {}, MediaType.parse("application/json; charset=utf-16"));
    var obama = publish(subscriber, json, UTF_16);
    assertEquals(obama.firstName, "Barack");
    assertEquals(obama.lastName, "Obama");
    assertEquals(obama.age, 58);
  }

  @Test
  void deserializeJson_customAdapter() {
    var gson = new GsonBuilder()
        .registerTypeAdapter(Point.class, new PointAdapter())
        .create();
    var subscriber = createOfResponse(gson).toObject(new TypeReference<Point>() {}, null);
    var point = publishUtf8(subscriber, "[1,2]");
    assertEquals(point.x, 1);
    assertEquals(point.y, 2);
  }

  @Test
  void deserializeJson_typeWithGenerics() {
    var gson = new GsonBuilder()
        .registerTypeAdapter(Point.class, new PointAdapter())
        .create();
    var subscriber = createOfResponse(gson).toObject(new TypeReference<List<Point>>() {}, null);
    var pointList = publishUtf8(subscriber, "[[1,2],[2,1],[0,0]]");
    var expected = List.of(new Point(1, 2), new Point(2, 1), new Point(0, 0));
    assertEquals(expected, pointList);
  }

  @Test
  void deserializeJson_customSettings() {
    var gson = new GsonBuilder()
        .setLenient()
        .create();
    var subscriber = createOfResponse(gson).toObject(new TypeReference<AwesomePerson>() {}, null);
    var nonStdJson =
          "{\n"
        + "  firstName: 'Elon',\n"
        + "  lastName: 'Musk',\n"
        + "  age: 48 // Can I ask Elon to adopt me?\n"
        + "}";
    var elon = publishUtf8(subscriber, nonStdJson);
    assertEquals(elon.firstName, "Elon");
    assertEquals(elon.lastName, "Musk");
    assertEquals(elon.age, 48);
  }

  @Test
  void deserializeJson_deferred() {
    var json = "{\"firstName\":\"Barack\",\"lastName\":\"Obama\",\"age\":58}";
    var subscriber = GsonConverterFactory.createOfResponse()
        .toDeferredObject(new TypeReference<AwesomePerson>() {}, null);
    var userSupplier = subscriber.getBody().toCompletableFuture().getNow(null);
    assertNotNull(userSupplier);
    new Thread(() -> {
      subscriber.onSubscribe(NOOP_SUBSCRIPTION);
      subscriber.onNext(List.of(UTF_8.encode(json)));
      subscriber.onComplete();
    }).start();
    var obama = userSupplier.get();
    assertEquals(obama.firstName, "Barack");
    assertEquals(obama.lastName, "Obama");
    assertEquals(obama.age, 58);
  }

  @Test
  void deserializeJson_deferredWithError() {
    var subscriber = GsonConverterFactory
        .createOfResponse().toDeferredObject(new TypeReference<AwesomePerson>() {}, null);
    var userSupplier = subscriber.getBody().toCompletableFuture().getNow(null);
    assertNotNull(userSupplier);
    new Thread(() -> {
      subscriber.onSubscribe(NOOP_SUBSCRIPTION);
      subscriber.onError(new IOException("Ops"));
    }).start();
    assertThrows(UncheckedIOException.class, userSupplier::get);
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

  private static class AwesomePerson {

    String firstName;
    String lastName;
    int age;

    AwesomePerson(String firstName, String lastName, int age) {
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
  }

  private static class PointAdapter extends TypeAdapter<Point> {

    PointAdapter() {
    }

    @Override
    public void write(JsonWriter out, Point value) throws IOException {
      out.beginArray();
      out.value(value.x);
      out.value(value.y);
      out.endArray();
    }

    @Override
    public Point read(JsonReader in) throws IOException {
      in.beginArray();
      var point = new Point(in.nextInt(), in.nextInt());
      in.endArray();
      return point;
    }
  }
}
