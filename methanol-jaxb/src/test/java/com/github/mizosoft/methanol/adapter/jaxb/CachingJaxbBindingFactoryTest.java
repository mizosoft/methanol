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

package com.github.mizosoft.methanol.adapter.jaxb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import javax.xml.bind.JAXBException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CachingJaxbBindingFactoryTest {

  private static final String epicArtCourseXmlUtf8 =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
          + "<course type=\"ART\">"
          + "<enrolled-students>"
          + "<student>"
          + "<name>Leonardo Da Vinci</name>"
          + "</student>"
          + "<student>"
          + "<name>Michelangelo</name>"
          + "</student>"
          + "</enrolled-students>"
          + "</course>";

  private static final Course epicArtCourse =
      new Course(Type.ART, List.of(new Student("Leonardo Da Vinci"), new Student("Michelangelo")));

  @BeforeAll
  static void registerJaxbImplementation() {
    JaxbUtils.registerImplementation();
  }

  @Test
  void cachesContextsForSameType() {
    var factory = new CachingJaxbBindingFactory();
    assertSame(factory.getOrCreateContext(Course.class), factory.getOrCreateContext(Course.class));
  }

  @Test
  void marshal() throws Exception {
    var factory = new CachingJaxbBindingFactory();
    var outputBuffer = new StringWriter();
    factory.createMarshaller(Course.class).marshal(epicArtCourse, outputBuffer);
    assertEquals(epicArtCourseXmlUtf8, outputBuffer.toString());
  }

  @Test
  void unmarshal() throws JAXBException {
    var factory = new CachingJaxbBindingFactory();
    var course = (Course) factory.createUnmarshaller(Course.class)
        .unmarshal(new StringReader(epicArtCourseXmlUtf8));
    assertEquals(epicArtCourse, course);
  }
}