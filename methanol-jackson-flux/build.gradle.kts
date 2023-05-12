plugins {
  id("conventions.java-library")
  id("conventions.static-analysis")
  id("conventions.testing")
  id("conventions.jacoco")
  id("conventions.publishing")
}

dependencies {
  api(project(":methanol"))
  api(libs.jackson.databind)
  api(libs.reactor.core)
  implementation(libs.reactivestreams)
  implementation(project(":methanol-jackson"))

  testImplementation(project(":methanol-testing"))
}
