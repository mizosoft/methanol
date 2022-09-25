/*
 * Copyright (c) 2021 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.adapter.protobuf;

import static com.github.mizosoft.methanol.adapter.protobuf.ProtobufAdapterFactory.createDecoder;
import static com.github.mizosoft.methanol.testing.Verifiers.verifyThat;
import static org.assertj.core.api.Assertions.from;

import com.github.mizosoft.methanol.adapter.protobuf.PointOuterClass.Point;
import com.github.mizosoft.methanol.testing.TestException;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

class ProtobufDeferredDecoderTest {
  @Test
  void deserialize() {
    var expected = Point.newBuilder().setX(1).setY(2).build();
    verifyThat(createDecoder())
        .converting(Point.class)
        .withDeferredBody(expected.toByteString().asReadOnlyByteBuffer())
        .succeedsWith(expected);
  }

  @Test
  void deserializeWithExtensions() {
    var registry = ExtensionRegistry.newInstance();
    registry.add(PointOuterClass.tag);
    var expected =
        Point.newBuilder().setX(1).setY(2).setExtension(PointOuterClass.tag, "hello").build();
    verifyThat(createDecoder(registry))
        .converting(Point.class)
        .withDeferredBody(expected.toByteString().asReadOnlyByteBuffer())
        .succeedsWith(expected)
        .returns("hello", from(point -> point.getExtension(PointOuterClass.tag)));
  }

  @Test
  void deserializeBadProtobuf() {
    verifyThat(createDecoder())
        .converting(Point.class)
        .withDeferredBody("not protobuf write, obviously")
        .failsWith(UncheckedIOException.class) // IOExceptions are rethrown as UncheckedIOExceptions
        .withCauseInstanceOf(InvalidProtocolBufferException.class);
  }
  
  @Test
  void deserializeWithError() {
    // Upstream errors cause the stream used by the supplier to throw
    // an IOException with the error as its cause. The IOException is
    // rethrown as an UncheckedIOException.
    verifyThat(createDecoder())
        .converting(Point.class)
        .withDeferredFailure(new TestException())
        .failsWith(UncheckedIOException.class)
        .havingCause()
        .withCauseInstanceOf(TestException.class);
  }
}
