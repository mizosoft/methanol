/*
 * Copyright (c) 2024 Moataz Hussein
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.mizosoft.methanol;

import static com.github.mizosoft.methanol.internal.Utils.escapeAndQuoteValueIfNeeded;
import static com.github.mizosoft.methanol.internal.Utils.requireValidToken;
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static com.github.mizosoft.methanol.internal.text.HttpCharMatchers.QUOTED_PAIR_MATCHER;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.text.HeaderValueTokenizer;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Basics_of_HTTP/MIME_types">MIME
 * type</a>. The {@linkplain #toString() text representation} of this class can be used as the value
 * of the {@code Content-Type} HTTP header.
 *
 * <p>A {@code MediaType} also defines a <a
 * href="https://tools.ietf.org/html/rfc7231#section-5.3.2">media range</a>. A media range has
 * either both wildcard type and subtype, both concrete type and subtype, or a concrete type and a
 * wildcard subtype (but not a wildcard type and a concrete subtype), with the character {@code *}
 * denoting a wildcard. Inclusion in media ranges can be tested using any of {@link
 * #includes(MediaType)} or {@link #isCompatibleWith(MediaType)}, with the latter being symmetric
 * among operands.
 *
 * <p>Case insensitive attributes such as the type, subtype, parameter names or the value of the
 * charset parameter are converted into lower-case.
 */
public final class MediaType {
  private static final String CHARSET_ATTRIBUTE = "charset";
  private static final String WILDCARD = "*";

  private static final String APPLICATION_TYPE = "application";
  private static final String IMAGE_TYPE = "image";
  private static final String TEXT_TYPE = "text";

  /*---Media Ranges---*/

  /** {@code *}{@code /*} */
  public static final MediaType ANY = new MediaType("*", "*");

  /** {@code application}{@code /*} */
  public static final MediaType APPLICATION_ANY = new MediaType(APPLICATION_TYPE, "*");

  /** {@code image}{@code /*} */
  public static final MediaType IMAGE_ANY = new MediaType(IMAGE_TYPE, "*");

  /** {@code text}{@code /*} */
  public static final MediaType TEXT_ANY = new MediaType(TEXT_TYPE, "*");

  /*---Application Types---*/

  /** {@code application/x-www-form-urlencoded} */
  public static final MediaType APPLICATION_FORM_URLENCODED =
      new MediaType(APPLICATION_TYPE, "x-www-form-urlencoded");

  /** {@code application/json} */
  public static final MediaType APPLICATION_JSON = new MediaType(APPLICATION_TYPE, "json");

  /** {@code application/octet-stream} */
  public static final MediaType APPLICATION_OCTET_STREAM =
      new MediaType(APPLICATION_TYPE, "octet-stream");

  /** {@code application/xhtml+xml} */
  public static final MediaType APPLICATION_XHTML_XML =
      new MediaType(APPLICATION_TYPE, "xhtml+xml");

  /** {@code application/xml} */
  public static final MediaType APPLICATION_XML = new MediaType(APPLICATION_TYPE, "xml");

  /** {@code application/x-protobuf} */
  public static final MediaType APPLICATION_X_PROTOBUF =
      new MediaType(APPLICATION_TYPE, "x-protobuf");

  /*---Image Types---*/

  /** {@code image/gif} */
  public static final MediaType IMAGE_GIF = new MediaType(IMAGE_TYPE, "gif");

  /** {@code image/jpeg} */
  public static final MediaType IMAGE_JPEG = new MediaType(IMAGE_TYPE, "jpeg");

  /** {@code image/png} */
  public static final MediaType IMAGE_PNG = new MediaType(IMAGE_TYPE, "png");

  /*---Text Types---*/

  /** {@code text/html} */
  public static final MediaType TEXT_HTML = new MediaType(TEXT_TYPE, "html");

  /** {@code text/markdown} */
  public static final MediaType TEXT_MARKDOWN = new MediaType(TEXT_TYPE, "markdown");

  /** {@code text/plain} */
  public static final MediaType TEXT_PLAIN = new MediaType(TEXT_TYPE, "plain");

  /** {@code text/xml} */
  public static final MediaType TEXT_XML = new MediaType(TEXT_TYPE, "xml");

  private final String type;
  private final String subtype;
  private final Map<String, String> parameters;
  private @MonotonicNonNull Charset lazyCharset;
  private boolean isCharsetParsed;

  private @MonotonicNonNull String lazyToString;

