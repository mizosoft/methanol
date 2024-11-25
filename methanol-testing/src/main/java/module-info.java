/** Miscellaneous test utilities used internally. */
module methanol.testing {
  requires transitive methanol;
  requires transitive org.junit.jupiter.params;
  requires transitive org.assertj.core;
  requires okhttp3.tls;
  requires java.logging;
  requires mockwebserver3;
  requires lettuce.core;
  requires methanol.redis;
  requires static org.checkerframework.checker.qual;
  requires static com.google.errorprone.annotations;

  exports com.github.mizosoft.methanol.testing;
  exports com.github.mizosoft.methanol.testing.decoder;
  exports com.github.mizosoft.methanol.testing.file;
  exports com.github.mizosoft.methanol.testing.verifiers;
  exports com.github.mizosoft.methanol.testing.store;

  uses com.github.mizosoft.methanol.testing.MemoryFileSystemProvider;

  provides java.nio.file.spi.FileTypeDetector with
      com.github.mizosoft.methanol.testing.RegistryFileTypeDetector;
}
