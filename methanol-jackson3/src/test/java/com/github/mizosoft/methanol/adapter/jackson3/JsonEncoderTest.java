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

import static com.github.mizosoft.methanol.adapter.jackson3.JacksonAdapterFactory.createJsonEncoder;
import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static java.nio.charset.StandardCharsets.UTF_16;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

class JsonEncoderTest {
  @Test
  void compatibleMediaTypes() {
    verifyThat(createJsonEncoder())
        .isCompatibleWith("application/json")
        .isCompatibleWith("application/json; charset=utf-8")
        .isCompatibleWith("application/*")
        .isCompatibleWith("*/*");
  }

  @Test
  void incompatibleMediaTypes() {
    verifyThat(createJsonEncoder()).isNotCompatibleWith("text/html").isNotCompatibleWith("text/*");
  }

  @Test
  void serialize() {
    verifyThat(createJsonEncoder()).converting(new Point(1, 2)).succeedsWith("{\"x\":1,\"y\":2}");
  }

  @Test
  void serializeWithUtf16() {
    verifyThat(createJsonEncoder())
        .converting(new Point(1, 2))
        .withMediaType("application/json; charset=utf-16")
        .succeedsWith("{\"x\":1,\"y\":2}", UTF_16);
  }

  @Test
  void serializeWithCustomSerializer() {
    var mapper =
        JsonMapper.builder()
            .addModule(new SimpleModule().addSerializer(Point.class, new CompactPointSerializer()))
            .build();
    verifyThat(createJsonEncoder(mapper)).converting(new Point(1, 2)).succeedsWith("[1,2]");
  }

  @Test
  void serializeList() {
    verifyThat(createJsonEncoder())
        .converting(List.of(new Point(1, 2), new Point(3, 4)))
        .succeedsWith("[{\"x\":1,\"y\":2},{\"x\":3,\"y\":4}]");
  }

  @Test
  void serializeListWithCustomSerializer() {
    var mapper =
        JsonMapper.builder()
            .addModule(new SimpleModule().addSerializer(Point.class, new CompactPointSerializer()))
            .build();
    verifyThat(createJsonEncoder(mapper))
        .converting(List.of(new Point(1, 2), new Point(3, 4)))
        .succeedsWith("[[1,2],[3,4]]");
  }

  @Test
  void serializeWithUnsupportedType() {
    // Jackson 3 serializes local/anonymous classes as empty objects instead of rejecting them
    class UnserializableByDefaultMapper {
      final int i = 1;

      UnserializableByDefaultMapper() {}
    }

    verifyThat(createJsonEncoder())
        .converting(new UnserializableByDefaultMapper())
        .succeedsWith("{}");
  }

  @Test
  void serializeWithUnsupportedMediaType() {
    verifyThat(createJsonEncoder())
        .converting(new Point(1, 2))
        .withMediaType("application/xml")
        .isNotSupported();
  }

  @Test
  void mediaTypeIsAttached() {
    verifyThat(createJsonEncoder())
        .converting(new Point(1, 2))
        .withMediaType("application/json")
        .asBodyPublisher()
        .hasMediaType("application/json");
  }

  @Test
  void mediaTypeWithCharsetIsAttached() {
    verifyThat(createJsonEncoder())
        .converting(new Point(1, 2))
        .withMediaType("application/json; charset=utf-16")
        .asBodyPublisher()
        .hasMediaType("application/json; charset=utf-16");
  }
}
