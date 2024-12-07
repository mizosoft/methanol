/**
 * Core Methanol module.
 *
 * @uses com.github.mizosoft.methanol.BodyDecoder.Factory
 * @uses com.github.mizosoft.methanol.BodyAdapter.Encoder
 * @uses com.github.mizosoft.methanol.BodyAdapter.Decoder
 * @provides com.github.mizosoft.methanol.BodyDecoder.Factory for the gzip and deflate encodings
 */
module methanol {
  requires transitive java.net.http;
  requires static org.checkerframework.checker.qual;
  requires static com.google.errorprone.annotations;

  exports com.github.mizosoft.methanol;
  exports com.github.mizosoft.methanol.decoder;
  exports com.github.mizosoft.methanol.adapter;
  exports com.github.mizosoft.methanol.function;
  exports com.github.mizosoft.methanol.internal.flow to
      methanol.adapter.jackson,
      methanol.adapter.jackson.flux,
      methanol.redis,
      methanol.testing;
  exports com.github.mizosoft.methanol.internal.extensions to
      methanol.adapter.jackson,
      methanol.adapter.jackson.flux,
      methanol.kotlin;
  exports com.github.mizosoft.methanol.internal.concurrent to
      methanol.adapter.jackson,
      methanol.redis,
      methanol.testing;
  exports com.github.mizosoft.methanol.internal.cache to
      methanol.redis,
      methanol.testing;
  exports com.github.mizosoft.methanol.internal.function to
      methanol.testing;
  exports com.github.mizosoft.methanol.internal to
      methanol.redis,
      methanol.testing,
      methanol.adapter.jaxb.jakarta,
      methanol.adapter.jaxb;

  uses com.github.mizosoft.methanol.BodyDecoder.Factory;
  uses com.github.mizosoft.methanol.BodyAdapter.Encoder;
  uses com.github.mizosoft.methanol.BodyAdapter.Decoder;

  provides com.github.mizosoft.methanol.BodyDecoder.Factory with
      com.github.mizosoft.methanol.internal.decoder.GzipBodyDecoderFactory,
      com.github.mizosoft.methanol.internal.decoder.DeflateBodyDecoderFactory;
}
