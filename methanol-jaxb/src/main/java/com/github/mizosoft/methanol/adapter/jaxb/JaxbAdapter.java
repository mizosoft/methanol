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

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.charset.Charset;
import java.util.function.Supplier;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import org.checkerframework.checker.nullness.qual.Nullable;

abstract class JaxbAdapter extends AbstractBodyAdapter {

  final JaxbBindingFactory jaxbFactory;

  JaxbAdapter(JaxbBindingFactory jaxbFactory) {
    super(MediaType.APPLICATION_XML, MediaType.TEXT_XML);
    this.jaxbFactory = requireNonNull(jaxbFactory);
  }

  @Override
  public boolean supportsType(TypeRef<?> type) {
    if (type.type() instanceof Class<?>) {
      Class<?> clazz = type.rawType();
      return clazz.isAnnotationPresent(XmlRootElement.class)
          || clazz.isAnnotationPresent(XmlType.class)
          || clazz.isAnnotationPresent(XmlEnum.class);
    }
    return false;
  }

  static final class Encoder extends JaxbAdapter implements BodyAdapter.Encoder {

    Encoder(JaxbBindingFactory factory) {
      super(factory);
    }

    @Override
    public BodyPublisher toBody(Object object, @Nullable MediaType mediaType) {
      requireNonNull(object);
      requireSupport(object.getClass());
      requireCompatibleOrNull(mediaType);
      ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
      try {
        Marshaller marshaller = jaxbFactory.createMarshaller(object.getClass());
        String encoding;
        if (mediaType != null && (encoding = mediaType.parameters().get("charset")) != null) {
          marshaller.setProperty(Marshaller.JAXB_ENCODING, encoding);
        }
        marshaller.marshal(object, outputBuffer);
      } catch (JAXBException e) {
        throw new UncheckedJaxbException(e);
      }
      return attachMediaType(BodyPublishers.ofByteArray(outputBuffer.toByteArray()), mediaType);
    }
  }

  static final class Decoder extends JaxbAdapter implements BodyAdapter.Decoder {

    Decoder(JaxbBindingFactory factory) {
      super(factory);
    }

    @Override
    public <T> BodySubscriber<T> toObject(TypeRef<T> objectType, @Nullable MediaType mediaType) {
      requireNonNull(objectType);
      requireSupport(objectType);
      requireCompatibleOrNull(mediaType);
      Class<T> elementClass = objectType.exactRawType();
      Charset charset = charsetOrNull(mediaType);
      Unmarshaller unmarshaller = createUnmarshallerUnchecked(elementClass);
      return BodySubscribers.mapping(
          BodySubscribers.ofByteArray(),
          bytes ->
              unmarshalValue(elementClass, unmarshaller, new ByteArrayInputStream(bytes), charset));
    }

    @Override
    public <T> BodySubscriber<Supplier<T>> toDeferredObject(
        TypeRef<T> objectType, @Nullable MediaType mediaType) {
      requireNonNull(objectType);
      requireSupport(objectType);
      requireCompatibleOrNull(mediaType);
      Class<T> elementClass = objectType.exactRawType();
      Charset charset = charsetOrNull(mediaType);
      Unmarshaller unmarshaller = createUnmarshallerUnchecked(elementClass);
      return BodySubscribers.mapping(
          BodySubscribers.ofInputStream(),
          in -> () -> unmarshalValue(elementClass, unmarshaller, in, charset));
    }

    private <T> T unmarshalValue(
        Class<T> elementClass,
        Unmarshaller unmarshaller,
        InputStream in,
        @Nullable Charset charset) {
      try {
        // If the charset is known from the media type, use it for a Reader
        // to avoid the overhead of having to infer it from the document
        return elementClass.cast(
            charset != null
                ? unmarshaller.unmarshal(new InputStreamReader(in, charset))
                : unmarshaller.unmarshal(in));
      } catch (JAXBException e) {
        throw new UncheckedJaxbException(e);
      }
    }

    private Unmarshaller createUnmarshallerUnchecked(Class<?> elementClass) {
      try {
        return jaxbFactory.createUnmarshaller(elementClass);
      } catch (JAXBException e) {
        throw new UncheckedJaxbException(e);
      }
    }

    private static @Nullable Charset charsetOrNull(@Nullable MediaType mediaType) {
      return mediaType != null ? mediaType.charset().orElse(null) : null;
    }
  }
}
