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
  api(libs.kotlinx.coroutines)
  api(libs.moshi)
  testImplementation(kotlin("test"))
  testImplementation(project(":methanol-testing"))
  testImplementation(project(":methanol-kotlin"))
  testImplementation(libs.mockwebserver)
  testImplementation(libs.assertk)
  testImplementation(libs.moshi.kotlin)
}
