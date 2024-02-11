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

package com.github.mizosoft.methanol.adapter.jaxb.jakarta;

import static com.github.mizosoft.methanol.adapter.jaxb.jakarta.JaxbAdapterFactory.createDecoder;
import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static java.nio.charset.StandardCharsets.UTF_16;

import com.github.mizosoft.methanol.testing.TestException;
import org.junit.jupiter.api.Test;

class JaxbDecoderTest {
  @Test
  void compatibleMediaTypes() {
    verifyThat(createDecoder())
        .isCompatibleWith("application/xml")
        .isCompatibleWith("text/xml")
        .isCompatibleWith("application/*")
        .isCompatibleWith("text/*")
        .isCompatibleWith("*/*");
  }

  @Test
  void incompatibleMediaTypes() {
    verifyThat(createDecoder())
        .isNotCompatibleWith("text/html")
        .isNotCompatibleWith("application/json")
        .isNotCompatibleWith("image/*");
  }

  @Test
  void deserialize() {
    verifyThat(createDecoder())
        .converting(Point.class)
        .withBody("<?xml version=\"1.0\" encoding=\"UTF-8\"?><point x=\"1\" y=\"2\"/>")
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void deserializeWithUtf16_inferredFromMediaType() {
    verifyThat(createDecoder())
        .converting(Point.class)
        .withMediaType("application/xml; charset=utf-16")
        .withBody("<?xml version=\"1.0\"?><point x=\"1\" y=\"2\"/>", UTF_16)
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void deserializeWithUtf16_inferredFromXmlDocument() {
    verifyThat(createDecoder())
        .converting(Point.class)
        .withBody("<?xml version=\"1.0\" encoding=\"UTF-16\"?><point x=\"1\" y=\"2\"/>", UTF_16)
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void deserializeList() {
    verifyThat(createDecoder())
        .converting(PointList.class)
        .withBody(
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
        .withBody("<?xml version=\"1.0\"?><point x=\"1\" y=\"2\">") // Missing forward slash
        .failsWith(
            UncheckedJaxbException.class); // JAXBExceptions are rethrown as UncheckedJaxbExceptions
  }

  @Test
  void deserializeWithError() {
    verifyThat(createDecoder())
        .converting(Point.class)
        .withFailure(new TestException())
        .failsWith(TestException.class);
  }

  @Test
  void deserializeWithUnsupportedType() {
    class NotAXmlRootElement {}

    verifyThat(createDecoder()).converting(NotAXmlRootElement.class).isNotSupported();
  }

  @Test
  void deserializeWithUnsupportedMediaType() {
    verifyThat(createDecoder())
        .converting(Point.class)
        .withMediaType("application/json")
        .isNotSupported();
  }
}
