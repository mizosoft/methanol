/*
 * Copyright (c) 2024 Moataz Hussein
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

import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.from;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import com.github.mizosoft.methanol.BodyAdapter.Hints;
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter;
import com.github.mizosoft.methanol.testing.ImmutableResponseInfo;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.net.http.HttpClient.Version;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdapterCodecTest {
  private static final MediaType X_NUMBER_INT = MediaType.parse("x-number/int");
  private static final MediaType X_NUMBER_INT_LIST = MediaType.parse("x-number/int-list");

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

    verifyThat(codec.publisherOf(1, Hints.of(X_NUMBER_INT))) //
        .hasMediaType(X_NUMBER_INT)
        .succeedsWith("1");

    verifyThat(codec.subscriberOf(TypeRef.of(Integer.class), Hints.of(X_NUMBER_INT))) //
        .publishing("1")
        .succeedsWith(1);

    verifyThat(codec.deferredSubscriberOf(TypeRef.of(Integer.class), Hints.of(X_NUMBER_INT))) //
        .publishing("1")
        .completedBody()
        .returns(1, from(Supplier::get));
  }

  @Test
  void handleToInt() {
    var codec = AdapterCodec.newBuilder().decoder(new IntDecoder()).build();
    verifyThat(
            codec
                .handlerOf(TypeRef.of(Integer.class), Hints.empty())
                .apply(responseInfoOf(X_NUMBER_INT)))
        .publishing("1")
        .succeedsWith(1);
  }

  @Test
  void deferredHandleToInt() {
    var codec = AdapterCodec.newBuilder().decoder(new IntDecoder()).build();
    verifyThat(
            codec
                .deferredHandlerOf(TypeRef.of(Integer.class), Hints.empty())
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

    verifyThat(codec.publisherOf(1, Hints.of(X_NUMBER_INT))) //
        .hasMediaType(X_NUMBER_INT)
        .succeedsWith("1");

    verifyThat(codec.subscriberOf(TypeRef.of(Integer.class), Hints.of(X_NUMBER_INT))) //
        .publishing("1")
        .succeedsWith(1);

    verifyThat(codec.deferredSubscriberOf(TypeRef.of(Integer.class), Hints.of(X_NUMBER_INT))) //
        .publishing("1")
        .completedBody()
        .returns(1, from(Supplier::get));

    // ---

    verifyThat(codec.publisherOf("a", Hints.of(MediaType.TEXT_PLAIN))) //
        .hasMediaType(MediaType.TEXT_PLAIN)
        .succeedsWith("a");

    verifyThat(codec.subscriberOf(TypeRef.of(String.class), Hints.of(MediaType.TEXT_PLAIN))) //
        .publishing("a")
        .succeedsWith("a");

    verifyThat(
            codec.deferredSubscriberOf(
                TypeRef.of(String.class), Hints.of(MediaType.TEXT_PLAIN))) //
        .publishing("a")
        .completedBody()
        .returns("a", from(Supplier::get));
  }

  @Test
  void unsupportedConversion() {
    var codec =
        AdapterCodec.newBuilder().encoder(new IntEncoder()).decoder(new IntDecoder()).build();
    assertThatThrownBy(() -> codec.publisherOf(12, Hints.of(MediaType.TEXT_PLAIN)))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () -> codec.subscriberOf(TypeRef.of(Integer.class), Hints.of(MediaType.TEXT_PLAIN)))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> codec.handlerOf(TypeRef.of(Double.class), Hints.empty()))
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

    verifyThat(codec.publisherOf(1, Hints.of(MediaType.ANY))) //
        .hasNoMediaType()
        .succeedsWith("1");

    verifyThat(codec.subscriberOf(TypeRef.of(Integer.class), Hints.of(MediaType.ANY)))
        .publishing("1")
        .succeedsWith(1);
    verifyThat(codec.deferredSubscriberOf(TypeRef.of(Integer.class), Hints.of(MediaType.ANY)))
        .publishing("1")
        .completedBody()
        .returns(1, from(Supplier::get));

    // ---

    verifyThat(codec.publisherOf("a", Hints.of(MediaType.ANY))) //
        .hasNoMediaType()
        .succeedsWith("a");

    verifyThat(codec.subscriberOf(TypeRef.of(String.class), Hints.of(MediaType.ANY)))
        .publishing("a")
        .succeedsWith("a");
    verifyThat(codec.deferredSubscriberOf(TypeRef.of(String.class), Hints.of(MediaType.ANY)))
        .publishing("a")
        .completedBody()
        .returns("a", from(Supplier::get));
  }

  @Test
  void encodeGenericType() {
    var codec = AdapterCodec.newBuilder().encoder(new IntListEncoder()).build();
    verifyThat(codec.publisherOf(List.of(1, 2), new TypeRef<>() {}, Hints.of(X_NUMBER_INT_LIST)))
        .succeedsWith(List.of(1, 2).toString());
    assertThatThrownBy(
            () ->
                codec.publisherOf(
                    List.of("a", "b"), new TypeRef<>() {}, Hints.of(X_NUMBER_INT_LIST)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void basicEncoder(@TempDir Path tempDir) throws IOException {
    testBasicEncoder(AdapterCodec.newBuilder().basicEncoder().build(), tempDir);
  }

  @Test
  void basicDecoder() {
    testBasicDecoder(AdapterCodec.newBuilder().basicDecoder().build());
  }

  @Test
  void basicCodec(@TempDir Path tempDir) throws IOException {
    var codec = AdapterCodec.newBuilder().basic().build();
    testBasicEncoder(codec, tempDir);
    testBasicDecoder(codec);
  }

  private void testBasicEncoder(AdapterCodec codec, Path tempDir) throws IOException {
    verifyThat(codec.publisherOf("Pikachu", Hints.of(MediaType.TEXT_PLAIN)))
        .hasMediaType(MediaType.TEXT_PLAIN)
        .succeedsWith("Pikachu");
    verifyThat(codec.publisherOf("é€Pikachu€é", Hints.of(MediaType.TEXT_PLAIN.withCharset(UTF_8))))
        .hasMediaType(MediaType.TEXT_PLAIN.withCharset(UTF_8))
        .succeedsWith("é€Pikachu€é");
    verifyThat(codec.publisherOf(new byte[] {1, 2, 3}, Hints.of(MediaType.ANY)))
        .hasNoMediaType()
        .succeedsWith(ByteBuffer.wrap(new byte[] {1, 2, 3}));
    verifyThat(
            codec.publisherOf(
                new ByteArrayInputStream("Pikachu".getBytes(UTF_8)), Hints.of(MediaType.ANY)))
        .succeedsWith("Pikachu");

    var file = Files.createTempFile(tempDir, AdapterCodecTest.class.getName(), "");
    Files.writeString(file, "Pikachu");
    verifyThat(codec.publisherOf(file, Hints.of(MediaType.ANY))).succeedsWith("Pikachu");
  }

  private void testBasicDecoder(AdapterCodec codec) {
    verifyThat(codec.subscriberOf(new TypeRef<String>() {}, Hints.of(MediaType.TEXT_PLAIN)))
        .publishing("Pikachu")
        .succeedsWith("Pikachu");
    verifyThat(
            codec.subscriberOf(
                new TypeRef<String>() {}, Hints.of(MediaType.TEXT_PLAIN.withCharset(UTF_8))))
        .publishing("é€Pikachu€é")
        .succeedsWith("é€Pikachu€é");
    verifyThat(codec.subscriberOf(new TypeRef<Reader>() {}, Hints.of(MediaType.TEXT_PLAIN)))
        .publishing("Pikachu")
        .completedBody()
        .satisfies(
            reader ->
                Assertions.assertThat(new BufferedReader(reader).readLine()).isEqualTo("Pikachu"));
    verifyThat(
            codec.subscriberOf(
                new TypeRef<Reader>() {}, Hints.of(MediaType.TEXT_PLAIN.withCharset(UTF_8))))
        .publishing("é€Pikachu€é")
        .completedBody()
        .satisfies(
            reader ->
                Assertions.assertThat(new BufferedReader(reader).readLine())
                    .isEqualTo("é€Pikachu€é"));
    verifyThat(codec.subscriberOf(new TypeRef<byte[]>() {}, Hints.of(MediaType.ANY)))
        .publishing(ByteBuffer.wrap(new byte[] {1, 2, 3}))
        .succeedsWith(new byte[] {1, 2, 3});
    verifyThat(codec.subscriberOf(new TypeRef<ByteBuffer>() {}, Hints.of(MediaType.ANY)))
        .publishing(ByteBuffer.wrap(new byte[] {1, 2, 3}))
        .succeedsWith(ByteBuffer.wrap(new byte[] {1, 2, 3}));
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
    public boolean supportsType(TypeRef<?> typeRef) {
      return targetType.equals(typeRef);
    }

    static class Encoder<V> extends CustomAdapter<V> implements BaseEncoder {
      private final Function<V, String> encode;

      Encoder(Class<V> clazz, Function<V, String> encode, MediaType... compatibleMediaTypes) {
        super(TypeRef.of(clazz), compatibleMediaTypes);
        this.encode = encode;
      }

      Encoder(TypeRef<V> typeRef, Function<V, String> encode, MediaType... compatibleMediaTypes) {
        super(typeRef, compatibleMediaTypes);
        this.encode = encode;
      }

      @SuppressWarnings("unchecked")
      @Override
      public <T> BodyPublisher toBody(T value, TypeRef<T> typeRef, Hints hints) {
        requireSupport(typeRef, hints);
        return attachMediaType(
            BodyPublishers.ofString(encode.apply((V) value)), hints.mediaTypeOrAny());
      }
    }

    static class Decoder<V> extends CustomAdapter<V> implements BaseDecoder {
      private final Function<String, V> decode;

      Decoder(Class<V> clazz, Function<String, V> decode, MediaType... compatibleMediaTypes) {
        this(TypeRef.of(clazz), decode, compatibleMediaTypes);
      }

      Decoder(TypeRef<V> typeRef, Function<String, V> decode, MediaType... compatibleMediaTypes) {
        super(typeRef, compatibleMediaTypes);
        this.decode = decode;
      }

      @SuppressWarnings("unchecked")
      @Override
      public <T> BodySubscriber<T> toObject(TypeRef<T> typeRef, Hints hints) {
        requireSupport(typeRef, hints);
        return (BodySubscriber<T>)
            BodySubscribers.mapping(
                BodySubscribers.ofString(hints.mediaTypeOrAny().charsetOrDefault(UTF_8)), decode);
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

  private static final class IntListEncoder extends CustomAdapter.Encoder<List<Integer>> {
    IntListEncoder() {
      super(new TypeRef<>() {}, List<Integer>::toString, X_NUMBER_INT_LIST);
    }
  }

  private static final class StringEncoder extends CustomAdapter.Encoder<String> {
    StringEncoder() {
      super(String.class, Function.identity(), MediaType.TEXT_PLAIN);
    }
  }

  private static final class StringDecoder extends CustomAdapter.Decoder<String> {
    StringDecoder() {
      super(String.class, Function.identity(), MediaType.TEXT_PLAIN);
    }
  }
}
