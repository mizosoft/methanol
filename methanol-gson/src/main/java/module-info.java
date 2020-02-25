module methanol.adapter.gson {
  requires transitive methanol;
  requires transitive com.google.gson;
  requires static org.checkerframework.checker.qual;

  exports com.github.mizosoft.methanol.adapter.gson;
}
