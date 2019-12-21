module methanol.brotli {
  requires methanol;
  requires org.checkerframework.checker.qual;

  provides com.github.mizosoft.methanol.BodyDecoder.Factory with
      com.github.mizosoft.methanol.brotli.internal.dec.BrotliBodyDecoderFactory;
}
