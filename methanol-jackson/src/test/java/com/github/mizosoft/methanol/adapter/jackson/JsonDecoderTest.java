/*
 * Copyright (c) 2021 Moataz Abdelnasser
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

import static com.github.mizosoft.methanol.adapter.jackson.JacksonAdapterFactory.createJsonDecoder;
import static com.github.mizosoft.methanol.testutils.Verification.verifyThat;
import static java.nio.charset.StandardCharsets.UTF_16;

import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.testutils.TestException;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonDecoderTest {
  @Test
  void compatibleMediaTypes() {
    verifyThat(createJsonDecoder())
        .isCompatibleWith("application/json")
        .isCompatibleWith("application/json; charset=utf-8")
        .isCompatibleWith("application/*")
        .isCompatibleWith("*/*");
  }

  @Test
  void incompatibleMediaTypes() {
    verifyThat(createJsonDecoder()).isNotCompatibleWith("text/html").isNotCompatibleWith("text/*");
  }

  @Test
  void deserialize() {
    verifyThat(createJsonDecoder())
        .converting(Point.class)
        .withBody("{\"x\":1, \"y\":2}")
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void deserializeWithUtf16() {
    verifyThat(createJsonDecoder())
        .converting(Point.class)
        .withMediaType("application/json; charset=utf-16")
        .withBody("{\"x\":1, \"y\":2}", UTF_16)
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void deserializeWithCustomDeserializer() {
    var mapper =
        new JsonMapper()
            .registerModule(
                new SimpleModule().addDeserializer(Point.class, new CompactPointDeserializer()));
    verifyThat(createJsonDecoder(mapper))
        .converting(Point.class)
        .withBody("[1, 2]")
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void deserializeWithGenerics() {
    verifyThat(createJsonDecoder())
        .converting(new TypeRef<List<Point>>() {})
        .withBody("[{\"x\":1, \"y\":2}, {\"x\":3, \"y\":4}]")
        .succeedsWith(List.of(new Point(1, 2), new Point(3, 4)));
  }

  @Test
  void deserializeWithGenericsAndCustomDeserializer() {
    var mapper =
        new JsonMapper()
            .registerModule(
                new SimpleModule().addDeserializer(Point.class, new CompactPointDeserializer()));
    verifyThat(createJsonDecoder(mapper))
        .converting(new TypeRef<List<Point>>() {})
        .withBody("[[1, 2], [3, 4]]")
        .succeedsWith(List.of(new Point(1, 2), new Point(3, 4)));
  }

  @Test
  void deserializeWithLenientMapper() {
    var mapper =
        new ObjectMapper()
            .enable(Feature.ALLOW_COMMENTS)
            .enable(Feature.ALLOW_SINGLE_QUOTES)
            .enable(Feature.ALLOW_UNQUOTED_FIELD_NAMES);
    verifyThat(createJsonDecoder(mapper))
        .converting(Point.class)
        .withBody("{\n" + "  x: '1',\n" + "  y: '2' // This is a comment \n" + "}")
        .succeedsWith(new Point(1, 2));
  }

  /** Tests that the used parser has access to the underlying ObjectMapper. */
  @Test
  void deserializeWithCustomDeserializerThatNeedsParserCodec() {
    var mapper =
        new ObjectMapper()
            .registerModule(
                new SimpleModule().addDeserializer(Point.class, new TreeNodePointDeserializer()));
    verifyThat(createJsonDecoder(mapper))
        .converting(Point.class)
        .withBody("{\"x\":1, \"y\":2}")
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void deserializeBadJson() {
    verifyThat(createJsonDecoder())
        .converting(Point.class)
        .withBody("{x:\"1\", y:\"2\"") // Missing enclosing bracket
        .failsWith(UncheckedIOException.class)
        .withCauseInstanceOf(JsonProcessingException.class);
  }

  @Test
  void deserializeWithError() {
    verifyThat(createJsonDecoder())
        .converting(Point.class)
        .withFailure(new TestException())
        .failsWith(TestException.class);
  }

  @Test
  void deserializeWithUnsupportedType() {
    class UnserializableByDefaultMapper {
      final int i = 1;

      UnserializableByDefaultMapper() {}
    }

    verifyThat(createJsonDecoder())
        .converting(UnserializableByDefaultMapper.class)
        .isNotSupported();
  }

  @Test
  void deserializeWithUnsupportedMediaType() {
    verifyThat(createJsonDecoder())
        .converting(Point.class)
        .withMediaType("text/plain")
        .isNotSupported();
  }
}
