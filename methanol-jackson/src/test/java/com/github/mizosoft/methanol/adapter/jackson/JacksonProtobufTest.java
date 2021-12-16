package com.github.mizosoft.methanol.adapter.jackson;

import static com.github.mizosoft.methanol.adapter.jackson.JacksonAdapterFactory.createDecoder;
import static com.github.mizosoft.methanol.adapter.jackson.JacksonAdapterFactory.createEncoder;
import static com.github.mizosoft.methanol.testutils.Verification.verifyThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.dataformat.protobuf.ProtobufMapper;
import com.fasterxml.jackson.dataformat.protobuf.schema.ProtobufSchema;
import com.fasterxml.jackson.dataformat.protobuf.schema.ProtobufSchemaLoader;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.TypeRef;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

// TODO support binary formats
@Disabled("JacksonAdapter currently assumes the formats it can handle are text-based")
class JacksonProtobufTest {
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
    verifyThat(createEncoder(mapper, writerFactory, MediaType.TEXT_XML))
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
    verifyThat(createDecoder(mapper, readerFactory, MediaType.TEXT_XML))
        .converting(Point.class)
        .withBody(ByteBuffer.wrap(mapper.writer(POINT_SCHEMA).writeValueAsBytes(new Point(1, 2))))
        .succeedsWith(new Point(1, 2));
  }
}
