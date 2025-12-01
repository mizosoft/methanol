package conventions

import extensions.javaVersion
import extensions.javaVendor
import extensions.javaNativeImageCapable
import java.nio.charset.StandardCharsets

plugins {
  java
}

// Specify a toolchain matching project's javaVersion property if specified.
java {
  toolchain {
    languageVersion =
      JavaLanguageVersion.of(project.javaVersion ?: JavaVersion.current().toString())
    project.javaVendor?.let {
      @Suppress("UnstableApiUsage")
      vendor = JvmVendorSpec.of(it)
    }
    nativeImageCapable = project.javaNativeImageCapable
  }
}

tasks.withType<JavaCompile> {
  options.encoding = StandardCharsets.UTF_8.name()
}
