import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.BodyDecoder;
import com.github.mizosoft.methanol.blackbox.BadzipBodyDecoderFactory;
import com.github.mizosoft.methanol.blackbox.CharSequenceEncoderProvider;
import com.github.mizosoft.methanol.blackbox.FailingBodyDecoderFactory;
import com.github.mizosoft.methanol.blackbox.JacksonProviders;
import com.github.mizosoft.methanol.blackbox.MyBodyDecoderFactory;
import com.github.mizosoft.methanol.blackbox.ProtobufProviders;
import com.github.mizosoft.methanol.blackbox.RegistryFileTypeDetectorProvider;
import com.github.mizosoft.methanol.blackbox.StringDecoderProvider;
import java.nio.file.spi.FileTypeDetector;

open module methanol.blackbox {
  requires methanol;
  requires methanol.adapter.jackson;
  requires methanol.adapter.protobuf;
  requires methanol.brotli;
  requires methanol.testutils;
  requires com.google.protobuf;
  requires java.logging;
  requires static org.checkerframework.checker.qual;

  provides BodyDecoder.Factory with
      MyBodyDecoderFactory.MyDeflateBodyDecoderFactory,
      MyBodyDecoderFactory.MyGzipBodyDecoderFactory,
      BadzipBodyDecoderFactory,
      FailingBodyDecoderFactory;

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
