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

import static com.github.mizosoft.methanol.adapter.jackson.JacksonAdapterFactory.createJsonEncoder;
import static com.github.mizosoft.methanol.testutils.Verification.verifyThat;
import static java.nio.charset.StandardCharsets.UTF_16;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.util.List;
import org.junit.jupiter.api.Test;

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
        new JsonMapper()
            .registerModule(
                new SimpleModule().addSerializer(Point.class, new CompactPointSerializer()));
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
        new JsonMapper()
            .registerModule(
                new SimpleModule().addSerializer(Point.class, new CompactPointSerializer()));
    verifyThat(createJsonEncoder(mapper))
        .converting(List.of(new Point(1, 2), new Point(3, 4)))
        .succeedsWith("[[1,2],[3,4]]");
  }

  @Test
  void serializeWithUnsupportedType() {
    class UnserializableByDefaultMapper {
      final int i = 1;

      UnserializableByDefaultMapper() {}
    }

    verifyThat(createJsonEncoder())
        .converting(new UnserializableByDefaultMapper())
        .isNotSupported();
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
