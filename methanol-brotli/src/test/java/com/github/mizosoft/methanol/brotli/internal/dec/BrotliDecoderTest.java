package com.github.mizosoft.methanol.brotli.internal.dec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.mizosoft.methanol.dec.AsyncDecoder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import org.brotli.dec.BrotliInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BrotliDecoderTest {

  private static final int BUFFER_SIZE = 64;
  private static final int SOURCE_INCREMENT_SCALE = 3;

  private static final String GOOD = "ocAXACEazuXPqLgaOX42Jj+EdAT91430gPT27km/6WbK3kTpTWJBJkmAeWoBWebW3oK/qHGuI8e6WIjsH5Qqmrt4ByakvCwb73IT2E7OA3MDpxszTNgAn1xJrzB3qoFjKUOWYBi+VYYbqmhiWlHmHtjbjdVfy3jnR9rs6X7PuzmVyW93/LLKaujeyU6O/8yJu4RSPpCDX8afTBrKXY6Vh/5ZqGfsC9oJGm3XX+klIwK/5sMFqil13dFUJH/xZhMm/JyLMb+HN6gerSzhhBGBAbNBkYaDVHKTZyy28+4XjDnIaY83AkYLSCJ7BIUq0b90zmwYPG4A";
  private static final String BAD  = "ocAXACEazuXPqLgaOX42Jj+EdAT91430gPT27km/6WbK3kTpTWJBJkmAeWoBWebW3oK/qHGuI8e6WIjsH5Qqmrt4ByakvCwb73IT2E7OA3MDpxszTNgAn1xJrzB3qoFjKUOWYBi+VYYbqmhiWlHmHtjbjdVfy3jnR9rs0D/xwqbv7QEf2rLKaujeyU6O/8yJu4RSPpCDX8afTBrKXY6Vh/5ZqGfsC9oJGm3XX+klIwK/5sMFqil13dFUJH/xZhMm/JyLMb+HN6gerSzhhBGBAbNBkYaDVHKTZyy28+4XjDnIaY83AkYLSCJ7BIUq0b90zmwYPG4A";

  private static final Base64.Decoder BASE64_DECODER = Base64.getDecoder();

  @BeforeAll
  static void loadBrotli() throws IOException {
    BrotliLoader.ensureLoaded();
  }

  @Test
  void decodesGood() throws IOException {
    byte[] goodStream = Base64.getDecoder().decode(GOOD);
    for (var so : BuffSizeOption.values()) {
      assertArrayEquals(brotli(goodStream), decode(goodStream, so));
    }
  }

  @Test
  void throwsOnBad() {
    byte[] badStream = Base64.getDecoder().decode(BAD);
    for (var so : BuffSizeOption.values()) {
      assertThrows(IOException.class, () -> decode(badStream, so));
    }
  }

  @Test
  void throwsOnOverflow() {
    byte[] goodStream = BASE64_DECODER.decode(GOOD);
    byte[] overflowedStream = Arrays.copyOfRange(goodStream, 0, goodStream.length + 2);
    for (var so : BuffSizeOption.values()) {
      // Overflow can throw from two places: either from decoder_jni setting ERROR status
      // if more input is detected in the pushed block, or if decode() loop detects more
      // input in source after decoder_jni sets DONE flag
      var t = assertThrows(IOException.class, () -> decode(overflowedStream, so));
      var msg = t.getMessage();
      assertTrue(msg.equals("Corrupt brotli stream") ||
          msg.equals("Brotli stream finished prematurely"));
    }
  }

  @Test
  void throwsOnUnderflow() {
    byte[] goodStream = BASE64_DECODER.decode(GOOD);
    byte[] underflowedStream = Arrays.copyOfRange(goodStream, 0, goodStream.length - 2);
    for (var so : BuffSizeOption.values()) {
      assertThrows(EOFException.class, () -> decode(underflowedStream, so));
    }
  }

  byte[] decode(byte[] compressed, BuffSizeOption sizeOption) throws IOException {
    var source = new IncrementalByteArraySource(
        compressed, sizeOption.inSize, SOURCE_INCREMENT_SCALE * sizeOption.inSize);
    var sink = new ByteArraySink(sizeOption.outSize);
    try (var decoder = new BrotliDecoder()) {
      do {
        source.increment();
        decoder.decode(source, sink);
      } while (!source.finalSource());
      assertFalse(source.hasRemaining(), "Source not exhausted after being final");
    }
    return sink.toByteArray();
  }

  // TODO: this code is copied from ZLibDecoderTest, maybe make a subproject for test utilities ?

  // Make sure the decoder works well with different buffer size configs
  @SuppressWarnings("unused")
  private enum BuffSizeOption {
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

  private static byte[] brotli(byte[] compressed) {
    try {
      return new BrotliInputStream(new ByteArrayInputStream(compressed)).readAllBytes();
    } catch (IOException ioe) {
      throw new UnsupportedOperationException(ioe);
    }
  }
}