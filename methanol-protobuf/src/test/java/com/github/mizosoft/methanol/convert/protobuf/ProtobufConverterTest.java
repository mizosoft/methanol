package com.github.mizosoft.methanol.convert.protobuf;

import static com.github.mizosoft.methanol.convert.protobuf.ProtobufConverterFactory.createOfRequest;
import static com.github.mizosoft.methanol.convert.protobuf.ProtobufConverterFactory.createOfResponse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.TypeReference;
import com.github.mizosoft.methanol.convert.protobuf.TestProto.AwesomePerson;
import com.github.mizosoft.methanol.convert.protobuf.TestProto.Awesomeness;
import com.github.mizosoft.methanol.testutils.BodyCollector;
import com.github.mizosoft.methanol.testutils.TestUtils;
import com.google.protobuf.ByteString;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.MessageLite;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

public class ProtobufConverterTest {

  @Test
  void isCompatibleWith_supportsType() {
    for (var c : List.of(createOfRequest(), createOfResponse())) {
      assertTrue(c.isCompatibleWith(MediaType.of("application", "octet-stream")));
      assertTrue(c.isCompatibleWith(MediaType.of("application", "x-protobuf")));
      assertTrue(c.isCompatibleWith(MediaType.of("application", "*")));
      assertFalse(c.isCompatibleWith(MediaType.of("application", "json")));
      assertTrue(c.supportsType(TypeReference.from(MessageLite.class)));
      assertTrue(c.supportsType(TypeReference.from(Message.class)));
      assertFalse(c.supportsType(TypeReference.from(String.class)));
    }
  }

  @Test
  void unsupportedConversion_ofRequest() {
    var ofReq = createOfRequest();
    assertThrows(UnsupportedOperationException.class, () -> ofReq.toBody("Not a Message!", null));
    assertThrows(UnsupportedOperationException.class,
        () -> ofReq.toBody(AwesomePerson.newBuilder().build(), MediaType.of("text", "plain")));
  }

  @Test
  void unsupportedConversion_ofResponse() {
    var ofRes = createOfResponse();
    assertThrows(UnsupportedOperationException.class,
        () -> ofRes.toObject(TypeReference.from(String.class), null));
    assertThrows(UnsupportedOperationException.class,
        () -> ofRes.toObject(TypeReference.from(AwesomePerson.class),
            MediaType.of("text", "plain")));
  }

  @Test
  void serializeMessage() {
    var elon = AwesomePerson.newBuilder()
        .setFirstName("Elon")
        .setLastName("Musk")
        .setAge(48)
        .build();
    var body = createOfRequest().toBody(elon, null);
    assertEquals(elon.toByteString(), ByteString.copyFrom(BodyCollector.collect(body)));
  }

  @Test
  void deserializeMessage() {
    var subscriber = createOfResponse().toObject(TypeReference.from(AwesomePerson.class), null);
    var elon = AwesomePerson.newBuilder()
        .setFirstName("Elon")
        .setLastName("Musk")
        .setAge(48)
        .build();
    assertEquals(elon, publishMessage(subscriber, elon));
  }

  @Test
  void deserializeMessage_withExtensions() {
    var registry = ExtensionRegistry.newInstance();
    registry.add(TestProto.awesomeness);
    var subscriber = createOfResponse(registry)
        .toObject(TypeReference.from(AwesomePerson.class), null);
    var elon = AwesomePerson.newBuilder()
        .setFirstName("Elon")
        .setLastName("Musk")
        .setAge(48)
        .setExtension(TestProto.awesomeness, Awesomeness.SUPER_AWESOME)
        .build();
    assertEquals(elon, publishMessage(subscriber, elon));
  }

  @Test
  void deserializeMessage_deferred() {
    var subscriber = createOfResponse()
        .toDeferredObject(TypeReference.from(AwesomePerson.class), null);
    var elon = AwesomePerson.newBuilder()
        .setFirstName("Elon")
        .setLastName("Musk")
        .setAge(48)
        .build();
    var supplier = subscriber.getBody().toCompletableFuture().getNow(null);
    assertNotNull(supplier);
    new Thread(() -> {
      subscriber.onSubscribe(TestUtils.NOOP_SUBSCRIPTION);
      subscriber.onNext(List.of(ByteBuffer.wrap(elon.toByteArray())));
      subscriber.onComplete();
    }).start();
    assertEquals(elon, supplier.get());
  }

  @Test
  void deserializeMessage_deferredWithError() {
    var subscriber = createOfResponse()
        .toDeferredObject(TypeReference.from(AwesomePerson.class), null);
    var supplier = subscriber.getBody().toCompletableFuture().getNow(null);
    assertNotNull(supplier);
    new Thread(() -> {
      subscriber.onSubscribe(TestUtils.NOOP_SUBSCRIPTION);
      subscriber.onNext(List.of(ByteBuffer.wrap("not wire, obviously.".getBytes())));
      subscriber.onComplete();
    }).start();
    var uioe = assertThrows(UncheckedIOException.class, supplier::get);
    assertTrue(uioe.getCause() instanceof InvalidProtocolBufferException);
  }

  public static <T extends Message> T publishMessage(BodySubscriber<T> subscriber, Message message) {
    subscriber.onSubscribe(TestUtils.NOOP_SUBSCRIPTION);
    subscriber.onNext(List.of(ByteBuffer.wrap(message.toByteArray())));
    subscriber.onComplete();
    return subscriber.getBody().toCompletableFuture().join();
  }
}
