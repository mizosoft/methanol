package conventions

import extensions.*
import java.nio.charset.StandardCharsets

plugins {
  `java-library`
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
  options.apply {
    javaModuleVersion = provider { project.version.toString() }

    // Suppress warnings when exporting to modules unresolvable on separate compilation.
    compilerArgs.add("-Xlint:-module")

    release = 11
    encoding = StandardCharsets.UTF_8.name()
  }
}

tasks.withType<Javadoc> {
  standardOptions {
    links("https://docs.oracle.com/en/java/javase/$JAVADOC_JDK_VERSION/docs/api/")
    addBooleanOption("Xdoclint:-missing", true)
  }
}
