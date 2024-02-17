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

import com.github.mizosoft.methanol.decoder.AsyncDecoder;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

/** {@code AsyncDecoder} for <a href="https://datatracker.ietf.org/doc/html/rfc1952">gzip</a>. */
final class GzipDecoder implements AsyncDecoder {
  static final String ENCODING = "gzip";

  /** ID1 and ID2 as a little-endian short. */
  private static final int GZIP_MAGIC = 0x8B1F;

  private static final int CM_DEFLATE = 8;
  private static final int HEADER_SIZE = 10;
  private static final int HEADER_SKIPPED_SIZE = 6;
  private static final int TRAILER_SIZE = 8;
  private static final int TEMP_BUFFER_SIZE = 10;
  private static final int MIN_GZIP_STREAM_SIZE = HEADER_SIZE + TRAILER_SIZE + 2;
  private static final int BYTE_MASK = 0xFF;
  private static final int SHORT_MASK = 0xFFFF;
  private static final long INT_MASK = 0xFFFFFFFFL;

  private final Inflater inflater = new Inflater(true); // Gzip has its own wrapping method.

  private final ByteBuffer tempBuffer =
      ByteBuffer.allocate(TEMP_BUFFER_SIZE)
          .order(ByteOrder.LITTLE_ENDIAN); // Multi-byte gzip values are little-endian.

  private final CRC32 crc = new CRC32();

  /**
   * Whether to compute the CRC of data upto, but not including, the compressed stream, in case
   * {@link Flag#HCRC} is set.
   */
  private boolean computeHcrc;

  private State state = State.BEGIN;

  /** The gzip stream flags. Set after the header is read. */
  private int flags;

  /** The extra field length in bytes. Used if {@link Flag#EXTRA} is set. */
  private int fieldLength;

  /** The current position in the extra field. Used if {@link Flag#EXTRA} is set. */
  private int fieldPosition;

  GzipDecoder() {}

  @Override
  public String encoding() {
    return ENCODING;
  }

  @Override
  public void decode(ByteSource source, ByteSink sink) throws IOException {
    // Whether an attempt to read trailing data after the current gzip stream as another
    // concatenated gzip stream has failed.
    IOException failedToReadConcatenatedHeader = null;

    outerLoop:
    while (state != State.END) {
      switch (state) {
        case BEGIN:
          state = State.HEADER.prepare(this);
          // Fallthrough.

        case HEADER:
          if (source.remaining() < HEADER_SIZE) {
            break outerLoop;
          }
          readHeader(source);
          state = State.fromNextFlag(this);
          break;

        case FLG_EXTRA_LEN:
          if (source.remaining() < Short.BYTES) {
            break outerLoop;
          }
          fieldLength = getUShort(source);
          state = State.FLG_EXTRA_DATA.prepare(this);
          // Fallthrough.

        case FLG_EXTRA_DATA:
          if (!trySkipExtraField(source)) {
            break outerLoop;
          }
          state = State.fromNextFlag(this);
          break;

        case FLG_ZERO_TERMINATED_FIELD:
          if (!tryConsumeToZeroByte(source)) {
            break outerLoop;
          }
          state = State.fromNextFlag(this);
          break;

        case FLG_HCRC:
          if (source.remaining() < Short.BYTES) {
            break outerLoop;
          }
          long crc16 = crc.getValue() & SHORT_MASK; // Mask to get lower 16 bits
          checkValue(crc16, getUShort(source), "corrupt gzip header");
          state = State.DEFLATED.prepare(this);
          // Fallthrough.

        case DEFLATED:
          InflaterUtils.inflateSourceWithChecksum(inflater, source, sink, crc);
          if (!inflater.finished()) {
            break outerLoop;
          }
          state = State.TRAILER.prepare(this);
          // Fallthrough.

        case TRAILER:
          if (source.remaining() < TRAILER_SIZE) {
            break outerLoop;
          }
          readTrailer(source);
          state = State.POSSIBLY_CONCATENATED_HEADER;
          // Fallthrough.

        case POSSIBLY_CONCATENATED_HEADER:
          // Check whether there's more data that is to be regarded as a concatenated gzip stream,
          // or this is the end of the current gzip stream.
          // TODO: allow ignoring trailing garbage via a system property

          if (source.remaining() < MIN_GZIP_STREAM_SIZE) {
            if (!source.finalSource()) {
              break outerLoop; // Expect either more bytes or end-of-stream to come.
            }

            // Treat as the end of the current gzip stream. We check at the end if we got any
            // unexpected bytes.
            state = State.END;
          } else {
            State.HEADER.prepare(this);
            try {
              // Rewind reading as another gzip stream.
              readHeader(source);
              state = State.fromNextFlag(this);
            } catch (IOException e) {
              failedToReadConcatenatedHeader = e;
              state = State.END;
            }
          }
          break;

        default:
          throw new AssertionError("Unexpected state: " + state);
      }
    }

    // Fail if we reached the end of the current gzip stream and there's unexpected trailing data.
    if (state == State.END && source.hasRemaining()) {
      var streamFinishedPrematurely = new IOException("Gzip stream finished prematurely");
      if (failedToReadConcatenatedHeader != null) {
        streamFinishedPrematurely.addSuppressed(failedToReadConcatenatedHeader);
      }
      throw streamFinishedPrematurely;
    }

    // Fail if source buffers end prematurely.
    if (state != State.END && source.finalSource()) {
      throw new EOFException("Unexpected end of gzip stream");
    }
  }

