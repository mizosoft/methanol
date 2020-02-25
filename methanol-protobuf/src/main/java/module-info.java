module methanol.adapter.protobuf {
  requires transitive methanol;
  requires com.google.protobuf;
  requires static org.checkerframework.checker.qual;

  exports com.github.mizosoft.methanol.adapter.protobuf;
}
