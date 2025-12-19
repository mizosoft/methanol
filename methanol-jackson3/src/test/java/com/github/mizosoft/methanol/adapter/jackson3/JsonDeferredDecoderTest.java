/*
 * Copyright (c) 2025 Moataz Hussein
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

package com.github.mizosoft.methanol.adapter.jackson3;

import static com.github.mizosoft.methanol.MutableRequest.GET;
import static com.github.mizosoft.methanol.adapter.jackson3.JacksonAdapterFactory.createJsonDecoder;
import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static java.nio.charset.StandardCharsets.UTF_16;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.AdapterCodec;
import com.github.mizosoft.methanol.Methanol;
import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.testing.RecordingHttpClient;
import com.github.mizosoft.methanol.testing.TestException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

class JsonDeferredDecoderTest {
  @Test
  void deserialize() {
    verifyThat(createJsonDecoder())
        .converting(Point.class)
        .withDeferredBody("{\"x\":1, \"y\":2}")
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void deserializeWithUtf16() {
    verifyThat(createJsonDecoder())
        .converting(Point.class)
        .withMediaType("application/json; charset=utf-16")
        .withDeferredBody("{\"x\":1, \"y\":2}", UTF_16)
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void deserializeWithCustomDeserializer() {
    var mapper =
        JsonMapper.builder()
            .addModule(
                new SimpleModule().addDeserializer(Point.class, new CompactPointDeserializer()))
            .build();
    verifyThat(createJsonDecoder(mapper))
        .converting(Point.class)
        .withDeferredBody("[1, 2]")
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void deserializeWithGenerics() {
    verifyThat(createJsonDecoder())
        .converting(new TypeRef<List<Point>>() {})
        .withDeferredBody("[{\"x\":1, \"y\":2}, {\"x\":3, \"y\":4}]")
        .succeedsWith(List.of(new Point(1, 2), new Point(3, 4)));
  }

  @Test
  void deserializeWithGenericsAndCustomDeserializer() {
    var mapper =
        JsonMapper.builder()
            .addModule(
                new SimpleModule().addDeserializer(Point.class, new CompactPointDeserializer()))
            .build();
    verifyThat(createJsonDecoder(mapper))
        .converting(new TypeRef<List<Point>>() {})
        .withDeferredBody("[[1, 2], [3, 4]]")
        .succeedsWith(List.of(new Point(1, 2), new Point(3, 4)));
  }

  @Test
  void deserializeWithLenientMapper() {
    var mapper =
        JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_PROPERTY_NAMES)
            .build();
    verifyThat(createJsonDecoder(mapper))
        .converting(Point.class)
        .withDeferredBody("{\n" + "  x: '1',\n" + "  y: '2' // This is a comment \n" + "}")
        .succeedsWith(new Point(1, 2));
  }

  /** Tests that the used parser has access to the underlying ObjectMapper. */
  @Test
  void deserializeWithCustomDeserializerThatNeedsParserCodec() {
    var mapper =
        JsonMapper.builder()
            .addModule(
                new SimpleModule().addDeserializer(Point.class, new TreeNodePointDeserializer()))
            .build();
    verifyThat(createJsonDecoder(mapper))
        .converting(Point.class)
        .withDeferredBody("{\"x\":1, \"y\":2}")
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void deserializeBadJson() {
    verifyThat(createJsonDecoder())
        .converting(Point.class)
        .withDeferredBody("{x:\"1\", y:\"2\"") // Missing enclosing bracket
        .failsWith(JacksonException.class);
  }

  @Test
  void deserializeWithError() {
    // Upstream errors cause the stream used by the supplier to throw
    // an IOException with the error as its cause. The IOException is
    // rethrown as a JacksonIOException in Jackson 3.
    verifyThat(createJsonDecoder())
        .converting(Point.class)
        .withDeferredFailure(new TestException())
        .failsWith(JacksonIOException.class)
        .havingCause()
        .withCauseInstanceOf(TestException.class);
  }

  @Test
  void deferredDeserializeWithClient() throws Exception {
    var backend =
        new RecordingHttpClient()
            .handleCalls(
                call ->
                    call.complete(
                        ByteBuffer.wrap(
                            "[{\"x\":1, \"y\":2}, {\"x\":3, \"y\":4}]".getBytes(UTF_8))));
    var client =
        Methanol.newBuilder(backend)
            .adapterCodec(
                AdapterCodec.newBuilder()
                    .decoder(JacksonAdapterFactory.createJsonDecoder())
                    .build())
            .build();
    assertThat(
            client
                .send(GET("https://example.com"), new TypeRef<Supplier<List<Point>>>() {})
                .body()
                .get())
        .containsExactly(new Point(1, 2), new Point(3, 4));
  }
}
