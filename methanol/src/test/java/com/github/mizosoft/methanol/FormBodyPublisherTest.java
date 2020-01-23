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

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.mizosoft.methanol.testing.BodyCollector;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FormBodyPublisherTest {

  @Test
  void buildMultiQueries() {
    var queries = new LinkedHashMap<String, List<String>>(); // preserve order
    queries.put("purpose", List.of("idk"));
    queries.put("help", List.of("now", "pls"));
    var builder = FormBodyPublisher.newBuilder();
    queries.forEach((n, vs) -> vs.forEach(v -> builder.query(n, v)));
    var formBody = builder.build();
    assertEquals(queries, formBody.queries());
    assertEquals(Optional.of("idk"), formBody.firstQuery("purpose"));
    assertEquals(Optional.of("idk"), formBody.lastQuery("purpose"));
    assertEquals(Optional.of("now"), formBody.firstQuery("help"));
    assertEquals(Optional.of("pls"), formBody.lastQuery("help"));
    assertEquals("application/x-www-form-urlencoded", formBody.mediaType().toString());
    assertEquals("purpose=idk&help=now&help=pls", formBody.encodedString());
  }

  @Test
  void encodedString_safe() {
    var formBody = FormBodyPublisher.newBuilder()
        .query("stranger", "danger")
        .query("leader", "believer")
        .query("safe", "*.-_")
        .build();
    assertSameContent("stranger=danger&leader=believer&safe=*.-_", formBody);
  }

  @Test
  void encodedString_unsafe() {
    var formBody = FormBodyPublisher.newBuilder()
        .query("some_unsafe", "&(@___@)&")
        .query("more uns@fe", "¥£$")
        .build();
    assertSameContent("some_unsafe=%26%28%40___%40%29%26&more+uns%40fe=%C2%A5%C2%A3%24", formBody);
  }

  @Test
  void serializeBody() {
    var formBody = FormBodyPublisher.newBuilder()
        .query("eyy+oh&ooh=help", "¥£$ but actually ηΦ")
        .query("fresh", "avocado")
        .query("sexy veg@n", "¤")
        .build();
    assertSameContent(
        "eyy%2Boh%26ooh%3Dhelp=%C2%A5%C2%A3%24+but+actually+%CE%B7%CE%A6&fresh=avocado&sexy+veg%40n=%C2%A4",
        formBody);
  }

  private void assertSameContent(String expected, FormBodyPublisher body) {
    var expectedContent = US_ASCII.encode(expected);
    var bodyContent = BodyCollector.collect(body);
    assertEquals(expectedContent.remaining(), body.contentLength());
    assertEquals(expectedContent, bodyContent,
        format("expected <%s> found <%s>", expected, US_ASCII.decode(bodyContent.duplicate())));
  }
}
