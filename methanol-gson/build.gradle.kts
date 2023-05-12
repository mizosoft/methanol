plugins {
  id("conventions.java-library")
  id("conventions.static-analysis")
  id("conventions.testing")
  id("conventions.jacoco")
  id("conventions.publishing")
}

dependencies {
  api(project(":methanol"))
  api(libs.gson)

  testImplementation(project(":methanol-testing"))
}
