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

import static java.lang.String.format;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;
import java.util.zip.ZipException;

/** {@code AsyncDecoder} for gzip. */
final class GzipDecoder extends ZLibDecoder {

  private static final int GZIP_MAGIC = 0x8B1F; // ID1 and ID2 as a little-endian ordered short
  private static final int CM_DEFLATE = 8;
  private static final int HEADER_SIZE = 10;
  private static final int HEADER_SKIPPED_SIZE = 6;
  private static final int TRAILER_SIZE = 8;
  private static final int TEMP_BUFFER_SIZE = 10;
  private static final int MIN_GZIP_MEMBER_SIZE = 20;
  private static final int BYTE_MASK = 0xFF;
  private static final int SHORT_MASK = 0xFFFF;
  private static final long INT_MASK = 0xFFFFFFFFL;

  private final ByteBuffer tempBuffer;
  private final CRC32 crc;
  private boolean computeCrc; // Whether to compute crc (in case FHCRC is enabled)
  private State state;

  // flag-related stuff
  private int flags;
  private int fieldLength;
  private int fieldPosition;

  GzipDecoder() {
    super(WrapMode.GZIP);
    tempBuffer =
        ByteBuffer.allocate(TEMP_BUFFER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN); // Multi-byte gzip values are little-endian/unsigned
    crc = new CRC32();
    state = State.BEGIN;
  }

  @Override
  public void decode(ByteSource source, ByteSink sink) throws IOException {
    outerLoop:
    while (state != State.END) {
      switch (state) {
        case BEGIN:
          state = State.HEADER.prepare(this);
          // fallthrough

        case HEADER:
          if (source.remaining() < HEADER_SIZE) {
            break outerLoop;
          }
          readHeader(source);
          state = State.fromFlags(this);
          break;

        case FLG_EXTRA_LEN:
          // +---+---+
          // | XLEN  |
          // +---+---+
          if (source.remaining() < Short.BYTES) {
            break outerLoop;
          }
          fieldLength = getUShort(source);
          state = State.FLG_EXTRA_DATA.prepare(this);
          // fallthrough

        case FLG_EXTRA_DATA:
          if (!trySkipExtraField(source)) {
            break outerLoop;
          }
          state = State.fromFlags(this);
          break;

        case FLG_ZERO_TERMINATED:
          if (!tryConsumeToZeroByte(source)) {
            break outerLoop;
          }
          state = State.fromFlags(this);
          break;

        case FLG_HCRC:
          if (source.remaining() < Short.BYTES) {
            break outerLoop;
          }
          long crc16 = crc.getValue() & SHORT_MASK; // Mask to get lower 16 bits
          checkValue(crc16, getUShort(source), "corrupt gzip header");
          state = State.DEFLATED.prepare(this);
          // fallthrough

        case DEFLATED:
          inflateSource(source, sink);
          if (!inflater.finished()) {
            break outerLoop;
          }
          state = State.TRAILER.prepare(this);
          // fallthrough

        case TRAILER:
          if (source.remaining() < TRAILER_SIZE) {
            break outerLoop;
          }
          readTrailer(source);
          state = State.CONCAT_INSPECTION;
          // fallthrough

        case CONCAT_INSPECTION:
          // Inspect on whether concatenated data has a chance of being
          // another gzip member or this should be the end of the gzip stream.
          // TODO: allow ignoring trailing garbage via a system property
          if (source.remaining() < MIN_GZIP_MEMBER_SIZE) {
            if (!source.finalSource()) {
              break outerLoop; // Expect more bytes to come
            }
            // Treat as end of gzip stream
            state = State.END;
          } else {
            State.HEADER.prepare(this);
            try {
              readHeader(source);
              state = State.fromFlags(this);
              // Keep reading as another gzip stream...
            } catch (IOException e) {
              state = State.END;
            }
          }
          // Fail if reached end with data still available after inspection
          if (state == State.END && source.hasRemaining()) {
            throw new IOException("gzip stream finished prematurely");
          }
          break;

        default:
          throw new AssertionError("unexpected state: " + state);
      }
    }
    // Detect if source buffers end prematurely
    if (state != State.END && source.finalSource()) {
      throw new EOFException("unexpected end of gzip stream");
    }
  }

  private ByteBuffer fillTempBuffer(ByteSource source, int bytes) {
    source.pullBytes(tempBuffer.rewind().limit(bytes));
    assert !tempBuffer.hasRemaining();
    if (computeCrc) {
      crc.update(tempBuffer.rewind());
    }
    return tempBuffer.rewind();
  }

  private int getUByte(ByteSource source) {
    return fillTempBuffer(source, Byte.BYTES).get() & BYTE_MASK;
  }

  private int getUShort(ByteSource source) {
    return fillTempBuffer(source, Short.BYTES).getShort() & SHORT_MASK;
  }

  private long getUInt(ByteSource source) {
    return fillTempBuffer(source, Integer.BYTES).getInt() & INT_MASK;
  }

  private void readHeader(ByteSource source) throws IOException {
    // +---+---+---+---+---+---+---+---+---+---+
    // |ID1|ID2|CM |FLG|     MTIME     |XFL|OS | (more-->)
    // +---+---+---+---+---+---+---+---+---+---+
    checkValue(GZIP_MAGIC, getUShort(source), "not in gzip format");
    checkValue(CM_DEFLATE, getUByte(source), "unsupported compression method");
    int flags = getUByte(source);
    if (FlagOption.RESERVED.isEnabled(flags)) {
      throw new ZipException(format("unsupported flags: %#x", flags));
    }
    if (!FlagOption.HCRC.isEnabled(flags)) {
      computeCrc = false;
    }
    this.flags = flags; // Save for subsequent states
    // Other header fields are ignore
    fillTempBuffer(source, HEADER_SKIPPED_SIZE);
  }