  @Override
  public void close() {
    inflater.end();
  }

  private ByteBuffer read(ByteSource source, int byteCount) {
    assert source.remaining() >= byteCount; // This is guaranteed by caller.

    source.pullBytes(tempBuffer.rewind().limit(byteCount));
    if (computeHcrc) {
      crc.update(tempBuffer.rewind());
    }
    return tempBuffer.rewind();
  }

  private int getUByte(ByteSource source) {
    return read(source, Byte.BYTES).get() & BYTE_MASK;
  }

  private int getUShort(ByteSource source) {
    return read(source, Short.BYTES).getShort() & SHORT_MASK;
  }

  private long getUInt(ByteSource source) {
    return read(source, Integer.BYTES).getInt() & INT_MASK;
  }

  private void readHeader(ByteSource source) throws IOException {
    // +---+---+---+---+---+---+---+---+---+---+
    // |ID1|ID2|CM |FLG|     MTIME     |XFL|OS | (more-->)
    // +---+---+---+---+---+---+---+---+---+---+
    checkValue(GZIP_MAGIC, getUShort(source), "Not in gzip format");
    checkValue(CM_DEFLATE, getUByte(source), "Unsupported compression method");
    int flags = getUByte(source);
    if (Flag.RESERVED.isEnabled(flags)) {
      throw new ZipException(format("Unsupported flags: %#x", flags));
    }
    if (!Flag.HCRC.isEnabled(flags)) {
      computeHcrc = false;
    }

    // Save for subsequent states.
    this.flags = flags;

    // Other header fields are ignored.
    read(source, HEADER_SKIPPED_SIZE);
  }

  private void readTrailer(ByteSource source) throws IOException {
    // +---+---+---+---+---+---+---+---+
    // |     CRC32     |     ISIZE     |
    // +---+---+---+---+---+---+---+---+
    checkValue(crc.getValue(), getUInt(source), "Corrupt gzip stream (CRC32)");
    checkValue(
        inflater.getBytesWritten() & INT_MASK, // Mask for modulo 2^32.
        getUInt(source),
        "Corrupt gzip stream (ISIZE)");
  }

