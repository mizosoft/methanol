import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.nio.charset.StandardCharsets

plugins {
  id("conventions.testing")
  id("conventions.jacoco")
  id("conventions.publishing")
  kotlin("jvm") version "1.9.0"
}

kotlin {
}

repositories {
  mavenCentral()
}

dependencies {
  api(project(":methanol"))
  api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
  implementation(project(mapOf("path" to ":methanol-redis")))
}

tasks.withType<KotlinCompile>().configureEach {
  kotlinOptions {
    jvmTarget = JavaVersion.VERSION_11.toString()
    freeCompilerArgs += "-Xjvm-default=all"
  }
}

tasks.withType<JavaCompile> {
  options.encoding = StandardCharsets.UTF_8.toString()
  sourceCompatibility = JavaVersion.VERSION_11.toString()
  targetCompatibility = JavaVersion.VERSION_11.toString()
}
