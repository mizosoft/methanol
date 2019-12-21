package com.github.mizosoft.methanol.brotli.internal.dec;

import com.github.mizosoft.methanol.BodyDecoder;
import com.github.mizosoft.methanol.dec.AsyncBodyDecoder;
import java.io.IOException;
import java.net.http.HttpResponse.BodySubscriber;
import java.util.concurrent.Executor;

/**
 * {@code BodyDecoder.Factory} provider for brotli encoding.
 */
public class BrotliBodyDecoderFactory implements BodyDecoder.Factory {

  static final String BROTLI_ENCODING = "br";

  /**
   * Creates a new {@code BrotliBodyDecoderFactory}. Meant to be called by the {@code ServiceLoader}
   * class.
   *
   * @throws IOException if the native brotli library cannot be loaded
   */
  public BrotliBodyDecoderFactory() throws IOException {
    BrotliLoader.ensureLoaded();
  }

  @Override
  public String encoding() {
    return BROTLI_ENCODING;
  }

  @Override
  public <T> BodyDecoder<T> create(BodySubscriber<T> downstream) {
    return new AsyncBodyDecoder<>(new BrotliDecoder(), downstream);
  }

  @Override
  public <T> BodyDecoder<T> create(BodySubscriber<T> downstream, Executor executor) {
    return new AsyncBodyDecoder<>(new BrotliDecoder(), downstream, executor);
  }
}
