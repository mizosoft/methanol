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

package com.github.mizosoft.methanol.adapter.jackson3.flux;

import static com.github.mizosoft.methanol.adapter.jackson3.flux.JacksonFluxAdapterFactory.createEncoder;
import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static java.nio.charset.StandardCharsets.UTF_16;
import static reactor.adapter.JdkFlowAdapter.publisherToFlowPublisher;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

public class JacksonFluxEncoderTest {
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
    verifyThat(createEncoder()).isNotCompatibleWith("text/html").isNotCompatibleWith("text/*");
  }

  @Test
  void serializeMono() {
    verifyThat(createEncoder())
        .converting(Mono.just(new Point(1, 2)))
        .succeedsWith("{\"x\":1,\"y\":2}");
  }

  @Test
  void serializeMonoWithUtf16() {
    verifyThat(createEncoder())
        .converting(Mono.just(new Point(1, 2)))
        .withMediaType("application/json; charset=utf-16")
        .succeedsWith("{\"x\":1,\"y\":2}", UTF_16);
  }

  @Test
  void serializeMonoWithCustomSerializer() {
    var mapper =
        JsonMapper.builder()
            .addModule(new SimpleModule().addSerializer(Point.class, new CompactPointSerializer()))
            .build();
    verifyThat(createEncoder(mapper)).converting(Mono.just(new Point(1, 2))).succeedsWith("[1,2]");
  }

  @Test
  void serializeFlux() {
    verifyThat(createEncoder())
        .converting(Flux.just(new Point(1, 2), new Point(3, 4)))
        .succeedsWith("[{\"x\":1,\"y\":2},{\"x\":3,\"y\":4}]");
  }

  @Test
  void serializeFluxWithUtf16() {
    verifyThat(createEncoder())
        .converting(Flux.just(new Point(1, 2), new Point(3, 4)))
        .withMediaType("application/json; charset=utf-16")
        .succeedsWith("[{\"x\":1,\"y\":2},{\"x\":3,\"y\":4}]", UTF_16);
  }

  @Test
  void serializeFluxWithCustomSerializer() {
    var mapper =
        JsonMapper.builder()
            .addModule(new SimpleModule().addSerializer(Point.class, new CompactPointSerializer()))
            .build();
    verifyThat(createEncoder(mapper))
        .converting(Flux.just(new Point(1, 2), new Point(3, 4)))
        .succeedsWith("[[1,2],[3,4]]");
  }

  @Test
  void serializeFlowPublisher() {
    verifyThat(createEncoder())
        .converting(publisherToFlowPublisher(Flux.just(new Point(1, 2), new Point(3, 4))))
        .succeedsWith("[{\"x\":1,\"y\":2},{\"x\":3,\"y\":4}]");
  }

  @Test
  void serializeWithUnsupportedType() {
    class NotAPublisher {}

    verifyThat(createEncoder()).converting(new NotAPublisher()).isNotSupported();
  }

  @Test
  void serializeWithUnsupportedMediaType() {
    verifyThat(createEncoder())
        .converting(Mono.just(new Point(1, 2)))
        .withMediaType("application/xml")
        .isNotSupported();
  }

  @Test
  void mediaTypeIsAttached() {
    verifyThat(createEncoder())
        .converting(Mono.just(new Point(1, 2)))
        .withMediaType("application/json")
        .asBodyPublisher()
        .hasMediaType("application/json");
  }

  @Test
  void mediaTypeWithCharsetIsAttached() {
    verifyThat(createEncoder())
        .converting(Mono.just(new Point(1, 2)))
        .withMediaType("application/json; charset=utf-16")
        .asBodyPublisher()
        .hasMediaType("application/json; charset=utf-16");
  }
}
