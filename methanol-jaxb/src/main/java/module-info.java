module methanol.adapter.jaxb {
  requires transitive methanol;
  requires transitive java.xml.bind;
  requires static org.checkerframework.checker.qual;

  exports com.github.mizosoft.methanol.adapter.jaxb;
}
