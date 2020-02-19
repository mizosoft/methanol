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

    OfRequest() {}

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
      // We know that T is <= MessageLite to the caller, but the compiler doesn't
      Class<T> messageClass = toRawType(type);
      MessageLite.Builder builder = getBuilderForMessage(messageClass);
      return BodySubscribers.mapping(
          BodySubscribers.ofByteArray(), data -> buildMessage(messageClass, builder, data));
    }

    @Override
    public <T> BodySubscriber<Supplier<T>> toDeferredObject(
        TypeReference<T> type, @Nullable MediaType mediaType) {
      requireNonNull(type);
      requireSupport(type);
      requireCompatibleOrNull(mediaType);
      Class<T> messageClass = toRawType(type);
      MessageLite.Builder builder = getBuilderForMessage(messageClass);
      return BodySubscribers.mapping(
          BodySubscribers.ofInputStream(), in -> () -> buildMessage(messageClass, builder, in));
    }

    private <T> T buildMessage(Class<T> messageClass, MessageLite.Builder builder, byte[] data) {
      try {
        builder.mergeFrom(data, registry);
      } catch (InvalidProtocolBufferException e) {
        throw new UncheckedIOException(e);
      }
      return messageClass.cast(builder.build());
    }

    private <T> T buildMessage(Class<T> messageClass, MessageLite.Builder builder, InputStream in) {
      try {
        builder.mergeFrom(in, registry);
      } catch (IOException ioe) {
        throw new UncheckedIOException(ioe);
      }
      return messageClass.cast(builder.build());
    }

    // Messages are never expected to be generic, so type.rawType() is also Class<T>
    @SuppressWarnings("unchecked")
    private static <T> Class<T> toRawType(TypeReference<T> type) {
      return (Class<T>) type.rawType();
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
