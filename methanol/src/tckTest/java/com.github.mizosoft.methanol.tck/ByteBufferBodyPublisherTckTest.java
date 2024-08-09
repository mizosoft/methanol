package com.github.mizosoft.methanol.tck;

import com.github.mizosoft.methanol.internal.extensions.ByteBufferBodyPublisher;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow;
import org.reactivestreams.tck.flow.FlowPublisherVerification;
import org.testng.annotations.Test;

@Test
public class ByteBufferBodyPublisherTckTest extends FlowPublisherVerification<ByteBuffer> {
  public ByteBufferBodyPublisherTckTest() {
    super(TckUtils.newTestEnvironment(ByteBufferBodyPublisherTckTest.class));
  }

  @Override
  public Flow.Publisher<ByteBuffer> createFlowPublisher(long l) {
    var buffer = ByteBuffer.allocate((int) l * TckUtils.BUFFER_SIZE);
    while (buffer.hasRemaining()) {
      buffer.put(TckUtils.generateData());
    }
    return new ByteBufferBodyPublisher(buffer.flip(), TckUtils.BUFFER_SIZE);
  }

  @Override
  public Flow.Publisher<ByteBuffer> createFailedFlowPublisher() {
    return null; // Cannot fail.
  }

  @Override
  public long maxElementsFromPublisher() {
    return TckUtils.MAX_PRECOMPUTED_ELEMENTS;
  }
}
