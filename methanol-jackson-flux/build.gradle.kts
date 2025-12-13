plugins {
  id("conventions.java-library")
  id("conventions.java-testing")
  id("conventions.static-analysis")
  id("conventions.coverage")
  id("conventions.publishing")
  id("conventions.min-version-test")
}

dependencies {
  api(project(":methanol"))
  api(libs.jackson.databind)
  api(libs.reactor.core)
  implementation(project(":methanol-jackson"))
  implementation(libs.reactivestreams)
}
