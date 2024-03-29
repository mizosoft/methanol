package conventions

import extensions.standardOptions
import java.nio.charset.StandardCharsets

plugins {
  `java-library`
}

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

tasks.compileJava {
  // Suppress warnings when exporting to modules unresolvable on separate compilation.
  options.compilerArgs.add("-Xlint:-module")
}

tasks.withType<JavaCompile> {
  options.encoding = StandardCharsets.UTF_8.name()
  options.javaModuleVersion = provider { project.version.toString() }
}

tasks.withType<Javadoc> {
  standardOptions {
    links("https://docs.oracle.com/en/java/javase/11/docs/api/")
    addBooleanOption("Xdoclint:-missing", true)
  }
}
