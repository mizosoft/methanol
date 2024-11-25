plugins {
  id("conventions.java-library")
  id("conventions.java-testing")
  id("conventions.static-analysis")
  id("conventions.coverage")
  id("conventions.publishing")
}

dependencies {
  api(project(":methanol"))
  api(libs.jaxb.api)
  testImplementation(project(":methanol-testing"))
  testImplementation(libs.jaxb.impl)
}
