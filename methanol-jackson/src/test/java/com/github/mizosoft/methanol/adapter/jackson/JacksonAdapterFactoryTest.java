package com.github.mizosoft.methanol.adapter.jackson;

import static com.github.mizosoft.methanol.MediaType.APPLICATION_JSON;
import static com.github.mizosoft.methanol.MediaType.APPLICATION_XHTML_XML;
import static com.github.mizosoft.methanol.MediaType.APPLICATION_XML;
import static com.github.mizosoft.methanol.adapter.jackson.JacksonAdapterFactory.createDecoder;
import static com.github.mizosoft.methanol.adapter.jackson.JacksonAdapterFactory.createEncoder;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter;
import java.lang.reflect.InvocationTargetException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JacksonAdapterFactoryTest {
  @Test
  void defaultAdapterHasJsonMediaType() {
    assertThat(mediaTypes(createEncoder())).containsOnly(APPLICATION_JSON);
    assertThat(mediaTypes(createDecoder())).containsOnly(APPLICATION_JSON);
  }

  @Test
  void adapterWithCustomMediaTypes() {
    assertThat(
            mediaTypes(createEncoder(new ObjectMapper(), APPLICATION_XML, APPLICATION_XHTML_XML)))
        .containsOnly(APPLICATION_XML, APPLICATION_XHTML_XML);
    assertThat(
            mediaTypes(createDecoder(new ObjectMapper(), APPLICATION_XML, APPLICATION_XHTML_XML)))
        .containsOnly(APPLICATION_XML, APPLICATION_XHTML_XML);
  }

  @Test
  void adaptersWithCustomCodecAndMediaTypes() {
    assertThat(
            mediaTypes(
                createEncoder(
                    new ObjectMapper(),
                    ObjectWriterFactory.getDefault(),
                    APPLICATION_XML,
                    APPLICATION_XHTML_XML)))
        .containsOnly(APPLICATION_XML, APPLICATION_XHTML_XML);
    assertThat(
            mediaTypes(
                createDecoder(
                    new ObjectMapper(),
                    ObjectReaderFactory.getDefault(),
                    APPLICATION_XML,
                    APPLICATION_XHTML_XML)))
        .containsOnly(APPLICATION_XML, APPLICATION_XHTML_XML);
  }

  @SuppressWarnings("unchecked")
  private static Set<MediaType> mediaTypes(BodyAdapter adapter) {
    assertThat(adapter).isInstanceOf(AbstractBodyAdapter.class);
    try {
      var method = AbstractBodyAdapter.class.getDeclaredMethod("compatibleMediaTypes");
      method.setAccessible(true);
      return (Set<MediaType>) method.invoke(adapter);
    } catch (InvocationTargetException | NoSuchMethodException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }
}
