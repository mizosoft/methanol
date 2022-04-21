package com.github.mizosoft.methanol.adapter.jackson;

import static com.github.mizosoft.methanol.adapter.jackson.JacksonAdapterFactory.createEncoder;
import static com.github.mizosoft.methanol.testutils.Verification.verifyThat;
import static java.nio.charset.StandardCharsets.UTF_16;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.github.mizosoft.methanol.MediaType;
import org.junit.jupiter.api.Test;

class XmlTest {
  @Test
  void encode() {
    verifyThat(createEncoder(new XmlMapper(), MediaType.TEXT_XML))
        .converting(new Point(1, 2))
        .succeedsWith("<Point><x>1</x><y>2</y></Point>");
  }

  @Test
  void encodeUtf16() {
    verifyThat(createEncoder(new XmlMapper(), MediaType.TEXT_XML))
        .converting(new Point(1, 2))
        .withMediaType("text/xml; charset=utf-16")
        .succeedsWith("<Point><x>1</x><y>2</y></Point>", UTF_16);
  }

  @Test
  void decode() {
    verifyThat(JacksonAdapterFactory.createDecoder(new XmlMapper(), MediaType.TEXT_XML))
        .converting(Point.class)
        .withBody("<point><x>1</x><y>2</y></point>")
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void decodeUtf16() {
    verifyThat(JacksonAdapterFactory.createDecoder(new XmlMapper(), MediaType.TEXT_XML))
        .converting(Point.class)
        .withMediaType("text/xml; charset=utf-16")
        .withBody("<point><x>1</x><y>2</y></point>", UTF_16)
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void deferredDecode() {
    verifyThat(JacksonAdapterFactory.createDecoder(new XmlMapper(), MediaType.TEXT_XML))
        .converting(Point.class)
        .withDeferredBody("<point><x>1</x><y>2</y></point>")
        .succeedsWith(new Point(1, 2));
  }
}
