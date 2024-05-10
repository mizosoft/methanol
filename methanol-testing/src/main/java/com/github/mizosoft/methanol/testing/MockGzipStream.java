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

/** A mock gzip stream with configurable gzip stream fields {@code &} corruption modes. */
public final class MockGzipStream {
  private static final int GZIP_MAGIC = 0x8B1F;
  private static final int CM_DEFLATE = 8;
  private static final int BYTE_MASK = 0xFF;
  private static final int SHORT_MASK = 0xFFFF;
  private static final long INT_MASK = 0xFFFFFFFFL;
  private static final int TRAILER_SIZE = 8;

  private final Map<Flag, Integer> flags;
  private final byte[] data;
  private final Map<CorruptionMode, Integer> corruptions;

  private MockGzipStream(Builder builder) {
    flags = Map.copyOf(builder.flags);
    data = builder.data;
    corruptions = Collections.unmodifiableMap(new EnumMap<>(builder.corruptions));
  }

  private InputStream openStream() {
    var dataCrc = new CRC32();
    var def = new Deflater(Deflater.DEFAULT_COMPRESSION, true); // No deflate-wrapping.
    var ins =
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
            .map(Supplier::get) // Will be lazily evaluated.
            .iterator();

    // SequenceInputStream only takes Enumerations (can't use Collections.enumeration(List) as the
    // implementation relies on the lazy evaluation of the stream's iterator).
    @SuppressWarnings("JdkObsolete")
    var enumeration =
        new Enumeration<InputStream>() {
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
    var outputBuffer = new ByteArrayOutputStream(TRAILER_SIZE);
    writeInt(outputBuffer, corruptions.getOrDefault(CorruptionMode.CRC32, (int) crc32) & INT_MASK);
    writeInt(outputBuffer, corruptions.getOrDefault(CorruptionMode.ISIZE, (int) isize));
    return outputBuffer.toByteArray();
  }

  public byte[] toByteArray() {
    try {
      return openStream().readAllBytes();
    } catch (IOException ioe) {
      throw new UncheckedIOException(ioe);
    }
  }

  private static byte[] randomZeroTerminatedAscii(int len) {
    var ascii =
        ThreadLocalRandom.current()
            .ints(len, 0x21, 0x7F) // 0x21 to 0x7E
            .collect(StringBuilder::new, (sb, ic) -> sb.append((char) ic), StringBuilder::append)
            .toString()
            .getBytes(US_ASCII);
    return Arrays.copyOf(ascii, ascii.length + 1); // Make it zero terminated.
  }

  private byte[] getHeader() {
    var outputBuffer = new ByteArrayOutputStream();
    writeHeader(outputBuffer);
    if (flags.containsKey(Flag.HCRC)) {
      var crc32 = new CRC32();
      crc32.update(outputBuffer.toByteArray());
      writeFlagData(new CheckedOutputStream(outputBuffer, crc32), crc32);
    } else {
      writeFlagData(outputBuffer, null);
    }
    return outputBuffer.toByteArray();
  }

  private void writeHeader(OutputStream out) {
    var corr = corruptions;
    writeShort(out, corr.getOrDefault(CorruptionMode.MAGIC, GZIP_MAGIC));
    writeByte(out, corr.getOrDefault(CorruptionMode.CM, CM_DEFLATE));
    writeByte(out, corr.getOrDefault(CorruptionMode.FLG, Flag.toFlagByte(flags.keySet())));
    writeInt(out, 0); // MTIME default.
    writeByte(out, 0); // Default XFL & OS.
    writeByte(out, 255);
  }

  private void writeFlagData(OutputStream out, @Nullable CRC32 crc32) {
    try {
      if (flags.containsKey(Flag.EXTRA)) {
        // The extra field has a format (specific subfields), but this doesn't matter because it
        // will be ignored anyway, so the data is randomly generated.
        int len = flags.get(Flag.EXTRA) & SHORT_MASK;
        writeShort(out, len);
        var b = new byte[len];
        ThreadLocalRandom.current().nextBytes(b);
        out.write(b);
      }

      if (flags.containsKey(Flag.NAME)) {
        int len = flags.get(Flag.NAME);
        out.write(Arrays.copyOf(randomZeroTerminatedAscii(len), len + 1));
      }

      if (flags.containsKey(Flag.COMMENT)) {
        int len = flags.get(Flag.COMMENT);
        out.write(Arrays.copyOf(randomZeroTerminatedAscii(len), len + 1));
      }
    } catch (IOException ioe) {
      throw new UncheckedIOException(ioe);
    }

    if (crc32 != null) { // Flag.HCRC is set.
      writeShort(out, crc32.getValue() & SHORT_MASK);
    }
  }

  private enum Flag {
    TEXT(1),
    HCRC(2),
    EXTRA(4),
    NAME(8),
    COMMENT(16);

    final int value;

    Flag(int value) {
      this.value = value;
    }

    static int toFlagByte(Set<Flag> flags) {
      return flags.stream().mapToInt(f -> f.value).reduce((f, v) -> f | v).orElse(0) & BYTE_MASK;
    }
  }

  public static final class Builder {
    private final Map<Flag, Integer> flags = new EnumMap<>(Flag.class);
    private final Map<CorruptionMode, Integer> corruptions = new EnumMap<>(CorruptionMode.class);
    private byte[] data = new byte[0];

    Builder() {}

    public Builder setText() {
      flags.put(Flag.TEXT, 0);
      return this;
    }

    public Builder addFileName(int len) {
      flags.put(Flag.NAME, len);
      return this;
    }

    public Builder addComment(int len) {
      flags.put(Flag.COMMENT, len);
      return this;
    }

    public Builder addExtraField(int len) {
      flags.put(Flag.EXTRA, len);
      return this;
    }

    public Builder addHeaderChecksum() {
      flags.put(Flag.HCRC, 0);
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

    public MockGzipStream build() {
      return new MockGzipStream(this);
    }
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  // LITTLE_ENDIAN ordered writes.

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
