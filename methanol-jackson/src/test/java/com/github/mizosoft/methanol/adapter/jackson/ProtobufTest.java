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

package com.github.mizosoft.methanol.adapter.jackson;

import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.dataformat.protobuf.ProtobufMapper;
import com.fasterxml.jackson.dataformat.protobuf.schema.ProtobufSchema;
import com.fasterxml.jackson.dataformat.protobuf.schema.ProtobufSchemaLoader;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.TypeRef;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class ProtobufTest {
  private static final ProtobufSchema POINT_SCHEMA;

  static {
    try {
      POINT_SCHEMA =
          ProtobufSchemaLoader.std.parse(
              "message Point {\n"
                  + "  required int32 x = 1;\n"
                  + "  required int32 y = 2;\n"
                  + "}");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void encode() throws IOException {
    ObjectWriterFactory writerFactory =
        (mapper, type) -> {
          assertThat(type).isEqualTo(TypeRef.of(Point.class));
          return mapper.writerFor(Point.class).with(POINT_SCHEMA);
        };
    var mapper = new ProtobufMapper();
    verifyThat(
            JacksonAdapterFactory.createEncoder(
                mapper, writerFactory, MediaType.APPLICATION_X_PROTOBUF))
        .converting(new Point(1, 2))
        .succeedsWith(
            ByteBuffer.wrap(mapper.writer(POINT_SCHEMA).writeValueAsBytes(new Point(1, 2))));
  }

  @Test
  void decode() throws IOException {
    ObjectReaderFactory readerFactory =
        (mapper, type) -> {
          assertThat(type).isEqualTo(TypeRef.of(Point.class));
          return mapper.readerFor(Point.class).with(POINT_SCHEMA);
        };
    var mapper = new ProtobufMapper();
    verifyThat(
            JacksonAdapterFactory.createDecoder(
                mapper, readerFactory, MediaType.APPLICATION_X_PROTOBUF))
        .converting(Point.class)
        .withBody(ByteBuffer.wrap(mapper.writer(POINT_SCHEMA).writeValueAsBytes(new Point(1, 2))))
        .succeedsWith(new Point(1, 2));
  }
}
