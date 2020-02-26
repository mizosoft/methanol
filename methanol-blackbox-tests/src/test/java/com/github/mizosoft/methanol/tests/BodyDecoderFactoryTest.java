package com.github.mizosoft.methanol.tests;

import static com.github.mizosoft.methanol.BodyDecoder.Factory.installedBindings;
import static com.github.mizosoft.methanol.BodyDecoder.Factory.installedFactories;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.mizosoft.methanol.BodyDecoder;
import com.github.mizosoft.methanol.tests.MyBodyDecoderFactory.MyDeflateBodyDecoderFactory;
import com.github.mizosoft.methanol.tests.MyBodyDecoderFactory.MyGzipBodyDecoderFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BodyDecoderFactoryTest {

  @Test
  void findInstalled() {
    var expected = Set.of("gzip", "deflate", "br", "badzip");
    var found = installedFactories()
        .stream()
        .map(BodyDecoder.Factory::encoding)
        .collect(Collectors.toSet());
    assertEquals(expected, found);
    assertEquals(expected, installedBindings().keySet());
  }

  @Test
  void userFactoryOverridesDefault() {
    var bindings = installedBindings();
    assertEquals(MyDeflateBodyDecoderFactory.class, bindings.get("deflate").getClass());
    assertEquals(MyGzipBodyDecoderFactory.class, bindings.get("gzip").getClass());
  }
}
