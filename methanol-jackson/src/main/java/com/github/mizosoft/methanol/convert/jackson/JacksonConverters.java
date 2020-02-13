package com.github.mizosoft.methanol.convert.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.mizosoft.methanol.Converter;
import com.github.mizosoft.methanol.convert.jackson.JacksonConverter.OfRequest;
import com.github.mizosoft.methanol.convert.jackson.JacksonConverter.OfResponse;

/**
 * Provides {@link Converter} implementations for the JSON format using Jackson.
 */
public class JacksonConverters {

  /**
   * Returns a {@code Converter.OfRequest} that uses a default {@code ObjectMapper} instance.
   */
  public static Converter.OfRequest createOfRequest() {
    return createOfRequest(new JsonMapper());
  }

  /**
   * Returns a {@code Converter.OfRequest} that uses the given {@code ObjectMapper} instance.
   *
   * @param mapper the {@code ObjectMapper}
   */
  public static Converter.OfRequest createOfRequest(ObjectMapper mapper) {
    return new OfRequest(mapper);
  }

  /**
   * Returns a {@code Converter.OfResponse} that uses a default {@code ObjectMapper} instance.
   */
  public static Converter.OfResponse createOfResponse() {
    return createOfResponse(new JsonMapper());
  }

  /**
   * Returns a {@code Converter.OfResponse} that uses the given {@code ObjectMapper} instance.
   *
   * @param mapper the {@code ObjectMapper}
   */
  public static Converter.OfResponse createOfResponse(ObjectMapper mapper) {
    return new OfResponse(mapper);
  }
}
