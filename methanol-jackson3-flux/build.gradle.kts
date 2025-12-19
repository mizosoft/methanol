plugins {
  id("conventions.java-library")
  id("conventions.java-testing")
  id("conventions.static-analysis")
  id("conventions.coverage")
  id("conventions.publishing")
  id("conventions.min-version-test")
}

tasks.withType<JavaCompile> {
  options.release = 17
}

dependencies {
  api(project(":methanol"))
  api(libs.jackson3.databind)
  api(libs.reactor.core)
  implementation(libs.reactivestreams)
}