  private boolean trySkipExtraField(ByteSource source) {
    // +=================================+
    // |...XLEN bytes of "extra field"...| (more-->)
    // +=================================+
    while (source.hasRemaining() && fieldPosition < fieldLength) {
      var buffer = source.currentSource();
      int skipped = Math.min(buffer.remaining(), fieldLength - fieldPosition);
      int updatedPosition = buffer.position() + skipped;
      if (computeHcrc) {
        int originalLimit = buffer.limit();
        crc.update(buffer.limit(updatedPosition)); // Consumes to updatedPosition.
        buffer.limit(originalLimit);
      } else {
        buffer.position(updatedPosition);
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
      var buffer = source.currentSource();
      if (computeHcrc) {
        buffer.mark();
      }

      int zeroPosition = -1;
      while (buffer.hasRemaining()) {
        if (buffer.get() == 0) {
          zeroPosition = buffer.position() - 1;
          break;
        }
      }

      int originalLimit = -1;
      if (zeroPosition >= 0) {
        originalLimit = buffer.limit();
        buffer.limit(zeroPosition + 1);
      }

      if (computeHcrc) {
        crc.update(buffer.reset());
      }

      if (zeroPosition >= 0) {
        buffer.limit(originalLimit);
        return true;
      }
    }
    return false;
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
      void onPrepare(GzipDecoder decoder) {
        decoder.crc.reset();

        // Assume FHCRC is enabled till we know for sure after reading the header.
        decoder.computeHcrc = true;
      }
    },
    FLG_EXTRA_LEN {
      @Override
      void onPrepare(GzipDecoder decoder) {
        decoder.fieldLength = 0;
      }
    },
    FLG_EXTRA_DATA {
      @Override
      void onPrepare(GzipDecoder decoder) {
        decoder.fieldPosition = 0;
      }
    },
    FLG_ZERO_TERMINATED_FIELD,
    FLG_HCRC {
      @Override
      void onPrepare(GzipDecoder decoder) {
        decoder.computeHcrc = false;
      }
    },
    DEFLATED {
      @Override
      void onPrepare(GzipDecoder decoder) {
        decoder.inflater.reset();
        decoder.crc.reset();
      }
    },
    TRAILER,
    POSSIBLY_CONCATENATED_HEADER {
      @Override
      void onPrepare(GzipDecoder decoder) {
        HEADER.onPrepare(decoder);
      }
    },
    END;

    void onPrepare(GzipDecoder decoder) {}

    final State prepare(GzipDecoder decoder) {
      onPrepare(decoder);
      return this;
    }

    /** Returns the next state from as known from the flag byte & clears the corresponding bit. */
    private static State fromNextFlag(GzipDecoder decoder) {
      var flag = Flag.nextEnabled(decoder.flags);
      decoder.flags = flag.clear(decoder.flags);
      return flag.prepareState(decoder);
    }
  }

  /** Flags in the FLG byte. */
  private enum Flag {
    // Order of declaration is important!

    RESERVED(0xE0, State.END) { // 1110_0000
      @Override
      boolean isEnabled(int flags) {
        return (flags & value) != 0;
      }
    },
    EXTRA(4, State.FLG_EXTRA_LEN),
    NAME(8, State.FLG_ZERO_TERMINATED_FIELD),
    COMMENT(16, State.FLG_ZERO_TERMINATED_FIELD),
    HCRC(2, State.FLG_HCRC),
    TEXT(1, State.DEFLATED), // Only a marker, doesn't have a dedicated state
    NONE(0, State.DEFLATED);

    final int value;
    final State state;

    Flag(int value, State state) {
      this.value = value;
      this.state = state;
    }

    boolean isEnabled(int flags) {
      return (flags & value) == value;
    }

    int clear(int flags) {
      return flags & ~value;
    }

    State prepareState(GzipDecoder decoder) {
      return state.prepare(decoder);
    }

    static Flag nextEnabled(int flags) {
      for (var flag : values()) {
        if (flag.isEnabled(flags)) {
          return flag;
        }
      }

      // Flags cover all possible cases for a byte so this shouldn't be possible
      throw new AssertionError("Couldn't get next Flag for: " + Integer.toHexString(flags));
    }
  }
}