  private MediaType(String type, String subtype) {
    this.type = type;
    this.subtype = subtype;
    this.parameters = Map.of();
  }

  private MediaType(String type, String subtype, Map<String, String> parameters) {
    this.type = type;
    this.subtype = subtype;
    this.parameters = parameters;
  }

  /** Returns the general type. */
  public String type() {
    return type;
  }

  /** Returns the subtype. */
  public String subtype() {
    return subtype;
  }

  /** Returns an immutable map representing the parameters. */
  public Map<String, String> parameters() {
    return parameters;
  }

  /**
   * Returns an {@code Optional} representing the value of the charset parameter. An empty {@code
   * Optional} is returned if no such parameter exists.
   *
   * @throws IllegalCharsetNameException if a charset parameter exists the value of which is invalid
   * @throws UnsupportedCharsetException if a charset parameter exists the value of which is not
   *     supported in this JVM
   */
  public Optional<Charset> charset() {
    if (!isCharsetParsed) {
      var charsetName = parameters.get(CHARSET_ATTRIBUTE);
      if (charsetName != null) {
        lazyCharset = Charset.forName(charsetName);
      }
      isCharsetParsed = true;
    }
    return Optional.ofNullable(lazyCharset);
  }

  /**
   * Returns either the value of the charset parameter or the given default charset if no such
   * parameter exists or if the charset has no support in this JVM.
   *
   * @throws IllegalCharsetNameException if a charset parameter exists the value of which is invalid
   */
  public Charset charsetOrDefault(Charset defaultCharset) {
    requireNonNull(defaultCharset);
    try {
      return charset().orElse(defaultCharset);
    } catch (UnsupportedCharsetException ignored) {
      return defaultCharset;
    }
  }

  /**
   * Equivalent to calling {@link #charsetOrDefault(Charset)
   * charsetOrDefault(StandardCharsets.UTF_8)}.
   */
  public Charset charsetOrUtf8() {
    return charsetOrDefault(UTF_8);
  }

  /**
   * Returns {@code true} if this media type is {@code *}{@code /*} or if it has a wildcard subtype.
   */
  public boolean hasWildcard() {
    return type.equals(WILDCARD) || subtype.equals(WILDCARD);
  }

  /**
   * Returns whether this media type includes the given one. A media type includes the other if the
   * former's parameters is a subset of the latter's and either the former is a {@link
   * #hasWildcard() wildcard type} that includes the latter or both have equal concrete type and
   * subtype.
   */
  public boolean includes(MediaType other) {
    return includes(other.type, other.subtype)
        && other.parameters.entrySet().containsAll(parameters.entrySet());
  }

  private boolean includes(String otherType, String otherSubtype) {
    return type.equals(WILDCARD) || (type.equals(otherType) && includesSubtype(otherSubtype));
  }

  private boolean includesSubtype(String otherSubtype) {
    int structuredSuffixIndex;
    return subtype.equals(WILDCARD)
        || subtype.equals(otherSubtype)
        || ((structuredSuffixIndex = otherSubtype.lastIndexOf('+')) != -1
            && otherSubtype.regionMatches(structuredSuffixIndex + 1, subtype, 0, subtype.length()));
  }

  /**
   * Returns whether this media type is compatible with the given one. Two media types are
   * compatible if either of them {@link #includes(MediaType) includes} the other.
   */
  public boolean isCompatibleWith(MediaType other) {
    return this.includes(other) || other.includes(this);
  }

  /**
   * Returns a new {@code MediaType} with this instance's type, subtype and parameters but with the
   * name of the given charset as the value of the charset parameter.
   */
  public MediaType withCharset(Charset charset) {
    requireNonNull(charset);
    var mediaType = withParameter(CHARSET_ATTRIBUTE, charset.name());
    mediaType.lazyCharset = charset;
    mediaType.isCharsetParsed = true;
    return mediaType;
  }

  /**
   * Returns a new {@code MediaType} with this instance's type, subtype and parameters but with the
   * value of the parameter specified by the given name set to the given value.
   *
   * @throws IllegalArgumentException if the given name or value is invalid
   */
  public MediaType withParameter(String name, String value) {
    return withParameters(Map.of(name, value));
  }

  /**
   * Returns a new {@code MediaType} with this instance's type, subtype and parameters but with each
   * of the given parameters' names set to their corresponding values.
   *
   * @throws IllegalArgumentException if any of the given parameters is invalid
   */
  public MediaType withParameters(Map<String, String> parameters) {
    return of(type, subtype, parameters, new LinkedHashMap<>(this.parameters));
  }

