/*
 * MIT License
 *
 * Copyright (c) 2019 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.testing;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterInputStream;
import org.checkerframework.checker.nullness.qual.Nullable;

/** A mock gzip member with configurable FLG related data and corruption modes. */
public final class MockGzipMember {

  private static final int GZIP_MAGIC = 0x8B1F;
  private static final int CM_DEFLATE = 8;
  private static final int BYTE_MASK = 0xFF;
  private static final int SHORT_MASK = 0xFFFF;
  private static final int INT_MASK = 0xFFFFFFFF;
  private static final int TRAILER_SIZE = 8;

  private final Map<FLG, Integer> flagMap;
  private final Set<FLG> flags;
  private final byte[] data;
  private final Map<CorruptionMode, Integer> corruptions;

  private InputStream openStream() {
    CRC32 dataCrc = new CRC32();
    Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION, true); // no deflate-wrapping
    Iterator<InputStream> ins =
        Stream.<Supplier<InputStream>>builder()
            .add(() -> new ByteArrayInputStream(getHeader()))
            .add(
                () ->
                    new DeflaterInputStream(
                        new CheckedInputStream(new ByteArrayInputStream(data), dataCrc), def))
            .add(
                () ->
                    new ByteArrayInputStream(
                        getTrailer(dataCrc.getValue(), def.getBytesRead() & INT_MASK)))
            .build()
            .map(Supplier::get) // Will be lazily evaluated
            .iterator();
    // SequenceInputStream only takes Enumeration (can't use Collections.enumeration(List)
    // as impl relies on lazy evaluation of stream's iterator)
    @SuppressWarnings("JdkObsolete") Enumeration<InputStream> enumeration =
        new Enumeration<>() {
          @Override
          public boolean hasMoreElements() {
            return ins.hasNext();
          }

          @Override
          public InputStream nextElement() {
            return ins.next();
          }
        };
    return new SequenceInputStream(enumeration);
  }

  private byte[] getTrailer(long crc32, long isize) {
    var corr = corruptions;
    var outBuff = new ByteArrayOutputStream(TRAILER_SIZE);
    writeInt(outBuff, corr.getOrDefault(CorruptionMode.CRC32, (int) crc32) & INT_MASK);
    writeInt(
        outBuff,
        corr.getOrDefault(CorruptionMode.ISIZE, (int) isize) & INT_MASK); // long size % 2^32
    return outBuff.toByteArray();
  }

  private MockGzipMember(Builder builder) {
    flagMap = Map.copyOf(builder.flags);
    flags = flagMap.keySet();
    data = builder.data;
    corruptions = Collections.unmodifiableMap(new EnumMap<>(builder.corruptions));
  }

  public byte[] getBytes() {
    try {
      return openStream().readAllBytes();
    } catch (IOException ioe) {
      throw new UncheckedIOException(ioe);
    }
  }

  private static byte[] rndAscii(int len) {
    byte[] ascii =
        ThreadLocalRandom.current()
            .ints(len, 0x21, 0x7F) // 0x21 to 0x7E
            .collect(StringBuilder::new, (sb, ic) -> sb.append((char) ic), StringBuilder::append)
            .toString()
            .getBytes(US_ASCII);
    return Arrays.copyOf(ascii, ascii.length + 1); // Zero terminated
  }

  private byte[] getHeader() {
    var outBuff = new ByteArrayOutputStream();
    writeMainHeader(outBuff);
    if (flags.contains(FLG.FHCRC)) {
      CRC32 crc32 = new CRC32();
      crc32.update(outBuff.toByteArray());
      writeFlagData(new CheckedOutputStream(outBuff, crc32), crc32);
    } else {
      writeFlagData(outBuff, null);
    }
    return outBuff.toByteArray();
  }

  private void writeMainHeader(OutputStream out) {
    var corr = corruptions;
    writeShort(out, corr.getOrDefault(CorruptionMode.MAGIC, GZIP_MAGIC));
    writeByte(out, corr.getOrDefault(CorruptionMode.CM, CM_DEFLATE));
    writeByte(out, corr.getOrDefault(CorruptionMode.FLG, FLG.toFlgByte(flags)));
    // MTIME default
    writeInt(out, 0);
    // Default XFL & OS
    writeByte(out, 0);
    writeByte(out, 255);
  }

  private void writeFlagData(OutputStream out, @Nullable CRC32 crc32) {
    try {
      if (flags.contains(FLG.FEXTRA)) {
        // The extra field has a format (specific subfields),
        // but this doesn't matter because it will be ignored
        // anyways so the data is randomly generated
        int len = flagMap.get(FLG.FEXTRA) & SHORT_MASK;
        writeShort(out, len);
        byte[] b = new byte[len];
        ThreadLocalRandom.current().nextBytes(b);
        out.write(b);
      }
      if (flags.contains(FLG.FNAME)) {
        int len = flagMap.get(FLG.FNAME);
        out.write(Arrays.copyOf(rndAscii(len), len + 1));
      }
      if (flags.contains(FLG.FCOMMENT)) {
        int len = flagMap.get(FLG.FCOMMENT);
        out.write(Arrays.copyOf(rndAscii(len), len + 1));
      }
    } catch (IOException ioe) {
      throw new UncheckedIOException(ioe);
    }
    if (crc32 != null) { // FLG.HCRC
      writeShort(out, crc32.getValue() & SHORT_MASK); // CRC16
    }
  }

  private enum FLG {
    FTEXT(1),
    FHCRC(2),
    FEXTRA(4),
    FNAME(8),
    FCOMMENT(16);

    final int value;

    FLG(int value) {
      this.value = value;
    }

    static int toFlgByte(Set<FLG> flags) {
      return flags.stream().mapToInt(f -> f.value).reduce((f, v) -> f | v).orElse(0) & BYTE_MASK;
    }
  }

  public static final class Builder {

    private final Map<FLG, Integer> flags;
    private byte[] data;
    private final Map<CorruptionMode, Integer> corruptions;

    Builder() {
      flags = new EnumMap<>(FLG.class);
      data = new byte[0];
      corruptions = new EnumMap<>(CorruptionMode.class);
    }

    public Builder setText() {
      flags.put(FLG.FTEXT, 0);
      return this;
    }

    public Builder addFileName(int len) {
      flags.put(FLG.FNAME, len);
      return this;
    }

    public Builder addComment(int len) {
      flags.put(FLG.FCOMMENT, len);
      return this;
    }

    public Builder addExtraField(int len) {
      flags.put(FLG.FEXTRA, len);
      return this;
    }

    public Builder addHeaderChecksum() {
      flags.put(FLG.FHCRC, 0);
      return this;
    }

    public Builder corrupt(CorruptionMode corruptionMode, int value) {
      corruptions.put(corruptionMode, value);
      return this;
    }

    public Builder data(byte[] data) {
      this.data = data.clone();
      return this;
    }

    public MockGzipMember build() {
      return new MockGzipMember(this);
    }
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  // LITTLE_ENDIAN ordered writes

  private static void writeByte(OutputStream out, long val) {
    try {
      out.write((int) (val & BYTE_MASK));
    } catch (IOException ioe) {
      throw new UncheckedIOException(ioe);
    }
  }

  private static void writeShort(OutputStream out, long val) {
    writeByte(out, val & BYTE_MASK);
    writeByte(out, (val >> Byte.SIZE) & BYTE_MASK);
  }

  private static void writeInt(OutputStream out, long val) {
    writeShort(out, val & SHORT_MASK);
    writeShort(out, (val >> Short.SIZE) & SHORT_MASK);
  }

  /** Modes to corrupt the gzip stream. */
  public enum CorruptionMode {
    MAGIC,
    CM,
    FLG,
    CRC32,
    ISIZE
  }
}
