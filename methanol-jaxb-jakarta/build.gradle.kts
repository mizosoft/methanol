plugins {
  id("conventions.java-library")
  id("conventions.static-analysis")
  id("conventions.testing")
  id("conventions.coverage")
  id("conventions.publishing")
}

dependencies {
  api(project(":methanol"))
  api(libs.jaxb.jakarta.api)

  testImplementation(project(":methanol-testing"))
  testImplementation(libs.jaxb.jakarta.impl)
}
