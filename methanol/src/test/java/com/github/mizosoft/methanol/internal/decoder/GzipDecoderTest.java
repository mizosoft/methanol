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

import static com.github.mizosoft.methanol.testutils.TestUtils.gunzip;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.InstanceOfAssertFactories.THROWABLE;

import com.github.mizosoft.methanol.decoder.AsyncDecoder;
import com.github.mizosoft.methanol.testutils.MockGzipMember;
import com.github.mizosoft.methanol.testutils.MockGzipMember.CorruptionMode;
import com.github.mizosoft.methanol.testutils.dec.Decode;
import com.github.mizosoft.methanol.testutils.dec.Decode.BufferSizeOption;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;
import org.junit.jupiter.api.Test;

class GzipDecoderTest extends ZLibDecoderTest {
  private static final int EXTRA_FIELD_SIZE = 512;
  private static final long INT_MASK = 0xFFFFFFFFL;

  private static final String HAPPY_TEXT = "I'm a happy compressible string";

  @Override
  String good() {
    return "H4sIAAAAAAAAAOWRMW7DMAxFd53ib156B6MIisJAxwKZGYuJBMuiIckxdPtSCtCiQ3qBDlo+ycfPr7OjMmQEvhYUQaY6mk/HmbElqpwyyMeh4JC0+HgDxbpKYvN251SbapGdFFg5InzENdDK2Zx/Y63g8MWhdPIlycIRm+eZMyQ2WQdF0mimYUWQ3FatFXfxM2OmEJqgnVV2Y4YT7YqZhruyWEnZUTNnfpQLR9tGLjQvByWbUXwIaPS+3ZypzK4fpPLDlk1Ma8ZNsG/tlLy2RvPBRXG0l4pZVobsBXIFZR36u/gaLQ6nbqYeZgviXWzfOGnLAp/NieJTwGh6WW9Gu/fhsjDppyhqa536eng+aZLqWTPLsofxOyNEZtsRjhO/6I+R+3cJPi1/AbQvoIz+AgAA";
  }

  @Override
  String bad() {
    return "H4sIAAAAAAAAAOWRMW7DMAxFd53ib156B6MIisJAxwKZGYuJBMuiIckxdPtSCtCiQ3qBDlo+ycfPr7OjMmQEvhYUQaY6mk/HmbElqpwyyMeh4JC0+HgDxbpKYvN251SbapGdFFg5InzENdDK2Zx/Y63g8MWhdPIlycIRm+eZMyQ2WQdF0mimYUWQ3FatFXfxM2OmEJqgnVV28lMioFqZhruyWEnZUTNnfpQLR9tGLjQvByWbUXwIaPS+3ZypzK4fpPLDlk1Ma8ZNsG/tlLy2RvPBRXG0l4pZVobsBXIFZR36u/gaLQ6nbqYeZgviXWzfOGnLAp/NieJTwGh6WW9Gu/fhsjDppyhqa536eng+aZLqWTPLsofxOyNEZtsRjhO/6I+R+3cJPi1/AbQvoIz+AgAA";
  }

  @Override
  String encoding() {
    return "gzip";
  }

  @Override
  AsyncDecoder newDecoder() {
    return new GzipDecoder();
  }

  @Override
  byte[] nativeDecode(byte[] compressed) {
    return gunzip(compressed);
  }

  // gzip-specific tests

  private void assertMemberDecodes(MockGzipMember member, BufferSizeOption sizeOption)
      throws IOException {
    var compressed = member.getBytes();
    assertThat(Decode.decode(new GzipDecoder(), compressed, sizeOption))
        .isEqualTo(gunzip(compressed));
  }

  @Test
  void decodesWithExtraField() throws IOException {
    for (var option : BufferSizeOption.inOptions()) {
      var member = happyMember().addExtraField(EXTRA_FIELD_SIZE).build();
      assertMemberDecodes(member, option);
    }
  }

  @Test
  void decodesWithComment() throws IOException {
    for (var option : BufferSizeOption.inOptions()) {
      var member = happyMember().addComment(EXTRA_FIELD_SIZE).build();
      assertMemberDecodes(member, option);
    }
  }

  @Test
  void decodeWithFileName() throws IOException {
    for (var option : BufferSizeOption.inOptions()) {
      var member = happyMember().addFileName(EXTRA_FIELD_SIZE).build();
      assertMemberDecodes(member, option);
    }
  }

  @Test
  void decodeWithHcrc() throws IOException {
    for (var option : BufferSizeOption.inOptions()) {
      var member = happyMember().addHeaderChecksum().build();
      assertMemberDecodes(member, option);
    }
  }

  @Test
  void decodeWithTextFlag() throws IOException {
    var member = happyMember().setText().build();
    assertMemberDecodes(member, BufferSizeOption.IN_MANY_OUT_MANY);
  }

  @Test
  void decodeWithAllFlags() throws IOException {
    for (var option : BufferSizeOption.inOptions()) {
      var member = enableAllOptions(happyMember(), EXTRA_FIELD_SIZE).build();
      assertMemberDecodes(member, option);
    }
  }

