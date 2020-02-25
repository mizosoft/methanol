module methanol {
  requires transitive java.net.http;
  requires static org.checkerframework.checker.qual;
  requires static com.google.errorprone.annotations;

  exports com.github.mizosoft.methanol;
  exports com.github.mizosoft.methanol.dec;
  exports com.github.mizosoft.methanol.adapter;
  exports com.github.mizosoft.methanol.internal.flow to
      methanol.adapter.jackson;

  uses com.github.mizosoft.methanol.BodyDecoder.Factory;

  uses com.github.mizosoft.methanol.BodyAdapter.Encoder;
  uses com.github.mizosoft.methanol.BodyAdapter.Decoder;

  provides com.github.mizosoft.methanol.BodyDecoder.Factory with
      com.github.mizosoft.methanol.internal.dec.DeflateBodyDecoderFactory,
      com.github.mizosoft.methanol.internal.dec.GzipBodyDecoderFactory;
}
