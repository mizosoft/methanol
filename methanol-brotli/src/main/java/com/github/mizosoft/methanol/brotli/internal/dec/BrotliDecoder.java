package com.github.mizosoft.methanol.brotli.internal.dec;

import com.github.mizosoft.methanol.dec.AsyncDecoder;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

class BrotliDecoder implements AsyncDecoder {

  // TODO: maybe make it configurable with a system property ?
  private static final int INPUT_BUFFER_SIZE = 4096;

  // Initialization is deferred to first decode() to rethrow any IOException directly
  private DecoderJNI.@MonotonicNonNull Wrapper brotliNative;
  private boolean destroyed;

  BrotliDecoder() { // package-private
  }

  @Override
  public String encoding() {
    return BrotliBodyDecoderFactory.BROTLI_ENCODING;
  }

  @Override
  public synchronized void decode(ByteSource source, ByteSink sink) throws IOException {
    if (destroyed) {
      return;
    }

    if (brotliNative == null) {
      brotliNative = new DecoderJNI.Wrapper(INPUT_BUFFER_SIZE);
    }

    outerLoop:
    while (true) {
      switch (brotliNative.getStatus()) {
        case OK:
          brotliNative.push(0);
          break;

        case NEEDS_MORE_INPUT:
          if (!source.hasRemaining()) {
            if (source.finalSource()) {
              throw new EOFException("Unexpected end of brotli stream");
            }
            break outerLoop; // More decode rounds to come...
          }
          ByteBuffer brotliIn = brotliNative.getInputBuffer();
          source.pullBytes(brotliIn.clear());
          brotliNative.push(brotliIn.position());
          break;

        case NEEDS_MORE_OUTPUT:
          do {
            sink.pushBytes(brotliNative.pull());
          } while (brotliNative.hasOutput());
          break;

        case ERROR:
          throw new IOException("Corrupt brotli stream");

        case DONE:
          if (source.hasRemaining()) {
            throw new IOException("Brotli stream finished prematurely");
          }
          // Flush any remaining output
          while (brotliNative.hasOutput()) {
            sink.pushBytes(brotliNative.pull());
          }
          break outerLoop; // Brotli stream finished!
      }
    }
  }

  @Override
  public synchronized void close() {
    if (!destroyed) {
      destroyed = true;
      if (brotliNative != null) {
        brotliNative.destroy();
      }
    }
  }
}
