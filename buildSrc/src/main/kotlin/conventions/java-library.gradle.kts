package conventions

import extensions.JAVADOC_JDK_VERSION
import extensions.javaVendor
import extensions.javaVersion
import extensions.standardOptions
import java.nio.charset.StandardCharsets

plugins {
  `java-library`
}

// Specify a tool chain matching project's javaVersion property if specified.
java {
  toolchain {
    languageVersion =
      JavaLanguageVersion.of(project.javaVersion ?: JavaVersion.current().toString())
    project.javaVendor?.let {
      vendor = JvmVendorSpec.matching(it)
    }
  }
}

tasks.compileJava {
  options.apply {
    javaModuleVersion = provider { project.version.toString() }
    release = 11

    // Suppress warnings when exporting to modules unresolvable on separate compilation.
    compilerArgs.add("-Xlint:-module")
  }
}

tasks.withType<JavaCompile> {
  options.encoding = StandardCharsets.UTF_8.name()
}

tasks.withType<Javadoc> {
  standardOptions {
    links("https://docs.oracle.com/en/java/javase/$JAVADOC_JDK_VERSION/docs/api/")
    addBooleanOption("Xdoclint:-missing", true)
  }
}
