module methanol {
  requires transitive java.net.http;
  requires org.checkerframework.checker.qual;

  exports com.github.mizosoft.methanol;

  uses com.github.mizosoft.methanol.BodyDecoder.Factory;

  uses com.github.mizosoft.methanol.Converter.OfRequest;
  uses com.github.mizosoft.methanol.Converter.OfResponse;

  provides com.github.mizosoft.methanol.BodyDecoder.Factory with
      com.github.mizosoft.methanol.internal.dec.DeflateBodyDecoderFactory,
      com.github.mizosoft.methanol.internal.dec.GzipBodyDecoderFactory;
}
