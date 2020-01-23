/*
 * MIT License
 *
 * Copyright (c) 2019 Moataz Abdelnasser
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

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class MediaTypeTest {

  @Test
  void parse_simpleType() {
    var type = MediaType.parse("text/plain");
    assertEquals("text", type.type());
    assertEquals("plain", type.subtype());
  }

  @Test
  void of_simpleType() {
    var type = MediaType.of("text", "plain");
    assertEquals("text", type.type());
    assertEquals("plain", type.subtype());
  }

  @Test
  void parse_typeWithSpecialTokenChars() {
    var generalType = "!#$%&'*+";
    var subtype = "-.^_`|~";
    var type = MediaType.parse(generalType + "/" + subtype);
    assertEquals(generalType, type.type());
    assertEquals(subtype, type.subtype());
  }

  @Test
  void of_typeWithSpecialTokenChars() {
    var generalType = "!#$%'*";
    var subtype = "+-.^_`|~";
    var type = MediaType.of(generalType, subtype);
    assertEquals(generalType, type.type());
    assertEquals(subtype, type.subtype());
  }

  @Test
  void parse_typeWithParameters() {
    var type = MediaType.parse(
        "text/plain; charset=utf-8; foo=bar; foo_again=bar_again");
    assertHasCharset(type, UTF_8);
    assertEquals("bar", type.parameters().get("foo"));
    assertEquals("bar_again", type.parameters().get("foo_again"));
  }

  @Test
  void withParameter_newParameter() {
    var type = MediaType.parse("text/plain; foo=bar")
        .withParameter("foo_again", "bar_again");
    assertEquals("bar", type.parameters().get("foo")); // old param not removed
    assertEquals("bar_again", type.parameters().get("foo_again"));
  }

  @Test
  void withParameter_clashingParameter() {
    var type = MediaType.parse("text/plain; foo=bar")
        .withParameter("foo", "bar_replaced");
    assertEquals("bar_replaced", type.parameters().get("foo"));
  }

  @Test
  void withParameters_newParameters() {
    var parameters = Map.of(
        "foo", "bar",
        "foo_again", "bar_again");
    var type = MediaType.parse("text/plain; charset=utf-8")
        .withParameters(parameters);
    assertHasCharset(type, UTF_8); // Charset not removed
    assertEquals("bar", type.parameters().get("foo"));
    assertEquals("bar_again", type.parameters().get("foo_again"));
  }

  @Test
  void withParameters_clashingParameters() {
    var parameters = Map.of(
        "foo", "bar_replaced",
        "foo_again", "bar_again_replaced");
    var type = MediaType.parse("text/plain; foo=bar; foo_again=bar_again")
        .withParameters(parameters);
    assertEquals("bar_replaced", type.parameters().get("foo"));
    assertEquals("bar_again_replaced", type.parameters().get("foo_again"));
  }

  @Test
  void withCharset_newCharset() {
    var type = MediaType.parse("text/plain")
        .withCharset(UTF_8);
    assertHasCharset(type, UTF_8);
  }

  @Test
  void withCharset_clashingCharset() {
    var type = MediaType.parse("text/plain; charset=utf-8")
        .withCharset(US_ASCII);
    assertHasCharset(type, US_ASCII);
  }

  @Test
  void parse_caseInsensitiveAttributesAreLowerCased() {
    var type = MediaType.parse("TEXT/PLAIN; FOO=bar; CHARSET=UTF-8");
    assertEquals("text", type.type());
    assertEquals("plain", type.subtype());
    assertEquals(Set.of("foo", "charset"), type.parameters().keySet());
    assertEquals("utf-8", type.parameters().get("charset"));
  }

  @Test
  void of_caseInsensitiveAttributesAreLowerCased() {
    var parameters = Map.of(
        "FOO", "bar",
        "CHARSET", "UTF-8");
    var type = MediaType.of("TEXT", "PLAIN", parameters);
    assertEquals("text", type.type());
    assertEquals("plain", type.subtype());
    assertEquals(Set.of("foo", "charset"), type.parameters().keySet());
    assertEquals("utf-8", type.parameters().get("charset"));
  }

  @Test
  void parse_quotedToken() {
    var type = MediaType.parse("text/plain; foo=\"bar\"");
    assertEquals("bar", type.parameters().get("foo"));
    assertEquals("text/plain; foo=bar", type.toString()); // token not requoted
  }

  @Test
  void parse_quotedValue() {
    var type = MediaType.parse("text/plain; foo=\"b@r\"");
    assertEquals("b@r", type.parameters().get("foo"));
    assertEquals("text/plain; foo=\"b@r\"", type.toString()); // requotes non-token
  }

  @Test
  void parse_emptyQuotedValue() {
    var type = MediaType.parse("text/plain; foo=\"\"");
    assertEquals("", type.parameters().get("foo"));
    assertEquals("text/plain; foo=\"\"", type.toString()); // requotes empty value
  }

  @Test
  void parse_quotedValueWithQuotedPairs() {
    var escapedValue = "\\\"\\\\\\a"; // \"\\\a unescaped to -> "\a
    var unescapedValue = "\"\\a"; // "\a
    var reescaped = "\\\"\\\\a"; // \"\\a (only backslash and quotes are requoted)
    var type = MediaType.parse("text/plain; foo=\"" + escapedValue + "\"");
    assertEquals(unescapedValue, type.parameters().get("foo"));
    assertEquals("text/plain; foo=\"" + reescaped + "\"", type.toString());
  }

  @Test
  void of_nonTokenValues() {
    var parameters = new LinkedHashMap<String, String>(); // order-based
    parameters.put("foo", "bar\\");     // bar\   escaped to bar\\
    parameters.put("bar", "\"foo\\\""); // "foo\" escaped to \"foo\\\"
    parameters.put("foobar", "");
    var type = MediaType.of("text", "plain", parameters);
    assertEquals("bar\\", type.parameters().get("foo"));
    assertEquals("\"foo\\\"", type.parameters().get("bar"));
    assertEquals("", type.parameters().get("foobar"));
    // values are escaped and quoted
    assertEquals("text/plain; foo=\"bar\\\\\"; bar=\"\\\"foo\\\\\\\"\"; foobar=\"\"",
        type.toString());
  }

  @Test
  void parse_withOptionalWhiteSpace() {
    var type = MediaType.parse("text/plain \t ;  \t foo=bar \t ; foo_again=bar_again");
    assertEquals("text", type.type());
    assertEquals("plain", type.subtype());
    assertEquals("bar", type.parameters().get("foo"));
    assertEquals("bar_again", type.parameters().get("foo_again"));
  }

  @Test
  void parse_withoutOptionalWhiteSpace() {
    var type = MediaType.parse("text/plain;foo=bar;foo_again=bar_again");
    assertEquals("text", type.type());
    assertEquals("plain", type.subtype());
    assertEquals("bar", type.parameters().get("foo"));
    assertEquals("bar_again", type.parameters().get("foo_again"));
  }

  @Test
  void parse_danglingSemicolons() {
    Function<String, String> normalize = s -> MediaType.parse(s).toString();
    assertEquals("text/plain", normalize.apply("text/plain;"));
    assertEquals("text/plain", normalize.apply("text/plain; "));
    assertEquals("text/plain", normalize.apply("text/plain ;"));
    assertEquals("text/plain", normalize.apply("text/plain ; "));
    assertEquals("text/plain", normalize.apply("text/plain ; ;; ; ;"));
    assertEquals("text/plain; charset=utf-8",
        normalize.apply("text/plain ; ;; charset=utf-8;"));
    assertEquals("text/plain; charset=utf-8; foo=bar",
        normalize.apply("text/plain ;; ; charset=utf-8;; ;foo=bar;; ;"));
  }

  @Test
  void charsetOrDefault() {
    var textPlain = MediaType.of("text", "plain");
    var textPlainAscii = textPlain.withCharset(US_ASCII);
    assertEquals(textPlain.charsetOrDefault(UTF_8), UTF_8);
    assertEquals(textPlainAscii.charsetOrDefault(UTF_8), US_ASCII);
  }

  @Test
  void charsetOrDefault_unsupportedCharset() {
    var type = MediaType.of("text", "plain")
        .withParameter("charset", "baby-yoda");
    assertEquals(UTF_8, type.charsetOrDefault(UTF_8));
  }

  @Test
  void equals_hashCode() {
    var type1 = MediaType.parse("text/plain; charset=utf-8; foo=bar");
    var type2 = MediaType.of("text", "plain", Map.of(
        "foo", "bar",
        "charset", "utf-8"));
    var type3 = MediaType.of("text", "plain")
        .withParameter("foo", "bar")
        .withParameter("charset", "ascii");
    assertEquals(type1, type1);
    assertEquals(type1, type2);
    assertEquals(type1.hashCode(), type2.hashCode());
    assertNotEquals(type1, "I am an instance of class String");
    assertNotEquals(type1, type3);
  }

  // exceptional behaviour

  @Test
  void parse_invalidType() {
    assertInvalidParse("text/pl@in");
    assertInvalidParse("t@xt/plain");
  }

  @Test
  void parse_invalidParameters() {
    assertInvalidParse("text/plain; b@r=foo");
    assertInvalidParse("text/plain; foo=b@r");
    assertInvalidParse("text/plain; foo=ba\r");
    assertInvalidParse("text/plain; foo=\"ba\r\"");
    assertInvalidParse("text/plain; foo=\"ba\\\r\""); // even if escaped
  }

  @Test
  void parse_invalidInput() {
    assertInvalidParse("text");
    assertInvalidParse("text/");
    assertInvalidParse("/plain");
    assertInvalidParse("text/plain ");
    assertInvalidParse(" text/plain");
    assertInvalidParse("text /plain");
    assertInvalidParse("text/ plain");
    assertInvalidParse("text/plain; foo");
    assertInvalidParse("text/plain; for=");
    assertInvalidParse("text/plain; =bar");
    assertInvalidParse("text/plain; foo=\"bar");
    assertInvalidParse("text/plain; foo=\"bar\\\"");
  }

  @Test
  void of_invalidType() {
    assertIllegalArg(() -> MediaType.of("text", "pl@in"));
    assertIllegalArg(() -> MediaType.of("t@xt", "plain"));
    assertIllegalArg(() -> MediaType.of("", "plain"));
    assertIllegalArg(() -> MediaType.of("text", ""));
  }

  @Test
  void of_invalidParameters() {
    var invalidNameParams = Map.of("b@r", "foo");
    var emptyNameParams = Map.of("", "ops");
    var invalidValueParams = Map.of("foo", "ba\r");
    assertIllegalArg(() -> MediaType.of("text", "plain", invalidNameParams));
    assertIllegalArg(() -> MediaType.of("text", "plain", emptyNameParams));
    assertIllegalArg(() -> MediaType.of("text", "plain", invalidValueParams));
  }

  private static void assertHasCharset(MediaType type, Charset charset) {
    assertEquals(Optional.of(charset), type.charset());
    assertEquals(charset.name().toLowerCase(),
        type.parameters().get("charset"));
  }

  private static void assertInvalidParse(String value) {
    assertIllegalArg(() -> MediaType.parse(value));
  }

  private static void assertIllegalArg(Executable action) {
    assertThrows(IllegalArgumentException.class, action);
  }
}