  @Test
  void trailingGarbage10Bytes() {
    var gzipBytes = happyMember().build().getBytes();
    var paddedGzipBytes = Arrays.copyOfRange(gzipBytes, 0, gzipBytes.length + 10);
    for (var option : BufferSizeOption.values()) {
      assertThatExceptionOfType(IOException.class)
          .isThrownBy(() -> Decode.decode(new GzipDecoder(), paddedGzipBytes, option))
          .withMessageContaining("gzip stream finished prematurely");
    }
  }

  @Test
  void trailingGarbage30Bytes() {
    var gzipBytes = happyMember().build().getBytes();
    var paddedGzipBytes = Arrays.copyOfRange(gzipBytes, 0, gzipBytes.length + 30);
    for (var option : BufferSizeOption.values()) {
      assertThatIOException()
          .isThrownBy(() -> Decode.decode(new GzipDecoder(), paddedGzipBytes, option))
          .withMessageContaining("gzip stream finished prematurely")
          .asInstanceOf(THROWABLE)
          .hasSuppressedException(
              new ZipException("not in gzip format; expected: 0x8b1f, found: 0x0"));
    }
  }

  @Test
  void decodesConcat() throws IOException {
    var outBuff = new ByteArrayOutputStream();
    outBuff.write(happyMember().build().getBytes());
    outBuff.write(BASE64_DEC.decode(good()));
    outBuff.write(enableAllOptions(happyMember(), 100).build().getBytes());
    //noinspection EmptyTryBlock
    try (var ignored = new GZIPOutputStream(outBuff)) {
      // Write empty member
    }
    try (var gzOut = new OutputStreamWriter(new GZIPOutputStream(outBuff))) {
      gzOut.write("It is possible to have multiple gzip members in the same stream");
    }
    var multiMemberGzipBytes = outBuff.toByteArray();
    for (var option : BufferSizeOption.values()) {
      assertThat(Decode.decode(new GzipDecoder(), multiMemberGzipBytes, option))
          .isEqualTo(gunzip(multiMemberGzipBytes));
    }
  }

  // Tests for corruptions

  @Test
  void corruptGzipMagic() {
    var member = happyMember().corrupt(CorruptionMode.MAGIC, 0xabcd).build();
    for (var option : BufferSizeOption.inOptions()) {
      assertThatExceptionOfType(ZipException.class)
          .isThrownBy(() -> Decode.decode(new GzipDecoder(), member.getBytes(), option))
          .withMessage("not in gzip format; expected: 0x8b1f, found: 0xabcd");
    }
  }

  @Test
  void corruptCompressionMethod() {
    var member =
        happyMember()
            .corrupt(CorruptionMode.CM, 0x7) // Reserved CM
            .build();
    for (var option : BufferSizeOption.inOptions()) {
      assertThatExceptionOfType(ZipException.class)
          .isThrownBy(() -> Decode.decode(new GzipDecoder(), member.getBytes(), option))
          .withMessage("unsupported compression method; expected: 0x8, found: 0x7");
    }
  }

  @Test
  void corruptFlags() {
    var member =
        happyMember()
            .corrupt(CorruptionMode.FLG, 0xE0) // Reserved flags
            .build();
    for (var option : BufferSizeOption.inOptions()) {
      assertThatExceptionOfType(ZipException.class)
          .isThrownBy(() -> Decode.decode(new GzipDecoder(), member.getBytes(), option))
          .withMessage("unsupported flags: 0xe0");
    }
  }

  @Test
  void corruptCrc32() {
    var crc = new CRC32();
    crc.update(HAPPY_TEXT.getBytes());
    long goodVal = crc.getValue();
    long badVal = ~goodVal & INT_MASK;
    var member = happyMember().corrupt(CorruptionMode.CRC32, (int) badVal).build();
    for (var option : BufferSizeOption.inOptions()) {
      assertThatExceptionOfType(ZipException.class)
          .isThrownBy(() -> Decode.decode(new GzipDecoder(), member.getBytes(), option))
          .withMessage("corrupt gzip stream (CRC32); expected: %#x, found: %#x", goodVal, badVal);
    }
  }

  @Test
  void corruptISize() {
    long goodVal = HAPPY_TEXT.length();
    long badVal = ~goodVal & INT_MASK;
    var member = happyMember().corrupt(CorruptionMode.ISIZE, (int) badVal).build();
    for (var option : BufferSizeOption.inOptions()) {
      assertThatExceptionOfType(ZipException.class)
          .isThrownBy(() -> Decode.decode(new GzipDecoder(), member.getBytes(), option))
          .withMessage("corrupt gzip stream (ISIZE); expected: %#x, found: %#x", goodVal, badVal);
    }
  }

  private static MockGzipMember.Builder enableAllOptions(
      MockGzipMember.Builder builder, int extraFieldSize) {
    return builder
        .addExtraField(extraFieldSize)
        .addComment(extraFieldSize)
        .addFileName(extraFieldSize)
        .addHeaderChecksum()
        .setText();
  }

  private static MockGzipMember.Builder happyMember() {
    return MockGzipMember.newBuilder().data(HAPPY_TEXT.getBytes(US_ASCII));
  }
}
