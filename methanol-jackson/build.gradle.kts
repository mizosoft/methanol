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

  testImplementation(project(":methanol-testing"))
  testImplementation(libs.jackson.xml)
  testImplementation(libs.jackson.protobuf)
  testImplementation(libs.jackson.avro)
  testImplementation(libs.bson4jackson)
}
