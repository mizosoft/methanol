plugins {
  id("conventions.java-library")
  id("conventions.java-testing")
  id("conventions.static-analysis")
  id("conventions.coverage")
  id("conventions.publishing")
}

dependencies {
  api(project(":methanol"))
  api(libs.jackson.databind)
  testImplementation(libs.jackson.xml)
  testImplementation(libs.jackson.protobuf)
  testImplementation(libs.jackson.avro)
  testImplementation(libs.bson4jackson)
}
