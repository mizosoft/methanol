module methanol.convert.protobuf {
  requires transitive methanol;
  requires com.google.protobuf;
  requires static org.checkerframework.checker.qual;

  exports com.github.mizosoft.methanol.convert.protobuf;
}
