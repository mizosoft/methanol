/*
 * Copyright (c) 2024 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.adapter.jaxb;

import static com.github.mizosoft.methanol.adapter.jaxb.JaxbAdapterFactory.createDecoder;
import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static java.nio.charset.StandardCharsets.UTF_16;

import com.github.mizosoft.methanol.testing.TestException;
import org.junit.jupiter.api.Test;

class JaxbDeferredDecoderTest {
  @Test
  void deserialize() {
    verifyThat(createDecoder())
        .converting(Point.class)
        .withDeferredBody("<?xml version=\"1.0\" encoding=\"UTF-8\"?><point x=\"1\" y=\"2\"/>")
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void deserializeWithUtf16_inferredFromMediaType() {
    verifyThat(createDecoder())
        .converting(Point.class)
        .withMediaType("application/xml; charset=utf-16")
        .withDeferredBody("<?xml version=\"1.0\"?><point x=\"1\" y=\"2\"/>", UTF_16)
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void deserializeWithUtf16_inferredFromXmlDocument() {
    verifyThat(createDecoder())
        .converting(Point.class)
        .withDeferredBody(
            "<?xml version=\"1.0\" encoding=\"UTF-16\"?><point x=\"1\" y=\"2\"/>", UTF_16)
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void deserializeList() {
    verifyThat(createDecoder())
        .converting(PointList.class)
        .withDeferredBody(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
          + "<points>"
          + "<point x=\"1\" y=\"2\"/>"
          + "<point x=\"3\" y=\"4\"/>"
          + "</points>")
        .succeedsWith(new PointList(new Point(1, 2), new Point(3, 4)));
  }

  @Test
  void deserializeBadXml() {
    verifyThat(createDecoder())
        .converting(Point.class)
        .withDeferredBody("<?xml version=\"1.0\"?><point x=\"1\" y=\"2\">") // No enclosing forward slash
        .failsWith(UncheckedJaxbException.class);
  }

  @Test
  void deserializeWithError() {
    verifyThat(createDecoder())
        .converting(Point.class)
        .withDeferredFailure(new TestException())
        .failsWith(UncheckedJaxbException.class);
  }
}
