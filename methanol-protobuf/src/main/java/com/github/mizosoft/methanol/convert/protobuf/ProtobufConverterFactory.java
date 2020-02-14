package com.github.mizosoft.methanol.convert.protobuf;

import com.github.mizosoft.methanol.Converter;
import com.google.protobuf.ExtensionRegistryLite;

/**
 * Providers {@link Converter} for google's <a href="https://developers.google.com/protocol-buffers">
 * protocol buffers</a> format. The converters support any subtype of {@link
 * com.google.protobuf.MessageLite}.
 */
public class ProtobufConverterFactory {

  private ProtobufConverterFactory() {
  }

  /**
   * Returns a new {@code Converter.OfRequest}.
   */
  public static Converter.OfRequest createOfRequest() {
    return new ProtobufConverter.OfRequest();
  }

  /**
   * Returns a new {@code Converter.OfResponse} with an empty extension registry.
   */
  public static Converter.OfResponse createOfResponse() {
    return createOfResponse(ExtensionRegistryLite.getEmptyRegistry());
  }

  /**
   * Returns a new {@code Converter.OfResponse} that uses the given extension registry.
   */
  public static Converter.OfResponse createOfResponse(ExtensionRegistryLite registry) {
    return new ProtobufConverter.OfResponse(registry);
  }
}
