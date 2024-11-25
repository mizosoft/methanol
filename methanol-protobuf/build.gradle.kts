plugins {
  id("conventions.java-library")
  id("conventions.java-testing")
  id("conventions.static-analysis")
  id("conventions.coverage")
  id("conventions.publishing")
  alias(libs.plugins.protobuf)
}

dependencies {
  api(project(":methanol"))
  api(libs.protobuf.java)
}

protobuf {
  protoc {
    artifact = libs.protobuf.compiler.get().toString()
  }
}
