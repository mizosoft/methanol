/*
 * Copyright (c) 2023 Moataz Abdelnasser
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

package com.github.mizosoft.methanol;

import static com.github.mizosoft.methanol.MediaType.TEXT_PLAIN;
import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static org.assertj.core.api.Assertions.from;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter;
import com.github.mizosoft.methanol.testing.ImmutableResponseInfo;
import java.net.http.HttpClient.Version;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.net.http.HttpResponse.ResponseInfo;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

class AdapterCodecTest {
  private static final MediaType X_NUMBER_INT = MediaType.parse("x-number/int");

  @Test
  void getEncoders() {
    var encoders = List.of(new IntEncoder(), new StringEncoder());
    var builder = AdapterCodec.newBuilder();
    encoders.forEach(builder::encoder);
    var codec = builder.build();
    assertThat(codec).returns(encoders, from(AdapterCodec::encoders));
  }

  @Test
  void getDecoders() {
    var decoders = List.of(new IntDecoder(), new StringDecoder());
    var builder = AdapterCodec.newBuilder();
    decoders.forEach(builder::decoder);
    var codec = builder.build();
    assertThat(codec).returns(decoders, from(AdapterCodec::decoders));
  }

  @Test
  void fromToInt() {
    var codec =
        AdapterCodec.newBuilder().encoder(new IntEncoder()).decoder(new IntDecoder()).build();

    verifyThat(codec.publisherOf(1, X_NUMBER_INT)) //
        .hasMediaType(X_NUMBER_INT)
        .succeedsWith("1");

    verifyThat(codec.subscriberOf(TypeRef.from(Integer.class), X_NUMBER_INT)) //
        .publishing("1")
        .succeedsWith(1);

    verifyThat(codec.deferredSubscriberOf(TypeRef.from(Integer.class), X_NUMBER_INT)) //
        .publishing("1")
        .completedBody()
        .returns(1, from(Supplier::get));
  }

  @Test
  void handleToInt() {
    var codec = AdapterCodec.newBuilder().decoder(new IntDecoder()).build();
    verifyThat(codec.handlerOf(Integer.class).apply(responseInfoOf(X_NUMBER_INT)))
        .publishing("1")
        .succeedsWith(1);
    verifyThat(codec.handlerOf(TypeRef.from(Integer.class)).apply(responseInfoOf(X_NUMBER_INT)))
        .publishing("1")
        .succeedsWith(1);
  }

  @Test
  void deferredHandleToInt() {
    var codec = AdapterCodec.newBuilder().decoder(new IntDecoder()).build();
    verifyThat(codec.deferredHandlerOf(Integer.class).apply(responseInfoOf(X_NUMBER_INT)))
        .publishing("1")
        .completedBody()
        .returns(1, from(Supplier::get));
    verifyThat(
            codec
                .deferredHandlerOf(TypeRef.from(Integer.class))
                .apply(responseInfoOf(X_NUMBER_INT)))
        .publishing("1")
        .completedBody()
        .returns(1, from(Supplier::get));
  }

  @Test
  void mixedAdapters() {
    var codec =
        AdapterCodec.newBuilder()
            .encoder(new IntEncoder())
            .decoder(new IntDecoder())
            .encoder(new StringEncoder())
            .decoder(new StringDecoder())
            .build();

    verifyThat(codec.publisherOf(1, X_NUMBER_INT)) //
        .hasMediaType(X_NUMBER_INT)
        .succeedsWith("1");

    verifyThat(codec.subscriberOf(TypeRef.from(Integer.class), X_NUMBER_INT)) //
        .publishing("1")
        .succeedsWith(1);

    verifyThat(codec.deferredSubscriberOf(TypeRef.from(Integer.class), X_NUMBER_INT)) //
        .publishing("1")
        .completedBody()
        .returns(1, from(Supplier::get));

    // ---

    verifyThat(codec.publisherOf("a", TEXT_PLAIN)) //
        .hasMediaType(TEXT_PLAIN)
        .succeedsWith("a");

    verifyThat(codec.subscriberOf(TypeRef.from(String.class), TEXT_PLAIN)) //
        .publishing("a")
        .succeedsWith("a");

    verifyThat(codec.deferredSubscriberOf(TypeRef.from(String.class), TEXT_PLAIN)) //
        .publishing("a")
        .completedBody()
        .returns("a", from(Supplier::get));
  }

  @Test
  void unsupportedConversion() {
    var codec =
        AdapterCodec.newBuilder().encoder(new IntEncoder()).decoder(new IntDecoder()).build();
    assertThatThrownBy(() -> codec.publisherOf(12, TEXT_PLAIN))
        .isInstanceOf(UnsupportedOperationException.class);

    assertThatThrownBy(() -> codec.subscriberOf(TypeRef.from(Integer.class), TEXT_PLAIN))
        .isInstanceOf(UnsupportedOperationException.class);

    assertThatThrownBy(() -> codec.handlerOf(TypeRef.from(Double.class)))
        .isInstanceOf(UnsupportedOperationException.class);

    var handler = codec.handlerOf(Integer.class);
    assertThatThrownBy(() -> handler.apply(responseInfoOf(TEXT_PLAIN)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void anyMediaType() {
    var codec =
        AdapterCodec.newBuilder()
            .encoder(new IntEncoder())
            .decoder(new IntDecoder())
            .encoder(new StringEncoder())
            .decoder(new StringDecoder())
            .build();

    verifyThat(codec.publisherOf(1, MediaType.ANY)) //
        .hasNoMediaType()
        .succeedsWith("1");

    verifyThat(codec.subscriberOf(TypeRef.from(Integer.class), MediaType.ANY))
        .publishing("1")
        .succeedsWith(1);
    verifyThat(codec.deferredSubscriberOf(TypeRef.from(Integer.class), MediaType.ANY))
        .publishing("1")
        .completedBody()
        .returns(1, from(Supplier::get));

    // ---

    verifyThat(codec.publisherOf("a", MediaType.ANY)) //
        .hasNoMediaType()
        .succeedsWith("a");

    verifyThat(codec.subscriberOf(TypeRef.from(String.class), MediaType.ANY))
        .publishing("a")
        .succeedsWith("a");
    verifyThat(codec.deferredSubscriberOf(TypeRef.from(String.class), MediaType.ANY))
        .publishing("a")
        .completedBody()
        .returns("a", from(Supplier::get));
  }

  private static ResponseInfo responseInfoOf(MediaType mediaType) {
    return new ImmutableResponseInfo(
        200,
        HttpHeaders.of(Map.of("Content-Type", List.of(mediaType.toString())), (r, t) -> true),
        Version.HTTP_1_1);
  }

  private abstract static class CustomAdapter<V> extends AbstractBodyAdapter {
    private final TypeRef<V> targetType;

    CustomAdapter(TypeRef<V> targetType, MediaType... compatibleMediaTypes) {
      super(compatibleMediaTypes);
      this.targetType = targetType;
    }

    @Override
    public boolean supportsType(TypeRef<?> type) {
      return targetType.equals(type);
    }

    static class Encoder<V> extends CustomAdapter<V> implements BodyAdapter.Encoder {
      private final Function<V, String> encode;

      Encoder(Class<V> clazz, Function<V, String> encode, MediaType... compatibleMediaTypes) {
        super(TypeRef.from(clazz), compatibleMediaTypes);
        this.encode = encode;
      }

      @SuppressWarnings("unchecked")
      @Override
      public BodyPublisher toBody(Object object, @Nullable MediaType mediaType) {
        requireSupport(object.getClass());
        return attachMediaType(BodyPublishers.ofString(encode.apply((V) object)), mediaType);
      }
    }

    static class Decoder<V> extends CustomAdapter<V> implements BodyAdapter.Decoder {
      private final Function<String, V> decode;

      Decoder(Class<V> clazz, Function<String, V> decode, MediaType... compatibleMediaTypes) {
        super(TypeRef.from(clazz), compatibleMediaTypes);
        this.decode = decode;
      }

      @SuppressWarnings("unchecked")
      @Override
      public <T> BodySubscriber<T> toObject(TypeRef<T> objectType, @Nullable MediaType mediaType) {
        requireSupport(objectType);
        return (BodySubscriber<T>)
            BodySubscribers.mapping(BodySubscribers.ofString(charsetOrUtf8(mediaType)), decode);
      }
    }
  }

  private static final class IntEncoder extends CustomAdapter.Encoder<Integer> {
    IntEncoder() {
      super(Integer.class, Object::toString, X_NUMBER_INT);
    }
  }

  private static final class IntDecoder extends CustomAdapter.Decoder<Integer> {
    IntDecoder() {
      super(Integer.class, Integer::parseInt, X_NUMBER_INT);
    }
  }

  private static final class StringEncoder extends CustomAdapter.Encoder<String> {
    StringEncoder() {
      super(String.class, Function.identity(), TEXT_PLAIN);
    }
  }

  private static final class StringDecoder extends CustomAdapter.Decoder<String> {
    StringDecoder() {
      super(String.class, Function.identity(), TEXT_PLAIN);
    }
  }
}
