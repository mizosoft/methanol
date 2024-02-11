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

import static com.github.mizosoft.methanol.adapter.jaxb.jakarta.JaxbAdapterFactory.createEncoder;
import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static java.nio.charset.StandardCharsets.UTF_16;
import static org.junit.jupiter.api.Assertions.fail;

import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import org.junit.jupiter.api.Test;

class JaxbEncoderTest {
  @Test
  void compatibleMediaTypes() {
    verifyThat(createEncoder())
        .isCompatibleWith("application/xml")
        .isCompatibleWith("text/xml")
        .isCompatibleWith("application/*")
        .isCompatibleWith("text/*")
        .isCompatibleWith("*/*");
  }

  @Test
  void incompatibleMediaTypes() {
    verifyThat(createEncoder())
        .isNotCompatibleWith("application/json")
        .isNotCompatibleWith("text/html")
        .isNotCompatibleWith("image/*");
  }

  @Test
  void serialize() {
    verifyThat(createEncoder())
        .converting(new Point(1, 2))
        .succeedsWith(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><point x=\"1\" y=\"2\"/>");
  }

  @Test
  void serializeWithUtf16() {
    verifyThat(createEncoder())
        .converting(new Point(1, 2))
        .withMediaType("application/xml; charset=utf-16")
        .succeedsWith(
            "<?xml version=\"1.0\" encoding=\"utf-16\" standalone=\"yes\"?><point x=\"1\" y=\"2\"/>",
            UTF_16);
  }

  @Test
  void serializeList() {
    verifyThat(createEncoder())
        .converting(new PointList(new Point(1, 2), new Point(3, 4)))
        .succeedsWith(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<points>"
                + "<point x=\"1\" y=\"2\"/>"
                + "<point x=\"3\" y=\"4\"/>"
                + "</points>");
  }

  @Test
  void serializeWithFormattedXml() {
    var customFactory =
        new JaxbBindingFactory() {
          private final JaxbBindingFactory delegate = JaxbBindingFactory.create();

          /** Create a Marshaller with pretty printing. */
          @Override
          public Marshaller createMarshaller(Class<?> boundClass) throws JAXBException {
            var marshaller = delegate.createMarshaller(boundClass);
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            return marshaller;
          }

          @Override
          public Unmarshaller createUnmarshaller(Class<?> boundClass) {
            return fail("unexpected unmarshalling");
          }
        };

    verifyThat(createEncoder(customFactory))
        .converting(new PointList(new Point(1, 2), new Point(3, 4)))
        .succeedsWithNormalizingLineEndings(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<points>\n"
                + "    <point x=\"1\" y=\"2\"/>\n"
                + "    <point x=\"3\" y=\"4\"/>\n"
                + "</points>\n");
  }

  @Test
  void serializeWithUnsupportedType() {
    class NotAXmlRootElement {}

    verifyThat(createEncoder()).converting(new NotAXmlRootElement()).isNotSupported();
  }

  @Test
  void serializeWithUnsupportedMediaType() {
    verifyThat(createEncoder())
        .converting(new Point(1, 2))
        .withMediaType("application/json")
        .isNotSupported();
  }

  @Test
  void mediaTypeIsAttached() {
    verifyThat(createEncoder())
        .converting(new Point(1, 2))
        .withMediaType("application/xml")
        .asBodyPublisher()
        .hasMediaType("application/xml");

    verifyThat(createEncoder())
        .converting(new Point(1, 2))
        .withMediaType("text/xml")
        .asBodyPublisher()
        .hasMediaType("text/xml");
  }

  @Test
  void mediaTypeWithCharsetIsAttached() {
    verifyThat(createEncoder())
        .converting(new Point(1, 2))
        .withMediaType("application/xml; charset=utf-16")
        .asBodyPublisher()
        .hasMediaType("application/xml; charset=utf-16");

    verifyThat(createEncoder())
        .converting(new Point(1, 2))
        .withMediaType("text/xml; charset=utf-16")
        .asBodyPublisher()
        .hasMediaType("text/xml; charset=utf-16");
  }
}
