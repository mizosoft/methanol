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
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.mizosoft.methanol.decoder.AsyncDecoder;
import com.github.mizosoft.methanol.testutils.MockGzipMember;
import com.github.mizosoft.methanol.testutils.MockGzipMember.CorruptionMode;
import com.github.mizosoft.methanol.testutils.dec.Decode;
import com.github.mizosoft.methanol.testutils.dec.Decode.BuffSizeOption;
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

  private static final String HAPPY_TEXT = "I'm a happy compressible string";

  private static final long INT_MASK = 0xFFFFFFFFL;

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

  private void assertMemberDecodes(MockGzipMember member, BuffSizeOption sizeOption)
      throws IOException {
    byte[] compressed = member.getBytes();
    assertArrayEquals(gunzip(compressed), Decode.decode(new GzipDecoder(), compressed, sizeOption));
  }

  @Test
  void decodesWithExtraField() throws IOException {
    for (var so : inSizeOptions()) {
      var member = happyMember()
          .addExtraField(EXTRA_FIELD_SIZE)
          .build();
      assertMemberDecodes(member, so);
    }
  }

  @Test
  void decodesWithComment() throws IOException {
    for (var so : inSizeOptions()) {
      var member = happyMember()
          .addComment(EXTRA_FIELD_SIZE)
          .build();
      assertMemberDecodes(member, so);
    }
  }

  @Test
  void decodeWithFileName() throws IOException {
    for (var so : inSizeOptions()) {
      var member = happyMember()
          .addFileName(EXTRA_FIELD_SIZE)
          .build();
      assertMemberDecodes(member, so);
    }
  }

  @Test
  void decodeWithHcrc() throws IOException {
    for (var so : inSizeOptions()) {
      var member = happyMember()
          .addHeaderChecksum()
          .build();
      assertMemberDecodes(member, so);
    }
  }

  @Test
  void decodeWithTextMarker() throws IOException {
    var member = happyMember()
        .setText()
        .build();
    assertMemberDecodes(member, BuffSizeOption.IN_MANY_OUT_MANY);
  }

  @Test
  void decodeWithAll() throws IOException {
    for (var so : inSizeOptions()) {
      var member = enableAllOptions(happyMember(), EXTRA_FIELD_SIZE).build();
      assertMemberDecodes(member, so);
    }
  }

  @Test
  void trailingGarbage10Bytes() {
    byte[] gzipped = happyMember().build().getBytes();
    byte[] appended = Arrays.copyOfRange(gzipped, 0, gzipped.length + 10);
    for (var so : BuffSizeOption.values()) {
      var t = assertThrows(IOException.class, () -> Decode.decode(new GzipDecoder(), appended, so));
      assertEquals("gzip stream finished prematurely", t.getMessage());
    }
  }

  @Test
  void trailingGarbage30Bytes() {
    byte[] gzipped = happyMember().build().getBytes();
    byte[] appended = Arrays.copyOfRange(gzipped, 0, gzipped.length + 30);
    for (var so : BuffSizeOption.values()) {
      var t = assertThrows(IOException.class, () -> Decode.decode(new GzipDecoder(), appended, so));
      assertEquals("gzip stream finished prematurely", t.getMessage());
      // will suppress read header failure
      var suppressed = t.getSuppressed();
      assertEquals(1, suppressed.length);
      var suppressedIoe = suppressed[0];
      assertTrue(suppressedIoe instanceof IOException);
      assertEquals("not in gzip format; expected: 0x8b1f, found: 0x0", suppressedIoe.getMessage());
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
    byte[] multiGzipped = outBuff.toByteArray();
    for (var so : BuffSizeOption.values()) {
      byte[] decoded = Decode.decode(new GzipDecoder(), multiGzipped, so);
      assertArrayEquals(gunzip(multiGzipped), decoded);
    }
  }

  // Tests for corruptions

  @Test
  void corruptGzipMagic() {
    var member = happyMember()
        .corrupt(CorruptionMode.MAGIC, 0xabcd)
        .build();
    for (var so : inSizeOptions()) {
      var t = assertThrows(ZipException.class,
          () -> Decode.decode(new GzipDecoder(), member.getBytes(), so));
      assertEquals("not in gzip format; expected: 0x8b1f, found: 0xabcd", t.getMessage());
    }
  }

  @Test
  void corruptCompressionMethod() {
    var member = happyMember()
        .corrupt(CorruptionMode.CM, 0x7) // Reserved cm
        .build();
    for (var so : inSizeOptions()) {
      var t = assertThrows(ZipException.class,
          () -> Decode.decode(new GzipDecoder(), member.getBytes(), so));
      assertEquals("unsupported compression method; expected: 0x8, found: 0x7", t.getMessage());
    }
  }

  @Test
  void corruptFlags() {
    var member = happyMember()
        .corrupt(CorruptionMode.FLG, 0xE0) // Reserved flags
        .build();
    for (var so : inSizeOptions()) {
      var t = assertThrows(ZipException.class,
          () -> Decode.decode(new GzipDecoder(), member.getBytes(), so));
      assertEquals("unsupported flags: 0xe0", t.getMessage());
    }
  }

  @Test
  void corruptCrc32() {
    var crc = new CRC32();
    crc.update(HAPPY_TEXT.getBytes());
    long goodVal = crc.getValue();
    long badVal = ~goodVal & INT_MASK;
    var member = happyMember()
        .corrupt(CorruptionMode.CRC32, (int) badVal)
        .build();
    for (var so : inSizeOptions()) {
      var t = assertThrows(ZipException.class,
          () -> Decode.decode(new GzipDecoder(), member.getBytes(), so));
      var msg = format("corrupt gzip stream (CRC32); expected: %#x, found: %#x", goodVal, badVal);
      assertEquals(msg, t.getMessage());
    }
  }

  @Test
  void corruptISize() {
    long goodVal = HAPPY_TEXT.length();
    long badVal = ~goodVal & INT_MASK;
    var member = happyMember()
        .corrupt(CorruptionMode.ISIZE, (int) badVal)
        .build();
    for (var so : inSizeOptions()) {
      var t = assertThrows(ZipException.class,
          () -> Decode.decode(new GzipDecoder(), member.getBytes(), so));
      var msg = format("corrupt gzip stream (ISIZE); expected: %#x, found: %#x", goodVal, badVal);
      assertEquals(msg, t.getMessage());
    }
  }

  private static MockGzipMember.Builder enableAllOptions(
      MockGzipMember.Builder builder, int extraFieldSize) {
    return builder.addExtraField(extraFieldSize)
        .addComment(extraFieldSize)
        .addFileName(extraFieldSize)
        .addHeaderChecksum()
        .setText();
  }

  private static MockGzipMember.Builder happyMember() {
    return MockGzipMember.newBuilder().data(HAPPY_TEXT.getBytes(US_ASCII));
  }

  private static BuffSizeOption[] inSizeOptions() {
    return new BuffSizeOption[] {BuffSizeOption.IN_ONE_OUT_MANY, BuffSizeOption.IN_MANY_OUT_MANY};
  }
}
