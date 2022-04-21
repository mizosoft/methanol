package com.github.mizosoft.methanol.adapter.jackson;

import static com.github.mizosoft.methanol.adapter.jackson.JacksonAdapterFactory.createDecoder;
import static com.github.mizosoft.methanol.adapter.jackson.JacksonAdapterFactory.createEncoder;
import static com.github.mizosoft.methanol.testutils.Verification.verifyThat;
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
