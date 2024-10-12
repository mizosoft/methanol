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

package com.github.mizosoft.methanol.adapter.gson;

import static com.github.mizosoft.methanol.adapter.gson.GsonAdapterFactory.createDecoder;
import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static java.nio.charset.StandardCharsets.UTF_16;

import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.testing.TestException;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class GsonDeferredDecoderTest {
  @Test
  void deserialize() {
    verifyThat(createDecoder())
        .converting(Point.class)
        .withDeferredBody("{\"x\":1, \"y\":2}")
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void deserializeWithUtf16() {
    verifyThat(createDecoder())
        .converting(Point.class)
        .withMediaType("application/json; charset=utf-16")
        .withDeferredBody("{\"x\":1, \"y\":2}", UTF_16)
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void deserializeWithCustomTypeAdapter() {
    var gson =
        new GsonBuilder().registerTypeAdapter(Point.class, new CompactPointAdapter()).create();
    verifyThat(createDecoder(gson))
        .converting(Point.class)
        .withDeferredBody("[1, 2]")
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void deserializeWithGenerics() {
    verifyThat(createDecoder())
        .converting(new TypeRef<List<Point>>() {})
        .withDeferredBody("[{\"x\":1, \"y\":2}, {\"x\":3, \"y\":4}]")
        .succeedsWith(List.of(new Point(1, 2), new Point(3, 4)));
  }

  @Test
  void deserializeWithGenericsAndCustomTypeAdapter() {
    var gson =
        new GsonBuilder().registerTypeAdapter(Point.class, new CompactPointAdapter()).create();
    verifyThat(createDecoder(gson))
        .converting(new TypeRef<List<Point>>() {})
        .withDeferredBody("[[1, 2], [3, 4]]")
        .succeedsWith(List.of(new Point(1, 2), new Point(3, 4)));
  }

  @Test
  void deserializeWithLenientGson() {
    var gson = new GsonBuilder().setLenient().create();
    verifyThat(createDecoder(gson))
        .converting(Point.class)
        .withDeferredBody("{\n" + "  x: '1',\n" + "  y: '2' // This is a comment \n" + "}")
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void deserializeBadJson() {
    verifyThat(createDecoder())
        .converting(Point.class)
        .withDeferredBody("{x:\"1\", y:\"2\"") // Missing enclosing bracket
        .failsWith(JsonSyntaxException.class);
  }

  @Test
  void deserializeWithError() {
    // Upstream errors cause the stream used by the supplier to throw an IOException with the error
    // as its cause.
    verifyThat(createDecoder())
        .converting(Point.class)
        .withDeferredFailure(new TestException())
        .failsWith(JsonSyntaxException.class)
        .havingCause()
        .isInstanceOf(IOException.class)
        .withCauseInstanceOf(TestException.class);
  }
}