  private void readTrailer(ByteSource source) throws IOException {
    // +---+---+---+---+---+---+---+---+
    // |     CRC32     |     ISIZE     |
    // +---+---+---+---+---+---+---+---+
    checkValue(crc.getValue(), getUInt(source), "corrupt gzip stream (CRC32)");
    checkValue(
        inflater.getBytesWritten() & INT_MASK, // Mask for modulo 2^32
        getUInt(source),
        "corrupt gzip stream (ISIZE)");
  }

  private boolean trySkipExtraField(ByteSource source) {
    // +=================================+
    // |...XLEN bytes of "extra field"...| (more-->)
    // +=================================+
    while (source.hasRemaining() && fieldPosition < fieldLength) {
      ByteBuffer in = source.currentSource();
      int skipped = Math.min(in.remaining(), fieldLength - fieldPosition);
      int skipPosition = in.position() + skipped;
      if (computeCrc) {
        int originalLimit = in.limit();
        crc.update(in.limit(skipPosition)); // Consumes to skipPosition
        in.limit(originalLimit);
      } else {
        in.position(skipPosition);
      }
      fieldPosition += skipped;
    }
    return fieldPosition >= fieldLength;
  }

  private boolean tryConsumeToZeroByte(ByteSource source) {
    // Skip zero-terminated fields: FCOMMENT or FNAME
    // +==========================================================+
    // |...(file comment | original file name), zero-terminated...| (more-->)
    // +==========================================================+
    while (source.hasRemaining()) {
      ByteBuffer in = source.currentSource();
      while (in.hasRemaining()) {
        byte currentByte = in.get();
        if (computeCrc) {
          crc.update(currentByte);
        }
        if (currentByte == 0) {
          return true;
        }
      }
    }
    return false;
  }

  // Override to compute crc32 of the inflated bytes
  @Override
  void inflateBlock(ByteBuffer in, ByteBuffer out) throws IOException {
    int prePos = out.position();
    super.inflateBlock(in, out);
    int postPos = out.position();
    int originalLimit = out.limit();
    crc.update(out.position(prePos).limit(postPos));
    out.limit(originalLimit);
  }

  private static void checkValue(long expected, long found, String msg) throws IOException {
    if (expected != found) {
      throw new ZipException(format("%s; expected: %#x, found: %#x", msg, expected, found));
    }
  }

  private enum State {
    BEGIN,
    HEADER {
      @Override
      void onPrepare(GzipDecoder dec) {
        dec.crc.reset();
        dec.computeCrc = true; // Assume FHCRC is enabled until the FLG byte is read
      }
    },
    FLG_EXTRA_LEN {
      @Override
      void onPrepare(GzipDecoder dec) {
        dec.fieldLength = 0;
      }
    },
    FLG_EXTRA_DATA {
      @Override
      void onPrepare(GzipDecoder dec) {
        dec.fieldPosition = 0;
      }
    },
    FLG_ZERO_TERMINATED,
    FLG_HCRC {
      @Override
      void onPrepare(GzipDecoder dec) {
        dec.computeCrc = false;
      }
    },
    DEFLATED {
      @Override
      void onPrepare(GzipDecoder dec) {
        dec.inflater.reset();
        dec.crc.reset();
      }
    },
    TRAILER,
    CONCAT_INSPECTION {
      @Override
      void onPrepare(GzipDecoder dec) {
        HEADER.onPrepare(dec);
      }
    },
    END;

    void onPrepare(GzipDecoder dec) {}

    final State prepare(GzipDecoder dec) {
      onPrepare(dec);
      return this;
    }

    // Returns the next state from the FLG byte, clearing it's bit afterwards.
    private static State fromFlags(GzipDecoder dec) {
      FlagOption option = FlagOption.nextEnabled(dec.flags);
      dec.flags = option.clear(dec.flags);
      return option.forward(dec);
    }
  }

  /** Options in the FLG byte. */
  @SuppressWarnings("unused") // Most are not explicitly used
  private enum FlagOption {
    // Order of declaration is important!

    RESERVED(0xE0, State.END) { // 1110_0000
      @Override
      boolean isEnabled(int flags) {
        return (flags & value) != 0;
      }
    },
    EXTRA(4, State.FLG_EXTRA_LEN),
    NAME(8, State.FLG_ZERO_TERMINATED),
    COMMENT(16, State.FLG_ZERO_TERMINATED),
    HCRC(2, State.FLG_HCRC),
    TEXT(1, State.DEFLATED), // Only a marker, doesn't have a dedicated state
    NONE(0, State.DEFLATED) {
      @Override
      boolean isEnabled(int flags) {
        return flags == value;
      }
    };

    final int value;
    final State state;

    FlagOption(int value, State state) {
      this.value = value;
      this.state = state;
    }

    boolean isEnabled(int flags) {
      return (flags & value) == value;
    }

    int clear(int flags) {
      return flags & ~value;
    }

    State forward(GzipDecoder ctx) {
      return state.prepare(ctx);
    }

    static FlagOption nextEnabled(int flags) {
      for (FlagOption option : values()) {
        if (option.isEnabled(flags)) {
          return option;
        }
      }
      // FlagOptions cover all possible cases for a byte so this shouldn't be possible
      throw new AssertionError("couldn't get FlgOption for: " + Integer.toHexString(flags));
    }
  }
}
