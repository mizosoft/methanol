module methanol.adapter.jackson {
  requires transitive methanol;
  requires transitive com.fasterxml.jackson.databind;
  requires static org.checkerframework.checker.qual;

  exports com.github.mizosoft.methanol.adapter.jackson;
}
