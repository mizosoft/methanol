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

import static com.github.mizosoft.methanol.adapter.protobuf.ProtobufAdapterFactory.createEncoder;
import static com.github.mizosoft.methanol.testutils.Verification.verifyThat;

import com.github.mizosoft.methanol.adapter.protobuf.PointOuterClass.Point;
import org.junit.jupiter.api.Test;

class ProtobufEncoderTest {
  @Test
  void compatibleMediaTypes() {
    verifyThat(createEncoder())
        .isCompatibleWith("application/x-protobuf")
        .isCompatibleWith("application/octet-stream")
        .isCompatibleWith("application/*")
        .isCompatibleWith("*/*");
  }

  @Test
  void incompatibleMediaTypes() {
    verifyThat(createEncoder())
        .isNotCompatibleWith("application/json")
        .isNotCompatibleWith("text/*");
  }

  @Test
  void serialize() {
    var point = Point.newBuilder().setX(1).setY(2).build();
    verifyThat(createEncoder())
        .converting(point)
        .succeedsWith(point.toByteString().asReadOnlyByteBuffer());
  }

  @Test
  void serializeWithUnsupportedType() {
    class NotAMessage {}

    verifyThat(createEncoder())
        .converting(new NotAMessage())
        .isNotSupported();
  }

  @Test
  void serializeWithUnsupportedMediaType() {
    verifyThat(createEncoder())
        .converting(Point.newBuilder().setX(1).setY(2).build())
        .withMediaType("application/json")
        .isNotSupported();
  }

  @Test
  void mediaTypeIsAttached() {
    verifyThat(createEncoder())
        .converting(Point.newBuilder().setX(1).setY(2).build())
        .withMediaType("application/x-protobuf")
        .asBodyPublisher()
        .hasMediaType("application/x-protobuf");

    verifyThat(createEncoder())
        .converting(Point.newBuilder().setX(1).setY(2).build())
        .withMediaType("application/octet-stream")
        .asBodyPublisher()
        .hasMediaType("application/octet-stream");
  }
}
