/*
 * Copyright (c) 2019, 2020 Moataz Abdelnasser
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

import static com.github.mizosoft.methanol.MoreBodyPublishers.ofObject;
import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.mizosoft.methanol.testutils.BodyCollector;
import java.nio.CharBuffer;
import org.junit.jupiter.api.Test;

class MoreBodyPublishersTest {

  @Test
  void ofMediaType() {
    var content = "some content";
    var plainText = MediaType.of("text", "plain");
    MimeBodyPublisher publisher =
        MoreBodyPublishers.ofMediaType(ofString(content), plainText);
    assertEquals(plainText, publisher.mediaType());
    assertEquals(content, US_ASCII.decode(BodyCollector.collect(publisher)).toString());
  }

  @Test
  void ofObject_charBufferBody() {
    var content = CharBuffer.wrap("<p>very important text</p>");
    var body = ofObject(content, MediaType.parse("text/html"));
    assertEquals(content, US_ASCII.decode(BodyCollector.collect(body)));
  }

  @Test
  void ofObject_unsupported() {
    assertThrows(UnsupportedOperationException.class, () -> ofObject(5555, null));
    assertThrows(UnsupportedOperationException.class,
        () -> ofObject("blah", MediaType.parse("application/json")));
  }
}
