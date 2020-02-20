module methanol.brotli {
  requires methanol;
  requires static org.checkerframework.checker.qual;

  provides com.github.mizosoft.methanol.BodyDecoder.Factory with
      com.github.mizosoft.methanol.brotli.internal.BrotliBodyDecoderFactory;
}
