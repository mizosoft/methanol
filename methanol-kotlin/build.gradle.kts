plugins {
  id("conventions.kotlin-library")
  id("conventions.testing")
  id("conventions.coverage")
  id("conventions.publishing")
  kotlin("plugin.serialization") version libs.versions.kotlin.serialization.plugin.get()
}

repositories {
  mavenCentral()
}

dependencies {
  api(project(":methanol"))
  api(libs.kotlinx.coroutines)
  implementation(libs.kotlinx.serialization)
  testImplementation(kotlin("test"))
  testImplementation(project(":methanol-testing"))
  testImplementation(libs.mockwebserver)
  testImplementation(libs.assertk)
  testImplementation(libs.kotlinx.serialization.json)
  testImplementation(libs.kotlinx.serialization.protobuf)
}
