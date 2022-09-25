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

package com.github.mizosoft.methanol.internal.decoder;

import static com.github.mizosoft.methanol.testing.TestUtils.inflate;
import static com.github.mizosoft.methanol.testing.TestUtils.zlibUnwrap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.github.mizosoft.methanol.decoder.AsyncDecoder;
import com.github.mizosoft.methanol.testing.decoder.Decode;
import com.github.mizosoft.methanol.testing.decoder.Decode.BufferSizeOption;
import java.io.EOFException;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class DeflateDecoderTest extends ZLibDecoderTest {
  @Override
  String good() {
    return "eJzlkTFuwzAMRXed4m9eegejCIrCQMcCmRmLiQTLoiHJMXT7UgrQokN6gQ5aPsnHz6+zozJkBL4WFEGmOppPx5mxJaqcMsjHoeCQtPh4A8W6SmLzdudUm2qRnRRYOSJ8xDXQytmcf2Ot4PDFoXTyJcnCEZvnmTMkNlkHRdJopmFFkNxWrRV38TNjphCaoJ1VdmOGE+2KmYa7slhJ2VEzZ36UC0fbRi40Lwclm1F8CGj0vt2cqcyuH6Tyw5ZNTGvGTbBv7ZS8tkbzwUVxtJeKWVaG7AVyBWUd+rv4Gi0Op26mHmYL4l1s3zhpywKfzYniU8BoellvRrv34bIw6acoamud+np4PmmS6lkzy7KH8TsjRGbbEY4Tv+iPkft3CT4tfwERoAh6";
  }

  @Override
  String bad() {
    return "eJzlkTFuwzAMRXed4m9eegejCIrCQMcCmRmLiQTLoiHJMXT7UgrQokN6gQ5aPsnHz6+zozJkBL4WFEGmOppPx5mxJaqcMsjHoeCQtPh4A8W6SmLzdudUm2qRnRRYOSJ8xDXQytmcf2Ot4PDFoXTyJcnCEZvnmTMkNlkHRdJopmFFkNxWrRV38TNjphCaoJ1VdmOGefyXT4G7slhJ2VEzZ36UC0fbRi40Lwclm1F8CGj0vt2cqcyuH6Tyw5ZNTGvGTbBv7ZS8tkbzwUVxtJeKWVaG7AVyBWUd+rv4Gi0Op26mHmYL4l1s3zhpywKfzYniU8BoellvRrv34bIw6acoamud+np4PmmS6lkzy7KH8TsjRGbbEY4Tv+iPkft3CT4tfwERoAh6";
  }

  @Override
  String encoding() {
    return "deflate";
  }

  @Override
  AsyncDecoder newDecoder() {
    return new DeflateDecoder();
  }

  @Override
  byte[] nativeDecode(byte[] compressed) {
    return inflate(compressed);
  }

  @Test
  void decodesUnwrappedGoodStream() throws IOException {
    var goodStream = BASE64_DEC.decode(good());
    var goodStreamNowrap = zlibUnwrap(goodStream);
    for (var option : BufferSizeOption.values()) {
      assertThat(Decode.decode(newDecoder(), goodStreamNowrap, option))
          .isEqualTo(inflate(goodStream));
    }
  }

  @Test
  void throwsEOFExceptionWhenLessThanTwoBytesAreReceived() {
    assertThatExceptionOfType(EOFException.class)
        .isThrownBy(
            () -> Decode.decode(newDecoder(), new byte[0], BufferSizeOption.IN_ONE_OUT_ONE));
    assertThatExceptionOfType(EOFException.class)
        .isThrownBy(
            () -> Decode.decode(newDecoder(), new byte[] {0xf}, BufferSizeOption.IN_ONE_OUT_ONE));
  }
}
