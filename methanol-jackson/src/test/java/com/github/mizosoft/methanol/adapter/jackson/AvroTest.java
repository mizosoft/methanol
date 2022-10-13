/*
 * Copyright (c) 2022 Moataz Abdelnasser
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

import static com.github.mizosoft.methanol.adapter.jackson.JacksonAdapterFactory.createDecoder;
import static com.github.mizosoft.methanol.adapter.jackson.JacksonAdapterFactory.createEncoder;
import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.dataformat.avro.AvroMapper;
import com.fasterxml.jackson.dataformat.avro.AvroSchema;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.TypeRef;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;

class AvroTest {
  private static final MediaType APPLICATION_AVRO = MediaType.parse("application/*+avro");

  private static final AvroSchema POINT_SCHEMA =
      new AvroSchema(
          new Schema.Parser()
              .setValidate(true)
              .parse(
                  "{\n"
                      + "\"type\": \"record\",\n"
                      + "\"name\": \"Point\",\n"
                      + "\"fields\": [\n"
                      + " {\"name\": \"x\", \"type\": \"int\"},\n"
                      + " {\"name\": \"y\", \"type\": \"int\"}\n"
                      + "]}"));

  @Test
  void encode() throws IOException {
    ObjectWriterFactory writerFactory =
        (mapper, type) -> {
          assertThat(type).isEqualTo(TypeRef.from(Point.class));
          return mapper.writerFor(Point.class).with(POINT_SCHEMA);
        };
    var mapper = new AvroMapper();
    verifyThat(createEncoder(mapper, writerFactory, APPLICATION_AVRO))
        .converting(new Point(1, 2))
        .succeedsWith(
            ByteBuffer.wrap(mapper.writer(POINT_SCHEMA).writeValueAsBytes(new Point(1, 2))));
  }

  @Test
  void decode() throws IOException {
    ObjectReaderFactory readerFactory =
        (mapper, type) -> {
          assertThat(type).isEqualTo(TypeRef.from(Point.class));
          return mapper.readerFor(Point.class).with(POINT_SCHEMA);
        };
    var mapper = new AvroMapper();
    verifyThat(createDecoder(mapper, readerFactory, APPLICATION_AVRO))
        .converting(Point.class)
        .withBody(ByteBuffer.wrap(mapper.writer(POINT_SCHEMA).writeValueAsBytes(new Point(1, 2))))
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void deferredDecode() throws IOException {
    ObjectReaderFactory readerFactory =
        (mapper, type) -> {
          assertThat(type).isEqualTo(TypeRef.from(Point.class));
          return mapper.readerFor(Point.class).with(POINT_SCHEMA);
        };
    var mapper = new AvroMapper();
    verifyThat(createDecoder(mapper, readerFactory, APPLICATION_AVRO))
        .converting(Point.class)
        .withDeferredBody(
            ByteBuffer.wrap(mapper.writer(POINT_SCHEMA).writeValueAsBytes(new Point(1, 2))))
        .succeedsWith(new Point(1, 2));
  }
}
