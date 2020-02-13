package com.github.mizosoft.methanol.convert;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.Converter;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.TypeReference;
import java.nio.charset.Charset;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Abstract {@link Converter} that implements {@link Converter#isCompatibleWith(MediaType)} by
 * specifying a set of {@code MediaTypes} the converter is compatible with.
 */
public abstract class AbstractConverter implements Converter {

  private final Set<MediaType> compatibleMediaTypes;

  /**
   * Creates a converter compatible with the given media types.
   */
  protected AbstractConverter(MediaType... compatibleMediaTypes) {
    this.compatibleMediaTypes = Set.of(compatibleMediaTypes);
  }

  @Override
  public final boolean isCompatibleWith(MediaType mediaType) {
    requireNonNull(mediaType);
    for (MediaType supported : compatibleMediaTypes) {
      if (supported.isCompatibleWith(mediaType)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns an immutable set containing the media types this converter is compatible with.
   */
  protected Set<MediaType> compatibleMediaTypes() {
    return compatibleMediaTypes;
  }

  /**
   * @throws UnsupportedOperationException if the converter doesn't {@link
   * Converter#supportsType(TypeReference) support} the given type.
   */
  protected void requireSupport(TypeReference<?> type) {
    if (!supportsType(type)) {
      throw new UnsupportedOperationException("unsupported type: " + type);
    }
  }

  /**
   * @throws UnsupportedOperationException if the converter is not {@link
   * Converter#isCompatibleWith(MediaType) compatible} the given type.
   */
  protected void requireCompatibleOrNull(@Nullable MediaType mediaType) {
    if (mediaType != null && !isCompatibleWith(mediaType)) {
      throw new UnsupportedOperationException("converter not compatible with: " + mediaType);
    }
  }

  /**
   * Returns either the result of {@link MediaType#charsetOrDefault(Charset)} or
   * {@code defaultCharset} directly if {@code mediaType} is null.
   */
  public static Charset charsetOrDefault(@Nullable MediaType mediaType, Charset defaultCharset) {
    return mediaType != null ? mediaType.charsetOrDefault(defaultCharset) : defaultCharset;
  }
}
