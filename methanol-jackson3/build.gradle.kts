plugins {
  id("conventions.java-library")
  id("conventions.java-testing")
  id("conventions.static-analysis")
  id("conventions.coverage")
  id("conventions.publishing")
  id("conventions.min-version-test")
}

tasks.withType<JavaCompile> {
  options.release = 17  // Jackson 3 requires Java 17+.
}

dependencies {
  api(project(":methanol"))
  api(libs.jackson3.databind)
  testImplementation(libs.jackson3.xml)
  testImplementation(libs.jackson3.protobuf)
  testImplementation(libs.jackson3.avro)
}

tasks.withType<JavaCompile> {
  onlyIf {
    java.toolchain.languageVersion.get().asInt() >= 17
  }
}

tasks.withType<Test> {
  onlyIf {
    java.toolchain.languageVersion.get().asInt() >= 17
  }
}
