package com.github.mizosoft.methanol.adapter.jackson;

import static com.github.mizosoft.methanol.testutils.Verification.verifyThat;
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
          assertThat(type).isEqualTo(TypeRef.from(Point.class));
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
          assertThat(type).isEqualTo(TypeRef.from(Point.class));
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
