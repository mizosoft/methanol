import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.BodyDecoder;
import com.github.mizosoft.methanol.blackbox.support.BadzipBodyDecoderFactory;
import com.github.mizosoft.methanol.blackbox.support.CharSequenceEncoderProvider;
import com.github.mizosoft.methanol.blackbox.support.FailingBodyDecoderFactory;
import com.github.mizosoft.methanol.blackbox.support.JacksonFluxProviders;
import com.github.mizosoft.methanol.blackbox.support.JacksonProviders;
import com.github.mizosoft.methanol.blackbox.support.JaxbProviders;
import com.github.mizosoft.methanol.blackbox.support.MyBodyDecoderFactory;
import com.github.mizosoft.methanol.blackbox.support.ProtobufProviders;
import com.github.mizosoft.methanol.blackbox.support.RegistryFileTypeDetectorProvider;
import com.github.mizosoft.methanol.blackbox.support.StringDecoderProvider;
import java.nio.file.spi.FileTypeDetector;

open module methanol.blackbox {
  requires methanol;
  requires methanol.adapter.jackson;
  requires methanol.adapter.jackson.flux;
  requires methanol.adapter.protobuf;
  requires methanol.adapter.jaxb;
  requires methanol.brotli;
  requires methanol.testing;
  requires com.google.protobuf;
  requires org.junit.jupiter.api;
  requires mockwebserver3;
  requires okio;
  requires reactor.core;
  requires org.reactivestreams;
  requires org.eclipse.persistence.moxy;
  requires java.sql; // Required by org.eclipse.persistence.moxy
  requires static org.checkerframework.checker.qual;

  provides BodyDecoder.Factory with
      MyBodyDecoderFactory.MyDeflateBodyDecoderFactory,
      MyBodyDecoderFactory.MyGzipBodyDecoderFactory,
      BadzipBodyDecoderFactory,
      FailingBodyDecoderFactory;
  provides BodyAdapter.Encoder with
      JacksonFluxProviders.EncoderProvider,
      JacksonProviders.EncoderProvider,
      ProtobufProviders.EncoderProvider,
      JaxbProviders.EncoderProvider,
      CharSequenceEncoderProvider;
  provides BodyAdapter.Decoder with
      JacksonFluxProviders.DecoderProvider,
      JacksonProviders.DecoderProvider,
      ProtobufProviders.DecoderProvider,
      JaxbProviders.DecoderProvider,
      StringDecoderProvider;
  provides FileTypeDetector with
      RegistryFileTypeDetectorProvider;
}
