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

package com.github.mizosoft.methanol.adapter.gson;

import static com.github.mizosoft.methanol.adapter.gson.GsonAdapterFactory.createEncoder;
import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static java.nio.charset.StandardCharsets.UTF_16;

import com.google.gson.GsonBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

class GsonEncoderTest {
  @Test
  void compatibleMediaTypes() {
    verifyThat(createEncoder())
        .isCompatibleWith("application/json")
        .isCompatibleWith("application/json; charset=utf-8")
        .isCompatibleWith("application/*")
        .isCompatibleWith("*/*");
  }

  @Test
  void incompatibleMediaTypes() {
    verifyThat(createEncoder())
        .isNotCompatibleWith("text/html")
        .isNotCompatibleWith("text/*");
  }

  @Test
  void serialize() {
    verifyThat(createEncoder())
        .converting(new Point(1, 2))
        .succeedsWith("{\"x\":1,\"y\":2}");
  }

  @Test
  void serializeWithUtf16() {
    verifyThat(createEncoder())
        .converting(new Point(1, 2))
        .withMediaType("application/json; charset=utf-16")
        .succeedsWith("{\"x\":1,\"y\":2}", UTF_16);
  }

  @Test
  void serializeWithCustomTypeAdapter() {
    var gson = new GsonBuilder()
        .registerTypeAdapter(Point.class, new CompactPointAdapter())
        .create();
    verifyThat(createEncoder(gson))
        .converting(new Point(1, 2))
        .succeedsWith("[1,2]");
  }

  @Test
  void serializeList() {
    verifyThat(createEncoder())
        .converting(List.of(new Point(1, 2), new Point(3, 4)))
        .succeedsWith("[{\"x\":1,\"y\":2},{\"x\":3,\"y\":4}]");
  }

  @Test
  void serializeListWithCustomTypeAdapter() {
    var gson = new GsonBuilder()
        .registerTypeAdapter(Point.class, new CompactPointAdapter())
        .create();
    verifyThat(createEncoder(gson))
        .converting(List.of(new Point(1, 2), new Point(3, 4)))
        .succeedsWith("[[1,2],[3,4]]");
  }

  @Test
  void serializeWithUnsupportedMediaType() {
    verifyThat(createEncoder())
        .converting(new Point(1, 2))
        .withMediaType("application/xml")
        .isNotSupported();
  }

  @Test
  void mediaTypeIsAttached() {
    verifyThat(createEncoder())
        .converting(new Point(1, 2))
        .withMediaType("application/json")
        .asBodyPublisher()
        .hasMediaType("application/json");
  }

  @Test
  void mediaTypeWithCharsetIsAttached() {
    verifyThat(createEncoder())
        .converting(new Point(1, 2))
        .withMediaType("application/json; charset=utf-16")
        .asBodyPublisher()
        .hasMediaType("application/json; charset=utf-16");
  }
}
