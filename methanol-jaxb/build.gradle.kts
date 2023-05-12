plugins {
  id("conventions.java-library")
  id("conventions.static-analysis")
  id("conventions.testing")
  id("conventions.jacoco")
  id("conventions.publishing")
}

dependencies {
  api(project(":methanol"))
  api(libs.jaxb.api)

  testImplementation(project(":methanol-testing"))
  testImplementation(libs.moxy)
}