  /**
   * Tests the given object for equality with this instance. {@code true} is returned if the given
   * object is a {@code MediaType} and both instances' type, subtype and parameters are equal.
   *
   * @param obj the object to test for equality
   */
  @Override
  public boolean equals(@Nullable Object obj) {
    if (obj == this) {
      return true;
    }
    if (!(obj instanceof MediaType)) {
      return false;
    }

    var other = (MediaType) obj;
    return type.equals(other.type)
        && subtype.equals(other.subtype)
        && parameters.equals(other.parameters);
  }

  /** Returns a hashcode for this media type. */
  @Override
  public int hashCode() {
    return Objects.hash(type, subtype, parameters);
  }

  /**
   * Returns a text representation of this media type that is compatible with the value of the
   * {@code Content-Type} header.
   */
  @Override
  public String toString() {
    var toString = lazyToString;
    if (toString == null) {
      toString = computeToString();
      lazyToString = toString;
    }
    return toString;
  }

  private String computeToString() {
    var sb = new StringBuilder().append(type).append("/").append(subtype);
    parameters.forEach(
        (name, value) ->
            sb.append("; ").append(name).append("=").append(escapeAndQuoteValueIfNeeded(value)));
    return sb.toString();
  }

  /**
   * Returns a new {@code MediaType} with the given type and subtype.
   *
   * @throws IllegalArgumentException if the given type or subtype is invalid
   */
  public static MediaType of(String type, String subtype) {
    return of(type, subtype, Map.of());
  }

  /**
   * Returns a new {@code MediaType} with the given type, subtype and parameters.
   *
   * @throws IllegalArgumentException if the given type, subtype or any of the given parameters is
   *     invalid
   */
  public static MediaType of(String type, String subtype, Map<String, String> parameters) {
    return of(type, subtype, parameters, new LinkedHashMap<>());
  }

  private static MediaType of(
      String type,
      String subtype,
      Map<String, String> untrustedParameters,
      Map<String, String> trustedParameters) {
    requireNonNull(type);
    requireNonNull(subtype);
    requireNonNull(untrustedParameters);
    requireNonNull(trustedParameters);
    requireArgument(
        !type.equals(WILDCARD) || subtype.equals(WILDCARD),
        "Cannot have a wildcard type with a concrete subtype");
    var normalizedType = validateAndNormalizeToken(type);
    var normalizedSubtype = validateAndNormalizeToken(subtype);
    var parameters = new LinkedHashMap<>(trustedParameters);
    for (var entry : untrustedParameters.entrySet()) {
      var normalizedAttribute = validateAndNormalizeToken(entry.getKey());
      String normalizedValue;
      if (normalizedAttribute.equals(CHARSET_ATTRIBUTE)) {
        normalizedValue = validateAndNormalizeToken(entry.getValue());
      } else {
        normalizedValue = entry.getValue();
        requireArgument(
            QUOTED_PAIR_MATCHER.allMatch(normalizedValue), "Illegal value: '%s'", normalizedValue);
      }
      parameters.put(normalizedAttribute, normalizedValue);
    }
    return new MediaType(
        normalizedType, normalizedSubtype, Collections.unmodifiableMap(parameters));
  }

  private static String validateAndNormalizeToken(String token) {
    return requireValidToken(token).toLowerCase(Locale.ROOT);
  }

  /**
   * Parses the given string into a {@code MediaType} instance.
   *
   * @throws IllegalArgumentException if the given string is an invalid media type
   */
  public static MediaType parse(String value) {
    // media-type     = type "/" subtype *( OWS ";" OWS parameter )
    // type           = token
    // subtype        = token
    // parameter      = token "=" ( token / quoted-string )

    try {
      var tokenizer = new HeaderValueTokenizer(value);
      var type = tokenizer.nextToken();
      tokenizer.requireCharacter('/');
      var subtype = tokenizer.nextToken();

      Map<String, String> parameters = null;
      while (tokenizer.consumeDelimiter(';')) {
        if (parameters == null) {
          parameters = new LinkedHashMap<>();
        }
        var name = tokenizer.nextToken();
        tokenizer.requireCharacter('=');
        parameters.put(name, tokenizer.nextTokenOrQuotedString());
      }
      return parameters != null ? of(type, subtype, parameters) : of(type, subtype);
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new IllegalArgumentException("Couldn't parse: '" + value + "'", e);
    }
  }
}
