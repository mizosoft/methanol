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

import static com.github.mizosoft.methanol.adapter.jaxb.JaxbAdapterFactory.createDecoder;
import static com.github.mizosoft.methanol.adapter.jaxb.JaxbAdapterFactory.createEncoder;
import static com.github.mizosoft.methanol.testutils.BodyCollector.collectString;
import static com.github.mizosoft.methanol.testutils.BodyCollector.collectUtf8;
import static com.github.mizosoft.methanol.testutils.TestUtils.NOOP_SUBSCRIPTION;
import static com.github.mizosoft.methanol.testutils.TestUtils.lines;
import static java.nio.charset.StandardCharsets.UTF_16;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.TypeRef;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.CompletionException;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class JaxbAdapterTest {

  static final String epicArtCourseXmlUtf8 =
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

  static final String epicARtCourseXmlUtf16 =
      "<?xml version=\"1.0\" encoding=\"UTF-16\"?>"
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

  static final String epicArtCourseFormattedXmlUtf8 =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          + "<course type=\"ART\">\n"
          + "   <enrolled-students>\n"
          + "      <student>\n"
          + "         <name>Leonardo Da Vinci</name>\n"
          + "      </student>\n"
          + "      <student>\n"
          + "         <name>Michelangelo</name>\n"
          + "      </student>\n"
          + "   </enrolled-students>\n"
          + "</course>";

  static final Course epicArtCourse =
      new Course(Type.ART, List.of(new Student("Leonardo Da Vinci"), new Student("Michelangelo")));

  @BeforeAll
  static void registerJaxbImplementation() {
    JaxbUtils.registerImplementation();
  }

  @Test
  void isCompatibleWith_anyXml() {
    for (var c : List.of(createEncoder(), createDecoder())) {
      assertTrue(c.isCompatibleWith(MediaType.APPLICATION_XML));
      assertTrue(c.isCompatibleWith(MediaType.TEXT_XML));
      assertTrue(c.isCompatibleWith(MediaType.APPLICATION_ANY));
      assertTrue(c.isCompatibleWith(MediaType.ANY));
      assertFalse(c.isCompatibleWith(MediaType.IMAGE_ANY));
    }
  }

  @Test
  void unsupportedConversion_encoder() {
    var encoder = createEncoder();
    assertThrows(UnsupportedOperationException.class,
        () -> encoder.toBody(epicArtCourse, MediaType.TEXT_PLAIN));
  }

  @Test
  void unsupportedConversion_decoder() {
    var decoder = createDecoder();
    assertThrows(UnsupportedOperationException.class,
        () -> decoder.toObject(new TypeRef<Course>() {}, MediaType.TEXT_PLAIN));
    assertThrows(UnsupportedOperationException.class,
        () -> decoder.toDeferredObject(new TypeRef<Course>() {}, MediaType.TEXT_PLAIN));

    class GenericType<T> {}
    assertThrows(UnsupportedOperationException.class,
        () -> decoder.toObject(new TypeRef<GenericType<String>>() {}, MediaType.APPLICATION_XML));

    class NotAXmlElement {}
    assertThrows(UnsupportedOperationException.class,
        () -> decoder.toObject(new TypeRef<NotAXmlElement>() {}, null));
  }

  @Test
  void serializeXml() {
    var body = createEncoder().toBody(epicArtCourse, null);
    assertEquals(epicArtCourseXmlUtf8, collectUtf8(body));
  }

  @Test
  void serializeXml_formatted() {
    var customFactory = new JaxbBindingFactory() {
      private final JaxbBindingFactory delegate = JaxbBindingFactory.create();

      @Override public Marshaller createMarshaller(Class<?> boundClass) throws JAXBException {
        var marshaller = delegate.createMarshaller(boundClass);
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        return marshaller;
      }

      @Override public Unmarshaller createUnmarshaller(Class<?> boundClass) throws JAXBException {
        return delegate.createUnmarshaller(boundClass);
      }
    };
    var body = createEncoder(customFactory).toBody(epicArtCourse, null);
    assertLinesMatch(lines(epicArtCourseFormattedXmlUtf8), lines(collectUtf8(body)));
  }

  @Test
  void serializeXml_utf16() {
    var body = createEncoder().toBody(epicArtCourse, MediaType.TEXT_XML.withCharset(UTF_16));
    assertEquals(
        epicARtCourseXmlUtf16,
        collectString(body, UTF_16).replace("utf-16", "UTF-16")); // document encoding is lower-cased on output
  }

  @Test
  void deserializeXml() {
    var subscriber = createDecoder().toObject(new TypeRef<Course>() {}, null);
    publishUtf8(subscriber, epicArtCourseXmlUtf8);
    assertEquals(epicArtCourse, getBody(subscriber));
  }

  @Test
  void deserializeXml_utf16_inferredFromDocument() {
    var subscriber = createDecoder().toObject(new TypeRef<Course>() {}, null);
    publish(subscriber, epicARtCourseXmlUtf16, UTF_16);
    assertEquals(epicArtCourse, getBody(subscriber));
  }

  @Test
  void deserializeXml_utf16_inferredFromMediaType() {
    var subscriber = createDecoder()
        .toObject(new TypeRef<Course>() {}, MediaType.APPLICATION_XML.withCharset(UTF_16));
    publish(subscriber, epicARtCourseXmlUtf16, UTF_16);
    assertEquals(epicArtCourse, getBody(subscriber));
  }

  @Test
  void deserializeXml_deferred() {
    var subscriber = createDecoder().toDeferredObject(new TypeRef<Course>() {}, null);
    var supplier = subscriber.getBody().toCompletableFuture().getNow(null);
    assertNotNull(supplier);
    publishUtf8(subscriber, epicArtCourseXmlUtf8);
    assertEquals(epicArtCourse, supplier.get());
  }

  @Test
  void deserializeXml_badXml() {
    var subscriber = createDecoder().toObject(new TypeRef<Course>() {}, null);
    publishUtf8(subscriber, "<?xml...NOPE");
    var ex = assertThrows(CompletionException.class, () -> getBody(subscriber));
    assertTrue(ex.getCause() instanceof UncheckedJaxbException, ex.getCause().toString());
  }

  @Test
  void deserializeXml_deferred_badXml() {
    var subscriber = createDecoder().toDeferredObject(new TypeRef<Course>() {}, null);
    var supplier = subscriber.getBody().toCompletableFuture().getNow(null);
    publishUtf8(subscriber, "<?xml...NOPE");
    assertThrows(UncheckedJaxbException.class, supplier::get);
  }

  private static void publishUtf8(BodySubscriber<?> subscriber, String body) {
    publish(subscriber, body, UTF_8);
  }

  private static void publish(BodySubscriber<?> subscriber, String body, Charset charset) {
    subscriber.onSubscribe(NOOP_SUBSCRIPTION);
    subscriber.onNext(List.of(charset.encode(body)));
    subscriber.onComplete();
  }

  private static <T> T getBody(BodySubscriber<T> subscriber) {
    return subscriber.getBody().toCompletableFuture().join();
  }
}
