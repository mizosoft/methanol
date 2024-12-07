plugins {
  id("conventions.kotlin-library")
  kotlin("plugin.serialization") version "2.1.0"
}

dependencies {
  implementation(project(":methanol-kotlin"))
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
