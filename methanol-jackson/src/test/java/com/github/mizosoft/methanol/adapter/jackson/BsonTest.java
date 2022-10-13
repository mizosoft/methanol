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

import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mizosoft.methanol.MediaType;
import de.undercouch.bson4jackson.BsonFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class BsonTest {
  private static final MediaType APPLICATION_BSON = MediaType.parse("application/bson");

  @Test
  void encode() throws IOException {
    var mapper = new ObjectMapper(new BsonFactory());
    verifyThat(JacksonAdapterFactory.createEncoder(mapper, APPLICATION_BSON))
        .converting(new Point(1, 2))
        .succeedsWith(
            ByteBuffer.wrap(mapper.writerFor(Point.class).writeValueAsBytes(new Point(1, 2))));
  }

  @Test
  void decode() throws IOException {
    var mapper = new ObjectMapper(new BsonFactory());
    verifyThat(JacksonAdapterFactory.createDecoder(mapper, APPLICATION_BSON))
        .converting(Point.class)
        .withBody(ByteBuffer.wrap(mapper.writerFor(Point.class).writeValueAsBytes(new Point(1, 2))))
        .succeedsWith(new Point(1, 2));
  }

  @Test
  void deferredDecode() throws IOException {
    var mapper = new ObjectMapper(new BsonFactory());
    verifyThat(JacksonAdapterFactory.createDecoder(mapper, APPLICATION_BSON))
        .converting(Point.class)
        .withDeferredBody(
            ByteBuffer.wrap(mapper.writerFor(Point.class).writeValueAsBytes(new Point(1, 2))))
        .succeedsWith(new Point(1, 2));
  }
}
