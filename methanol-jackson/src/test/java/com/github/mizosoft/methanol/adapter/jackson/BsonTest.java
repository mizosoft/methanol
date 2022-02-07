package com.github.mizosoft.methanol.adapter.jackson;

import static com.github.mizosoft.methanol.testutils.Verification.verifyThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mizosoft.methanol.MediaType;
import de.undercouch.bson4jackson.BsonFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class BsonTest {
  private static final MediaType APPLICATION_BSON = MediaType.parse("application/bson");

  @Test
  void encode() throws IOException {
    var mapper = new ObjectMapper(new BsonFactory());
    verifyThat(JacksonAdapterFactory.createEncoder(mapper, APPLICATION_BSON))
        .converting(new Point(1, 2))
        .succeedsWith(
            ByteBuffer.wrap(mapper.writerFor(Point.class).writeValueAsBytes(new Point(1, 2))));
  }

  @Test
  void decode() throws IOException {
    var mapper = new ObjectMapper(new BsonFactory());
    verifyThat(JacksonAdapterFactory.createDecoder(mapper, APPLICATION_BSON))
        .converting(Point.class)
        .withBody(ByteBuffer.wrap(mapper.writerFor(Point.class).writeValueAsBytes(new Point(1, 2))))
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void deferredDecode() throws IOException {
    var mapper = new ObjectMapper(new BsonFactory());
    verifyThat(JacksonAdapterFactory.createDecoder(mapper, APPLICATION_BSON))
        .converting(Point.class)
        .withDeferredBody(
            ByteBuffer.wrap(mapper.writerFor(Point.class).writeValueAsBytes(new Point(1, 2))))
        .succeedsWith(new Point(1, 2));
  }
}
