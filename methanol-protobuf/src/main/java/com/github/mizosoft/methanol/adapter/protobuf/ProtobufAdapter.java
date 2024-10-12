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

package com.github.mizosoft.methanol.adapter.protobuf;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.util.function.Supplier;

abstract class ProtobufAdapter extends AbstractBodyAdapter {
  ProtobufAdapter() {
    super(MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_X_PROTOBUF);
  }

  @Override
  public boolean supportsType(TypeRef<?> typeRef) {
    return typeRef.type() instanceof Class<?>
        && MessageLite.class.isAssignableFrom(typeRef.rawType());
  }

  static final class Encoder extends ProtobufAdapter implements BaseEncoder {
    Encoder() {}

    @Override
    public <T> BodyPublisher toBody(T value, TypeRef<T> typeRef, Hints hints) {
      requireSupport(typeRef, hints);
      var message = (MessageLite) value;
      return attachMediaType(
          BodyPublishers.ofByteArray(message.toByteArray()), hints.mediaTypeOrAny());
    }
  }

  static final class Decoder extends ProtobufAdapter implements BaseDecoder {
    private final ExtensionRegistryLite registry;

    Decoder(ExtensionRegistryLite registry) {
      this.registry = requireNonNull(registry);
    }

    @Override
    public <T> BodySubscriber<T> toObject(TypeRef<T> typeRef, Hints hints) {
      requireSupport(typeRef, hints);
      var messageType = typeRef.exactRawType();
      var builder = builderOf(messageType);
      return BodySubscribers.mapping(
          BodySubscribers.ofByteArray(),
          bytes -> {
            try {
              builder.mergeFrom(bytes, registry);
            } catch (InvalidProtocolBufferException e) {
              throw new UncheckedIOException(e);
            }
            return messageType.cast(builder.build());
          });
    }

    @Override
    public <T> BodySubscriber<Supplier<T>> toDeferredObject(TypeRef<T> typeRef, Hints hints) {
      requireSupport(typeRef, hints);
      var messageType = typeRef.exactRawType();
      var builder = builderOf(messageType);
      return BodySubscribers.mapping(
          BodySubscribers.ofInputStream(),
          in ->
              () -> {
                try {
                  builder.mergeFrom(in, registry);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
                return messageType.cast(builder.build());
              });
    }

    private static MessageLite.Builder builderOf(Class<?> messageClass) {
      try {
        var builderFactory = messageClass.getMethod("newBuilder");
        return (MessageLite.Builder) builderFactory.invoke(null);
      } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
        throw new UnsupportedOperationException(
            "Couldn't create a builder from message of type: " + messageClass,
            e instanceof InvocationTargetException ? e.getCause() : e);
      }
    }
  }
}
