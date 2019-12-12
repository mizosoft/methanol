module methanol {
  requires transitive java.net.http;
  requires org.checkerframework.checker.qual;

  exports com.github.mizosoft.methanol;
  exports com.github.mizosoft.methanol.dec;

  uses com.github.mizosoft.methanol.BodyDecoder.Factory;

  provides com.github.mizosoft.methanol.BodyDecoder.Factory with
      com.github.mizosoft.methanol.internal.dec.DeflateBodyDecoderFactory,
      com.github.mizosoft.methanol.internal.dec.GzipBodyDecoderFactory;
}
