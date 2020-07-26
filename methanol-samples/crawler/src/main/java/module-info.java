import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.samples.crawler.JsoupDecoderProvider;

module samples.cralwer {
  requires methanol;
  requires org.jsoup;
  requires static org.checkerframework.checker.qual;

  provides BodyAdapter.Decoder with JsoupDecoderProvider;
}