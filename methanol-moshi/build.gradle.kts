plugins {
  id("conventions.kotlin-library")
  id("conventions.kotlin-testing")
  id("conventions.coverage")
  id("conventions.publishing")
}

repositories {
  mavenCentral()
}

dependencies {
  api(project(":methanol"))
  api(libs.moshi)
  testImplementation(kotlin("test"))
  testImplementation(project(":methanol-testing"))
  testImplementation(libs.moshi.kotlin)
}
