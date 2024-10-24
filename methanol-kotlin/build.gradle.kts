plugins {
  id("conventions.kotlin-library")
  id("conventions.testing")
  id("conventions.coverage")
  id("conventions.publishing")
}

repositories {
  mavenCentral()
}

dependencies {
  api(project(":methanol"))
  api(libs.kotlin.coroutines)
  testImplementation(kotlin("test"))
  testImplementation(project(":methanol-testing"))
  testImplementation(libs.mockwebserver)
  testImplementation(libs.assertk)
}
