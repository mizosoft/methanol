/*
 * Copyright (c) 2022 Moataz Abdelnasser
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

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_16;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.from;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MediaTypeTest {
  @Test
  void constantsSanityTest() {
    assertThat(MediaType.ANY).hasToString("*/*");
    assertThat(MediaType.APPLICATION_ANY).hasToString("application/*");
    assertThat(MediaType.APPLICATION_ANY).hasToString("application/*");
    assertThat(MediaType.IMAGE_ANY).hasToString("image/*");
    assertThat(MediaType.TEXT_ANY).hasToString("text/*");
    assertThat(MediaType.APPLICATION_FORM_URLENCODED)
        .hasToString("application/x-www-form-urlencoded");
    assertThat(MediaType.APPLICATION_JSON).hasToString("application/json");
    assertThat(MediaType.APPLICATION_OCTET_STREAM).hasToString("application/octet-stream");
    assertThat(MediaType.APPLICATION_XHTML_XML).hasToString("application/xhtml+xml");
    assertThat(MediaType.APPLICATION_XML).hasToString("application/xml");
    assertThat(MediaType.APPLICATION_X_PROTOBUF).hasToString("application/x-protobuf");
    assertThat(MediaType.IMAGE_GIF).hasToString("image/gif");
    assertThat(MediaType.IMAGE_JPEG).hasToString("image/jpeg");
    assertThat(MediaType.IMAGE_PNG).hasToString("image/png");
    assertThat(MediaType.TEXT_HTML).hasToString("text/html");
    assertThat(MediaType.TEXT_MARKDOWN).hasToString("text/markdown");
    assertThat(MediaType.TEXT_PLAIN).hasToString("text/plain");
    assertThat(MediaType.TEXT_XML).hasToString("text/xml");
  }

  @Test
  void parseSimpleMediaType() {
    assertThat(MediaType.parse("text/plain"))
        .returns("text", from(MediaType::type))
        .returns("plain", from(MediaType::subtype))
        .hasToString("text/plain")
        .extracting(MediaType::parameters, MAP)
        .isEmpty();
  }

  @Test
  void createSimpleMediaType() {
    assertThat(MediaType.of("text", "plain"))
        .returns("text", from(MediaType::type))
        .returns("plain", from(MediaType::subtype))
        .hasToString("text/plain")
        .extracting(MediaType::parameters, MAP)
        .isEmpty();
  }

  @Test
  void parseWithSpecialTokenChars() {
    var generalType = "!#$%&'*+";
    var subtype = "-.^_`|~";
    assertThat(MediaType.parse(generalType + "/" + subtype))
        .returns(generalType, from(MediaType::type))
        .returns(subtype, from(MediaType::subtype))
        .hasToString(generalType + "/" + subtype)
        .extracting(MediaType::parameters, MAP)
        .isEmpty();
  }

  @Test
  void createWithSpecialTokenChars() {
    var generalType = "!#$%&'*+";
    var subtype = "-.^_`|~";
    assertThat(MediaType.parse(generalType + "/" + subtype))
        .returns(generalType, from(MediaType::type))
        .returns(subtype, from(MediaType::subtype))
        .hasToString(generalType + "/" + subtype)
        .extracting(MediaType::parameters, MAP)
        .isEmpty();
  }

  @Test
  void parseWithParameters() {
    assertThat(MediaType.parse("text/plain; charset=utf-8; a=pikachu; b=ditto"))
        .returns("text", from(MediaType::type))
        .returns("plain", from(MediaType::subtype))
        .returns(Optional.of(UTF_8), from(MediaType::charset))
        .hasToString("text/plain; charset=utf-8; a=pikachu; b=ditto")
        .extracting(MediaType::parameters, MAP)
        .containsExactly(entry("charset", "utf-8"), entry("a", "pikachu"), entry("b", "ditto"));
  }

  @Test
  void addParameterAfterParse() {
    assertThat(MediaType.parse("text/plain; a=pikachu").withParameter("b", "ditto"))
        .hasToString("text/plain; a=pikachu; b=ditto")
        .extracting(MediaType::parameters, MAP)
        .containsExactly(entry("a", "pikachu"), entry("b", "ditto"));
  }

  @Test
  void replaceParameterAfterParse() {
    assertThat(MediaType.parse("text/plain; a=pikachu").withParameter("a", "eevee"))
        .hasToString("text/plain; a=eevee")
        .extracting(MediaType::parameters, MAP)
        .containsExactly(entry("a", "eevee"));
  }

  @Test
  void addParameterAfterCreate() {
    assertThat(MediaType.of("text", "plain", Map.of("a", "pikachu")).withParameter("b", "ditto"))
        .hasToString("text/plain; a=pikachu; b=ditto")
        .extracting(MediaType::parameters, MAP)
        .containsExactly(entry("a", "pikachu"), entry("b", "ditto"));
  }

  @Test
  void replaceParameterAfterCreate() {
    assertThat(MediaType.of("text", "plain", Map.of("a", "pikachu")).withParameter("a", "eevee"))
        .hasToString("text/plain; a=eevee")
        .extracting(MediaType::parameters, MAP)
        .containsExactly(entry("a", "eevee"));
  }

  @Test
  void addMultipleParametersAfterParse() {
    assertThat(
            MediaType.parse("text/plain; charset=utf-8")
                .withParameters(linkedHashMap("a", "pikachu", "b", "ditto")))
        .hasToString("text/plain; charset=utf-8; a=pikachu; b=ditto")
        .returns(Optional.of(UTF_8), from(MediaType::charset))
        .extracting(MediaType::parameters, MAP)
        .containsExactly(entry("charset", "utf-8"), entry("a", "pikachu"), entry("b", "ditto"));
  }

  @Test
  void addMultipleParametersAfterCreate() {
    var parameters = new LinkedHashMap<String, String>(); // Preserve order
    parameters.put("a", "pikachu");
    parameters.put("b", "ditto");
    assertThat(MediaType.of("text", "plain", Map.of("charset", "utf-8")).withParameters(parameters))
        .hasToString("text/plain; charset=utf-8; a=pikachu; b=ditto")
        .returns(Optional.of(UTF_8), from(MediaType::charset))
        .extracting(MediaType::parameters, MAP)
        .containsExactly(entry("charset", "utf-8"), entry("a", "pikachu"), entry("b", "ditto"));
  }

  @Test
  void replaceMultipleParametersAfterParse() {
    assertThat(
            MediaType.parse("text/plain; charset=utf-8; a=pikachu")
                .withParameters(linkedHashMap("charset", "utf-16", "a", "eevee", "b", "ditto")))
        .hasToString("text/plain; charset=utf-16; a=eevee; b=ditto")
        .returns(Optional.of(UTF_16), from(MediaType::charset))
        .extracting(MediaType::parameters, MAP)
        .containsExactly(entry("charset", "utf-16"), entry("a", "eevee"), entry("b", "ditto"));
  }

  @Test
  void replaceMultipleParametersAfterCreate() {
    assertThat(
            MediaType.of("text", "plain", linkedHashMap("charset", "utf-8", "a", "pikachu"))
                .withParameters(linkedHashMap("charset", "utf-16", "a", "eevee", "b", "ditto")))
        .hasToString("text/plain; charset=utf-16; a=eevee; b=ditto")
        .returns(Optional.of(UTF_16), from(MediaType::charset))
        .extracting(MediaType::parameters, MAP)
        .containsExactly(entry("charset", "utf-16"), entry("a", "eevee"), entry("b", "ditto"));
  }

  @Test
  void addCharset() {
    assertThat(MediaType.parse("text/plain").withCharset(UTF_8))
        .hasToString("text/plain; charset=utf-8")
        .returns(Optional.of(UTF_8), from(MediaType::charset))
        .extracting(MediaType::parameters, MAP)
        .containsExactly(entry("charset", "utf-8"));
  }

  @Test
  void replaceCharset() {
    assertThat(MediaType.parse("text/plain; charset=ascii").withCharset(UTF_8))
        .hasToString("text/plain; charset=utf-8")
        .returns(Optional.of(UTF_8), from(MediaType::charset))
        .extracting(MediaType::parameters, MAP)
        .containsExactly(entry("charset", "utf-8"));
  }

  @Test
  void caseInsensitiveAttributesAreLowerCasedWhenParsed() {
    assertThat(MediaType.parse("TEXT/PLAIN; a=PIKACHU; CHARSET=UTF-8"))
        .returns(Optional.of(UTF_8), from(MediaType::charset))
        .hasToString("text/plain; a=PIKACHU; charset=utf-8")
        .extracting(MediaType::parameters, MAP)
        .containsKeys("a", "charset")
        .containsExactly(
            entry("a", "PIKACHU"), // non-charset param value is untouched
            entry("charset", "utf-8"));
  }

  @Test
  void caseInsensitiveAttributesAreLowerCasedWhenCreated() {
    assertThat(MediaType.of("TEXT", "PLAIN", linkedHashMap("a", "PIKACHU", "CHARSET", "UTF-8")))
        .returns(Optional.of(UTF_8), from(MediaType::charset))
        .hasToString("text/plain; a=PIKACHU; charset=utf-8")
        .extracting(MediaType::parameters, MAP)
        .containsKeys("a", "charset")
        .containsExactly(
            entry("a", "PIKACHU"), // non-charset param value is untouched
            entry("charset", "utf-8"));
  }

  @Test
  void parseQuotedToken() {
    assertThat(MediaType.parse("text/plain; a=\"pikachu\""))
        .hasToString("text/plain; a=pikachu") // Unnecessarily quoted token in unquoted
        .extracting(MediaType::parameters, MAP)
        .containsExactly(entry("a", "pikachu"));
  }

  @Test
  void parseQuotedNonTokenValue() {
    assertThat(MediaType.parse("text/plain; a=\"b@r\""))
        .hasToString("text/plain; a=\"b@r\"") // Quotes for non-tokens are maintained
        .extracting(MediaType::parameters, MAP)
        .containsExactly(entry("a", "b@r"));
  }

  @Test
  void parseEmptyQuotedValue() {
    assertThat(MediaType.parse("text/plain; a=\"\""))
        .hasToString("text/plain; a=\"\"") // Quotes for empty string (a non-token) are maintained
        .extracting(MediaType::parameters, MAP)
        .containsExactly(entry("a", ""));
  }

  @Test
  void parseQuotedValueWithQuotedPairs() {
    var escapedValue = "\\\"\\\\\\a"; // \"\\\a unescaped to -> "\a
    var unescapedValue = "\"\\a"; // "\a
    var reespacedValue = "\\\"\\\\a"; // \"\\a (only backslash and quotes are re-quoted)
    assertThat(MediaType.parse("text/plain; a=\"" + escapedValue + "\""))
        .hasToString("text/plain; a=\"" + reespacedValue + "\"")
        .extracting(MediaType::parameters, MAP)
        .containsExactly(entry("a", unescapedValue));
  }

  @Test
  void createWithNonTokenParameterValues() {
    assertThat(
            MediaType.of(
                "text",
                "plain",
                linkedHashMap(
                    "a", "bar\\", // bar\ should be escaped to bar\\
                    "b", "\"foo\\\"", // "foo\" should be escaped to \"foo\\\"
                    "c", "" // <empty-str> should be escaped to ""
                    )))
        .hasToString("text/plain; a=\"bar\\\\\"; b=\"\\\"foo\\\\\\\"\"; c=\"\"")
        .extracting(MediaType::parameters, MAP)
        .containsExactly(entry("a", "bar\\"), entry("b", "\"foo\\\""), entry("c", ""));
  }

  @Test
  void parseWithOptionalWhiteSpace() {
    assertThat(MediaType.parse("text/plain \t ;  \t a=pikachu \t ; b=ditto"))
        .returns("text", from(MediaType::type))
        .returns("plain", from(MediaType::subtype))
        .extracting(MediaType::parameters, MAP)
        .containsExactly(entry("a", "pikachu"), entry("b", "ditto"));
  }

  @Test
  void parseWithoutOptionalWhiteSpace() {
    assertThat(MediaType.parse("text/plain;a=pikachu;b=ditto"))
        .returns("text", from(MediaType::type))
        .returns("plain", from(MediaType::subtype))
        .extracting(MediaType::parameters, MAP)
        .containsExactly(entry("a", "pikachu"), entry("b", "ditto"));
  }

  @Test
  void parseWithDanglingSemicolons() {
    assertThat(MediaType.parse("text/plain;")).hasToString("text/plain");
    assertThat(MediaType.parse("text/plain; ;")).hasToString("text/plain");
    assertThat(MediaType.parse("text/plain ; ;\t; ; \t ")).hasToString("text/plain");
    assertThat(MediaType.parse("text/plain; ;;charset=utf-8; ;a=pikachu;"))
        .hasToString("text/plain; charset=utf-8; a=pikachu");
  }

  @Test
  void charsetOrDefault() {
    assertThat(MediaType.parse("text/plain"))
        .returns(UTF_8, from(mediaType -> mediaType.charsetOrDefault(UTF_8)));
    assertThat(MediaType.parse("text/plain; charset=ascii"))
        .returns(US_ASCII, from(mediaType -> mediaType.charsetOrDefault(UTF_8)));
  }

  @Test
  void charsetOrDefaultWithUnsupportedCharset() {
    assertThat(MediaType.parse("text/plain; charset=baby-yoda"))
        .returns(US_ASCII, from(mediaType -> mediaType.charsetOrDefault(US_ASCII)));
  }

  @Test
  void hasWildcard() {
    assertThat(MediaType.parse("*/*")).returns(true, from(MediaType::hasWildcard));
    assertThat(MediaType.parse("text/*")).returns(true, from(MediaType::hasWildcard));
    assertThat(MediaType.parse("text/plain")).returns(false, from(MediaType::hasWildcard));
  }

  @Test
  void inclusion() {
    assertInclusion(MediaType.parse("*/*"), MediaType.parse("*/*"));
    assertInclusion(MediaType.parse("*/*; foo=bar"), MediaType.parse("text/*; foo=bar"));
    assertInclusion(MediaType.parse("*/*"), MediaType.parse("text/plain"));
    assertInclusion(MediaType.parse("text/*"), MediaType.parse("text/*; foo=bar"));
    assertInclusion(MediaType.parse("text/*"), MediaType.parse("text/plain"));
    assertInclusion(
        MediaType.parse("text/*; foo=bar; foo=bar_again"),
        MediaType.parse("text/plain; foo=bar; foo=bar_again; foo_again=bar_again_again"));
    assertInclusion(MediaType.parse("text/plain"), MediaType.parse("text/plain; foo=bar"));

    assertThat(MediaType.parse("*/*; foo=bar").includes(MediaType.parse("*/*"))).isFalse();
    assertThat(MediaType.parse("text/*").includes(MediaType.parse("audio/*"))).isFalse();
    assertThat(MediaType.parse("text/plain; foo=bar").includes(MediaType.parse("text/plain")))
        .isFalse();
    assertThat(MediaType.parse("text/plain").includes(MediaType.parse("text/*"))).isFalse();
  }

  @Test
  void equalsAndHashcode() {
    assertThat(MediaType.parse("text/plain; charset=utf-8; a=pikachu"))
        .isEqualTo(MediaType.parse("text/plain; charset=utf-8; a=pikachu"));
    assertThat(MediaType.parse("text/plain; charset=utf-8; a=pikachu"))
        .isEqualTo(
            MediaType.of(
                "text",
                "plain",
                Map.of(
                    "a", "pikachu",
                    "charset", "utf-8")))
        .hasSameHashCodeAs(
            MediaType.of(
                "text",
                "plain",
                Map.of(
                    "a", "pikachu",
                    "charset", "utf-8")));
    assertThat(MediaType.parse("text/plain").equals("text/plain")).isFalse();
  }

  // Exceptional behaviour

  @Test
  void parseInvalidTypeOrSubtype() {
    assertThatIllegalArgumentException().isThrownBy(() -> MediaType.parse("text/pl@in"));
    assertThatIllegalArgumentException().isThrownBy(() -> MediaType.parse("t@xt/plain"));
  }

  @Test
  void parseInvalidParameters() {
    assertThatIllegalArgumentException().isThrownBy(() -> MediaType.parse("text/plain; b@r=foo"));
    assertThatIllegalArgumentException().isThrownBy(() -> MediaType.parse("text/plain; foo=b@r"));
    assertThatIllegalArgumentException().isThrownBy(() -> MediaType.parse("text/plain; foo=ba\r"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MediaType.parse("text/plain; foo=\"ba\r\""));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MediaType.parse("text/plain; foo=\"ba\\\r\""));
  }

  @Test
  void parseInvalidInput() {
    assertThatIllegalArgumentException().isThrownBy(() -> MediaType.parse("text"));
    assertThatIllegalArgumentException().isThrownBy(() -> MediaType.parse("text/"));
    assertThatIllegalArgumentException().isThrownBy(() -> MediaType.parse("/plain"));
    assertThatIllegalArgumentException().isThrownBy(() -> MediaType.parse("text/plain "));
    assertThatIllegalArgumentException().isThrownBy(() -> MediaType.parse(" text/plain"));
    assertThatIllegalArgumentException().isThrownBy(() -> MediaType.parse("text /plain"));
    assertThatIllegalArgumentException().isThrownBy(() -> MediaType.parse("text/ plain"));
    assertThatIllegalArgumentException().isThrownBy(() -> MediaType.parse("text/plain; foo"));
    assertThatIllegalArgumentException().isThrownBy(() -> MediaType.parse("text/plain; for="));
    assertThatIllegalArgumentException().isThrownBy(() -> MediaType.parse("text/plain; =bar"));
    assertThatIllegalArgumentException().isThrownBy(() -> MediaType.parse("text/plain; foo=\"bar"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MediaType.parse("text/plain; foo=\"bar\\\""));
  }

  @Test
  void parseWildcardTypeWithConcreteSubtype() {
    assertThatIllegalArgumentException().isThrownBy(() -> MediaType.parse("*/plain; "));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MediaType.parse("*/plain; charset=utf-8"));
  }

  @Test
  void createWithInvalidType() {
    assertThatIllegalArgumentException().isThrownBy(() -> MediaType.of("text", "pl@in"));
    assertThatIllegalArgumentException().isThrownBy(() -> MediaType.of("t@xt", "plain"));
    assertThatIllegalArgumentException().isThrownBy(() -> MediaType.of("", "plain"));
    assertThatIllegalArgumentException().isThrownBy(() -> MediaType.of("text", ""));
  }

  @Test
  void createWithInvalidParameters() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> MediaType.of("text", "plain", Map.of("b@r", "foo"))); // Invalid param name
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MediaType.of("text", "plain", Map.of("", "ops"))); // Empty param name
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> MediaType.of("text", "plain", Map.of("foo", "ba\r"))); // Invalid param value
  }

  @Test
  void createWithWildcardTypeWithConcreteSubtype() {
    assertThatIllegalArgumentException().isThrownBy(() -> MediaType.of("*", "plain"));
    assertThatIllegalArgumentException().isThrownBy(() -> MediaType.of("*", "plain", Map.of()));
  }

  private static void assertInclusion(MediaType range, MediaType type) {
    assertThat(range.includes(type))
        .withFailMessage(() -> format("<%s> & <%s>", range, type))
        .isTrue();
    assertThat(range.isCompatibleWith(type) && type.isCompatibleWith(range))
        .withFailMessage(() -> format("<%s> & <%s>", range, type))
        .isTrue();
  }

  private static Map<String, String> linkedHashMap(String... keyVals) {
    var m = new LinkedHashMap<String, String>();
    for (int i = 0; i < keyVals.length; i += 2) {
      m.put(keyVals[i], keyVals[i + 1]);
    }
    return m;
  }
}
