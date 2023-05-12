plugins {
  id("conventions.java-library")
  id("conventions.static-analysis")
  id("conventions.testing")
  id("conventions.jacoco")
  id("conventions.publishing")
  alias(libs.plugins.protobuf)
}

dependencies {
  api(project(":methanol"))
  api(libs.protobuf.java)

  testImplementation(project(":methanol-testing"))
}

protobuf {
  protoc {
    artifact = libs.protobuf.compiler.get().toString()
  }
}
