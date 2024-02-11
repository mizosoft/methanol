import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.adapter.jaxb.jakarta.JaxbAdapterFactory;

/**
 * Provides {@link BodyAdapter.Encoder} and {@link BodyAdapter.Decoder} implementations for XML
 * using JAXB. Note that, for the sake of configurability, the adapters are not service-provided by
 * default. You will need to explicitly declare service-providers that delegate to the instances
 * created by {@link JaxbAdapterFactory}.
 */
module methanol.adapter.jaxb.jakarta {
  requires transitive methanol;
  requires transitive jakarta.xml.bind;
  requires static org.checkerframework.checker.qual;

  exports com.github.mizosoft.methanol.adapter.jaxb.jakarta;
}
