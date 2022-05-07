package com.github.mizosoft.methanol.blackbox.support;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

public class JacksonMapper {
  private static final ObjectMapper configuredMapper =
      JsonMapper.builder()
          .disable(MapperFeature.AUTO_DETECT_GETTERS)
          .disable(MapperFeature.AUTO_DETECT_SETTERS)
          .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
          .build()
          .setVisibility(PropertyAccessor.ALL, Visibility.ANY);

  public static ObjectMapper get() {
    return configuredMapper;
  }
}
