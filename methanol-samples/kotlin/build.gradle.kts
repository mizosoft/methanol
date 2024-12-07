plugins {
  id("conventions.kotlin-library")
  kotlin("plugin.serialization") version "2.1.0"
}

dependencies {
  implementation(project(":methanol-kotlin"))
  implementation(project(":methanol-redis"))
  implementation(libs.kotlinx.serialization.json)
}
