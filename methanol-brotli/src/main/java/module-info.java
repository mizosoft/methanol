/**
 * Provides <a href="https://github.com/google/brotli">Brotli</a> decompression for Methanol.
 *
 * @provides com.github.mizosoft.methanol.BodyDecoder.Factory
 */
module methanol.brotli {
  requires methanol;
  requires static org.checkerframework.checker.qual;

  provides com.github.mizosoft.methanol.BodyDecoder.Factory with
      com.github.mizosoft.methanol.brotli.internal.BrotliBodyDecoderFactory;
}
