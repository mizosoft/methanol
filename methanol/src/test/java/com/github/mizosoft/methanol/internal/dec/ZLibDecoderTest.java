package com.github.mizosoft.methanol.internal.dec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.mizosoft.methanol.dec.AsyncDecoder;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.ZipException;
import org.junit.jupiter.api.Test;

abstract class ZLibDecoderTest {

  private static final int BUFFER_SIZE = 64;
  private static final int SOURCE_INCREMENT_SCALE = 3;

  static final Base64.Decoder BASE64 = Base64.getDecoder();

  abstract String good();

  abstract String bad();

  abstract String encoding();

  abstract ZLibDecoder newDecoder();

  abstract byte[] nativeDecode(byte[] compressed);

  @Test
  void correctEncoding() {
    try (var dec = newDecoder()) {
      assertEquals(encoding(), dec.encoding()); // Sanity check
    }
  }

  @Test
  void decodesGoodStream() throws IOException {
    byte[] goodStream = BASE64.decode(good());
    for (var so : BuffSizeOption.values()) {
      byte[] decoded = decode(goodStream, so);
      assertArrayEquals(nativeDecode(goodStream), decoded);
    }
  }

  @Test
  void throwsOnBadStream() {
    byte[] badStream = BASE64.decode(bad());
    for (var so : BuffSizeOption.values()) {
      assertThrows(ZipException.class, () -> decode(badStream, so));
    }
  }

  @Test
  void throwsOnOverflow() {
    byte[] goodStream = BASE64.decode(good());
    byte[] overflowedStream = Arrays.copyOfRange(goodStream, 0, goodStream.length + 2);
    for (var so : BuffSizeOption.values()) {
      var t = assertThrows(IOException.class, () -> decode(overflowedStream, so));
      assertTrue(t.getMessage().contains("stream finished prematurely"));
    }
  }

  @Test
  void throwsOnUnderflow() {
    byte[] goodStream = BASE64.decode(good());
    byte[] underflowedStream = Arrays.copyOfRange(goodStream, 0, goodStream.length - 2);
    for (var so : BuffSizeOption.values()) {
      assertThrows(EOFException.class, () -> decode(underflowedStream, so));
    }
  }

  byte[] decode(byte[] compressed, BuffSizeOption sizeOption) throws IOException {
    var source = new IncrementalByteArraySource(
        compressed, sizeOption.inSize, SOURCE_INCREMENT_SCALE * sizeOption.inSize);
    var sink = new ByteArraySink(sizeOption.outSize);
    try (var decoder = newDecoder()) {
      do {
        source.increment();
        decoder.decode(source, sink);
      } while (!source.finalSource());
      assertFalse(source.hasRemaining(), "Source not exhausted after being final");
    };
    return sink.toByteArray();
  }

  // Make sure the decoder works well with different buffer size configs
  @SuppressWarnings("unused")
  enum BuffSizeOption {
    IN_MANY_OUT_MANY(BUFFER_SIZE, BUFFER_SIZE),
    IN_ONE_OUT_MANY(1, BUFFER_SIZE),
    IN_MANY_OUT_ONE(BUFFER_SIZE, 1),
    IN_ONE_OUT_ONE(1, 1);

    int inSize;
    int outSize;

    BuffSizeOption(int inSize, int outSize) {
      this.inSize = inSize;
      this.outSize = outSize;
    }

    static BuffSizeOption[] inOptions() {
      return new BuffSizeOption[] {IN_MANY_OUT_MANY, IN_ONE_OUT_MANY};
    }
  }

  /** A ByteSource that reads from a byte array up to a limit that can be incremented.  */
  private static final class IncrementalByteArraySource implements AsyncDecoder.ByteSource {

    private final byte[] source;
    private final ByteBuffer buffer;
    private final int increment;
    private int position;
    private int limit;

    IncrementalByteArraySource(byte[] source, int bufferSize, int increment) {
      this.source = source;
      buffer = ByteBuffer.allocate(bufferSize);
      this.increment = increment;
      buffer.flip(); // Mark as empty initially
    }

    @Override
    public ByteBuffer currentSource() {
      if (!buffer.hasRemaining()) {
        buffer.clear();
      } else {
        buffer.compact();
      }
      int copy = Math.min(buffer.remaining(), limit - position);
      buffer.put(source, position, copy);
      position += copy;
      return buffer.flip();
    }

    @Override
    public long remaining() {
      return buffer.remaining() + (limit - position);
    }

    @Override
    public boolean finalSource() {
      return limit >= source.length;
    }

    void increment() {
      limit = Math.min(source.length, Math.addExact(limit, increment));
    }
  }

  private static final class ByteArraySink implements AsyncDecoder.ByteSink {

    private final ByteBuffer buffer;
    private final ByteArrayOutputStream dump;

    ByteArraySink(int bufferSize) {
      buffer = ByteBuffer.allocate(bufferSize);
      dump = new ByteArrayOutputStream();
    }

    @Override
    public ByteBuffer currentSink() {
      flush();
      return buffer.clear();
    }

    byte[] toByteArray() {
      flush();
      return dump.toByteArray();
    }

    void flush() {
      buffer.flip();
      if (buffer.hasRemaining()) {
        dump.write(buffer.array(), buffer.position(), buffer.limit());
      }
    }
  }
}
