package com.github.mizosoft.methanol.tests;

import com.github.mizosoft.methanol.BodyDecoder;
import com.github.mizosoft.methanol.BodyDecoder.Factory;
import com.github.mizosoft.methanol.dec.AsyncBodyDecoder;
import com.github.mizosoft.methanol.dec.AsyncDecoder;
import java.io.IOException;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.util.concurrent.Executor;

/** For asserting that user providers replace the defaults for same encoding. */
public abstract class MyBodyDecoderFactory implements BodyDecoder.Factory {

  MyBodyDecoderFactory() {}

  abstract MyDecoder newDecoder();

  @Override
  public <T> BodyDecoder<T> create(BodySubscriber<T> downstream) {
    return new AsyncBodyDecoder<>(newDecoder(), downstream);
  }

  @Override
  public <T> BodyDecoder<T> create(BodySubscriber<T> downstream, Executor executor) {
    return new AsyncBodyDecoder<>(newDecoder(), downstream, executor);
  }

  static AsyncDecoder getDefaultAsyncDecoder(String encoding) {
    return Factory.installedFactories()
        .stream()
        .filter(f -> !(f instanceof MyBodyDecoderFactory) && encoding.equals(f.encoding()))
        .map(f -> ((AsyncBodyDecoder<?>) f.create(BodySubscribers.discarding())).asyncDecoder())
        .findFirst()
        .orElseThrow(() -> new AssertionError("cannot find default decoder for: " + encoding));
  }

  private static final class MyDecoder implements AsyncDecoder {

    private final AsyncDecoder hisDecoder; // developer talking to himself be like

    MyDecoder(AsyncDecoder hisDecoder) {
      this.hisDecoder = hisDecoder;
    }

    @Override
    public String encoding() {
      return hisDecoder.encoding();
    }

    @Override
    public void decode(ByteSource source, ByteSink sink) throws IOException {
      hisDecoder.decode(source, sink);
    }

    @Override
    public void close() {
      hisDecoder.close();
    }
  }

  public static final class MyDeflateBodyDecoderFactory extends MyBodyDecoderFactory {

    public MyDeflateBodyDecoderFactory() {}

    @Override
    MyDecoder newDecoder() {
      return new MyDecoder(getDefaultAsyncDecoder("deflate"));
    }

    @Override
    public String encoding() {
      return "deflate";
    }
  }

  public static final class MyGzipBodyDecoderFactory extends MyBodyDecoderFactory {

    public MyGzipBodyDecoderFactory() {}

    @Override
    MyDecoder newDecoder() {
      return new MyDecoder(getDefaultAsyncDecoder("gzip"));
    }

    @Override
    public String encoding() {
      return "gzip";
    }
  }
}
