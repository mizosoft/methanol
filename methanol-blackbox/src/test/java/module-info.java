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

import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.BodyDecoder;
import com.github.mizosoft.methanol.blackbox.support.BadzipBodyDecoderFactory;
import com.github.mizosoft.methanol.blackbox.support.CharSequenceEncoderProvider;
import com.github.mizosoft.methanol.blackbox.support.FailingBodyDecoderFactory;
import com.github.mizosoft.methanol.blackbox.support.JacksonFluxProviders;
import com.github.mizosoft.methanol.blackbox.support.JacksonProviders;
import com.github.mizosoft.methanol.blackbox.support.JaxbJakartaProviders;
import com.github.mizosoft.methanol.blackbox.support.JaxbProviders;
import com.github.mizosoft.methanol.blackbox.support.MyBodyDecoderFactory;
import com.github.mizosoft.methanol.blackbox.support.ProtobufProviders;
import com.github.mizosoft.methanol.blackbox.support.StringDecoderProvider;

open module methanol.blackbox {
  requires methanol;
  requires methanol.adapter.jackson;
  requires methanol.adapter.jackson.flux;
  requires methanol.adapter.protobuf;
  requires methanol.adapter.jaxb;
  requires methanol.adapter.jaxb.jakarta;
  requires methanol.brotli;
  requires methanol.testing;
  requires com.google.protobuf;
  requires org.junit.jupiter.api;
  requires mockwebserver3;
  requires okio;
  requires reactor.core;
  requires org.reactivestreams;
  requires java.xml.bind;
  requires jakarta.xml.bind;
  requires org.eclipse.persistence.moxy; // Used for Javax JAXB.
  requires com.sun.xml.bind; // Used for Jakarta JAXB.
  requires java.sql; // Required by org.eclipse.persistence.moxy.
  requires static org.checkerframework.checker.qual;

  provides BodyDecoder.Factory with
      MyBodyDecoderFactory.MyDeflateBodyDecoderFactory,
      MyBodyDecoderFactory.MyGzipBodyDecoderFactory,
      BadzipBodyDecoderFactory,
      FailingBodyDecoderFactory;
  provides BodyAdapter.Encoder with
      JacksonFluxProviders.EncoderProvider,
      JacksonProviders.EncoderProvider,
      ProtobufProviders.EncoderProvider,
      JaxbProviders.EncoderProvider,
      JaxbJakartaProviders.EncoderProvider,
      CharSequenceEncoderProvider;
  provides BodyAdapter.Decoder with
      JacksonFluxProviders.DecoderProvider,
      JacksonProviders.DecoderProvider,
      ProtobufProviders.DecoderProvider,
      JaxbProviders.DecoderProvider,
      JaxbJakartaProviders.DecoderProvider,
      StringDecoderProvider;
}
