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

package com.github.mizosoft.methanol.adapter.jackson;

import static com.github.mizosoft.methanol.adapter.jackson.JacksonAdapterFactory.createEncoder;
import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static java.nio.charset.StandardCharsets.UTF_16;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.github.mizosoft.methanol.MediaType;
import org.junit.jupiter.api.Test;

class XmlTest {
  @Test
  void encode() {
    verifyThat(createEncoder(new XmlMapper(), MediaType.TEXT_XML))
        .converting(new Point(1, 2))
        .succeedsWith("<Point><x>1</x><y>2</y></Point>");
  }

  @Test
  void encodeUtf16() {
    verifyThat(createEncoder(new XmlMapper(), MediaType.TEXT_XML))
        .converting(new Point(1, 2))
        .withMediaType("text/xml; charset=utf-16")
        .succeedsWith("<Point><x>1</x><y>2</y></Point>", UTF_16);
  }

  @Test
  void decode() {
    verifyThat(JacksonAdapterFactory.createDecoder(new XmlMapper(), MediaType.TEXT_XML))
        .converting(Point.class)
        .withBody("<point><x>1</x><y>2</y></point>")
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void decodeUtf16() {
    verifyThat(JacksonAdapterFactory.createDecoder(new XmlMapper(), MediaType.TEXT_XML))
        .converting(Point.class)
        .withMediaType("text/xml; charset=utf-16")
        .withBody("<point><x>1</x><y>2</y></point>", UTF_16)
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void deferredDecode() {
    verifyThat(JacksonAdapterFactory.createDecoder(new XmlMapper(), MediaType.TEXT_XML))
        .converting(Point.class)
        .withDeferredBody("<point><x>1</x><y>2</y></point>")
        .succeedsWith(new Point(1, 2));
  }
}
