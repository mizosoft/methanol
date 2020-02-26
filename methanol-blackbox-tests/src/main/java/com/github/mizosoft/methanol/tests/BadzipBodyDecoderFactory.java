package com.github.mizosoft.methanol.tests;

import com.github.mizosoft.methanol.BodyDecoder;
import com.github.mizosoft.methanol.dec.AsyncBodyDecoder;
import com.github.mizosoft.methanol.dec.AsyncDecoder;
import java.io.IOException;
import java.net.http.HttpResponse.BodySubscriber;
import java.util.concurrent.Executor;

public class BadzipBodyDecoderFactory implements BodyDecoder.Factory {

  public BadzipBodyDecoderFactory() {}

  @Override
  public String encoding() {
    return "badzip";
  }

  @Override
  public <T> BodyDecoder<T> create(BodySubscriber<T> downstream) {
    return new AsyncBodyDecoder<>(Badzip.INSTANCE, downstream);
  }

  @Override
  public <T> BodyDecoder<T> create(BodySubscriber<T> downstream, Executor executor) {
    return new AsyncBodyDecoder<>(Badzip.INSTANCE, downstream, executor);
  }

  private enum Badzip implements AsyncDecoder {
    INSTANCE;

    @Override
    public String encoding() {
      return "badzip";
    }

    @Override
    public void decode(ByteSource source, ByteSink sink) throws IOException {
      throw new IOException("Ops! I forgot my encoding :(");
    }

    @Override
    public void close() {}
  }
}
