package com.github.mizosoft.methanol.decoder;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.testing.SubmittablePublisher;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

class AsyncBodyDecoderTest {
  // Test for the regression caused by a fix to https://github.com/mizosoft/methanol/issues/117.
  @Test
  void droppedPrefetchUpdate() throws Exception {
    var publisher = new SubmittablePublisher<List<ByteBuffer>>(FlowSupport.SYNC_EXECUTOR);
    int bodySize = 2 * FlowSupport.prefetch(); // Make sure at least two upstream.request(...) calls are needed.
    var decoder =
        new AsyncBodyDecoder<>(
            IdentityDecoder.INSTANCE,
            BodySubscribers.ofByteArray(),
            FlowSupport.SYNC_EXECUTOR,
            bodySize + 1); // Make sure onNext() doesn't fill any sink buffers.
    publisher.subscribe(decoder);
    for (int i = 0; i < bodySize; i++) {
      publisher.submit(List.of(ByteBuffer.allocate(1)));
    }
    publisher.close();
    assertThat(decoder.getBody().toCompletableFuture().get()).hasSize(bodySize);
  }

  private enum IdentityDecoder implements AsyncDecoder {
    INSTANCE;

    @Override
    public String encoding() {
      return "identity";
    }

    @Override
    public void decode(ByteSource source, ByteSink sink) {
      while (source.hasRemaining()) {
        sink.pushBytes(source.currentSource());
      }
    }

    @Override
    public void close() {}
  }
}
