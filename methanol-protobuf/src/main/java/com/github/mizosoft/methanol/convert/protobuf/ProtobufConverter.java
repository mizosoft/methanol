package com.github.mizosoft.methanol.convert.protobuf;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.Converter;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.TypeReference;
import com.github.mizosoft.methanol.convert.AbstractConverter;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;

abstract class ProtobufConverter extends AbstractConverter {

  private static final MediaType APPLICATION_OCTET_STREAM =
      MediaType.of("application", "octet-stream");
  private static final MediaType APPLICATION_PROTOBUF = MediaType.of("application", "x-protobuf");

  ProtobufConverter() {
    super(APPLICATION_OCTET_STREAM, APPLICATION_PROTOBUF);
  }

  @Override
  public boolean supportsType(TypeReference<?> type) {
    return MessageLite.class.isAssignableFrom(type.rawType());
  }

  static final class OfRequest extends ProtobufConverter implements Converter.OfRequest {

    OfRequest() {
      super();
    }

    @Override
    public BodyPublisher toBody(Object object, @Nullable MediaType mediaType) {
      requireNonNull(object);
      requireSupport(TypeReference.from(object.getClass()));
      requireCompatibleOrNull(mediaType);
      MessageLite message = (MessageLite) object;
      return BodyPublishers.ofByteArray(message.toByteArray());
    }
  }

  static final class OfResponse extends ProtobufConverter implements Converter.OfResponse {

    private final ExtensionRegistryLite registry;

    OfResponse(ExtensionRegistryLite registry) {
      this.registry = requireNonNull(registry);
    }

    @Override
    public <T> BodySubscriber<T> toObject(TypeReference<T> type, @Nullable MediaType mediaType) {
      requireNonNull(type);
      requireSupport(type);
      requireCompatibleOrNull(mediaType);
      MessageLite.Builder builder = getBuilderForMessage(type.rawType());
      return BodySubscribers.mapping(
          BodySubscribers.ofByteArray(), data -> buildMessage(builder, data));
    }

    @Override
    public <T> BodySubscriber<Supplier<T>> toDeferredObject(
        TypeReference<T> type, @Nullable MediaType mediaType) {
      requireNonNull(type);
      requireSupport(type);
      requireCompatibleOrNull(mediaType);
      MessageLite.Builder builder = getBuilderForMessage(type.rawType());
      return BodySubscribers.mapping(
          BodySubscribers.ofInputStream(), in -> () -> buildMessage(builder, in));
    }

    private <T> T buildMessage(MessageLite.Builder builder, byte[] data) {
      try {
        builder.mergeFrom(data, registry);
      } catch (InvalidProtocolBufferException e) {
        throw new UncheckedIOException(e);
      }
      return castMessage(builder.build());
    }

    private <T> T buildMessage(MessageLite.Builder builder, InputStream in) {
      try {
        builder.mergeFrom(in, registry);
      } catch (IOException ioe) {
        throw new UncheckedIOException(ioe);
      }
      return castMessage(builder.build());
    }

    // Used after we know that message is T to the caller
    @SuppressWarnings("unchecked")
    private static <T> T castMessage(MessageLite message) {
      return (T) message;
    }

    private static MessageLite.Builder getBuilderForMessage(Class<?> clazz) {
      try {
        Method builderFactory = clazz.getMethod("newBuilder");
        return (MessageLite.Builder) builderFactory.invoke(null);
      } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
        throw new UnsupportedOperationException(
            "couldn't create a builder from message of type: " + clazz,
            e instanceof InvocationTargetException ? e.getCause() : e);
      }
    }
  }
}
