import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.BodyDecoder;
import com.github.mizosoft.methanol.tests.BadzipBodyDecoderFactory;
import com.github.mizosoft.methanol.tests.CharSequenceEncoderProvider;
import com.github.mizosoft.methanol.tests.JacksonProviders;
import com.github.mizosoft.methanol.tests.MyBodyDecoderFactory;
import com.github.mizosoft.methanol.tests.ProtobufProviders;
import com.github.mizosoft.methanol.tests.RegistryFileTypeDetectorProvider;
import com.github.mizosoft.methanol.tests.StringDecoderProvider;
import java.nio.file.spi.FileTypeDetector;

open module methanol.tests {
  requires methanol;
  requires methanol.adapter.jackson;
  requires methanol.adapter.protobuf;
  requires methanol.brotli;
  requires methanol.testutils;
  requires com.google.protobuf;
  requires static org.checkerframework.checker.qual;

  provides BodyDecoder.Factory with
      MyBodyDecoderFactory.MyDeflateBodyDecoderFactory,
      MyBodyDecoderFactory.MyGzipBodyDecoderFactory,
      BadzipBodyDecoderFactory;

  provides BodyAdapter.Encoder with
      JacksonProviders.EncoderProvider,
      ProtobufProviders.EncoderProvider,
      CharSequenceEncoderProvider;

  provides BodyAdapter.Decoder with
      JacksonProviders.DecoderProvider,
      ProtobufProviders.DecoderProvider,
      StringDecoderProvider;

  provides FileTypeDetector with RegistryFileTypeDetectorProvider;
}
