package com.github.mizosoft.methanol.adapter.jackson;

import static com.github.mizosoft.methanol.adapter.jackson.JacksonAdapterFactory.createDecoder;
import static com.github.mizosoft.methanol.adapter.jackson.JacksonAdapterFactory.createEncoder;
import static com.github.mizosoft.methanol.testutils.Verification.verifyThat;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.github.mizosoft.methanol.MediaType;
import org.junit.jupiter.api.Test;

class JacksonXmlTest {
  @Test
  void encode() {
    verifyThat(createEncoder(new XmlMapper(), MediaType.TEXT_XML))
        .converting(new Point(1, 2))
        .succeedsWith("<Point><x>1</x><y>2</y></Point>");
  }

  @Test
  void decode() {
    verifyThat(createDecoder(new XmlMapper(), MediaType.TEXT_XML))
        .converting(Point.class)
        .withBody("<point><x>1</x><y>2</y></point>")
        .succeedsWith(new Point(1, 2));
  }
}
