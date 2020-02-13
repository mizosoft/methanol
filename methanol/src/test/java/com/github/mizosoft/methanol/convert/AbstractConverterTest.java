package com.github.mizosoft.methanol.convert;

import static org.junit.jupiter.api.Assertions.*;

import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.TypeReference;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AbstractConverterTest {

  @Test
  void isCompatibleWith_single() {
    var converter = new ConverterImpl(MediaType.of("text", "*"));
    assertTrue(converter.isCompatibleWith(MediaType.of("text", "plain")));
    assertTrue(converter.isCompatibleWith(MediaType.of("text", "html")));
    assertFalse(converter.isCompatibleWith(MediaType.of("application", "octet-stream")));
  }

  @Test
  void isCompatibleWith_multiple() {
    var converter = new ConverterImpl(
        MediaType.of("text", "plain"), MediaType.of("application", "json"));
    assertTrue(converter.isCompatibleWith(MediaType.of("text", "plain")));
    assertTrue(converter.isCompatibleWith(MediaType.of("application", "json")));
    assertTrue(converter.isCompatibleWith(MediaType.of("application", "*")));
    assertFalse(converter.isCompatibleWith(MediaType.of("application", "octet_stream")));
  }

  @Test
  void requireSupport() {
    var converter = new AbstractConverter() {
      @Override
      public boolean supportsType(TypeReference<?> type) {
        return List.class.isAssignableFrom(type.rawType());
      }
    };
    assertThrows(UnsupportedOperationException.class,
        () -> converter.requireSupport(TypeReference.from(Set.class)));
  }

  @Test
  void requireCompatibleOrNull() {
    var converter = new ConverterImpl(MediaType.of("text", "plain"));
    assertThrows(UnsupportedOperationException.class,
        () -> converter.requireCompatibleOrNull(MediaType.of("application", "json")));
  }

  private static final class ConverterImpl extends AbstractConverter {

    ConverterImpl(MediaType... compatibleMediaTypes) {
      super(compatibleMediaTypes);
    }

    @Override
    public boolean supportsType(TypeReference<?> type) {
      return false;
    }
  }
}