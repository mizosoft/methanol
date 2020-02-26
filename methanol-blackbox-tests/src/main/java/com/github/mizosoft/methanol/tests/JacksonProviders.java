package com.github.mizosoft.methanol.tests;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.BodyAdapter.Encoder;
import com.github.mizosoft.methanol.adapter.jackson.JacksonBodyAdapterFactory;

public class JacksonProviders {

  private static final ObjectMapper configuredMapper =
      new JsonMapper()
          .disable(MapperFeature.AUTO_DETECT_GETTERS)
          .disable(MapperFeature.AUTO_DETECT_SETTERS)
          .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
          .setVisibility(PropertyAccessor.ALL, Visibility.ANY);

  private JacksonProviders() {}

  public static class EncoderProvider {

    private EncoderProvider() {}

    public static Encoder provider() {
      return JacksonBodyAdapterFactory.createEncoder(configuredMapper);
    }
  }

  public static class DecoderProvider {

    private DecoderProvider() {}

    public static BodyAdapter.Decoder provider() {
      return JacksonBodyAdapterFactory.createDecoder(configuredMapper);
    }
  }
}
