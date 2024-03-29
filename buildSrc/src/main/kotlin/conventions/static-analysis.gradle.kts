package conventions

import extensions.libs
import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.nullaway

plugins {
  `java-library`
  id("org.checkerframework")
  id("net.ltgt.errorprone")
  id("net.ltgt.nullaway")
}

dependencies {
  compileOnly(libs.checkerframework.qual)
  compileOnly(libs.errorprone.annotations)
  checkerFramework(libs.checkerframework)
  errorprone(libs.errorprone)
  errorprone(libs.nullaway)
}

checkerFramework {
  excludeTests = true
  if (project.hasProperty("enableCheckerframework")) {
    checkers = listOf(
      "org.checkerframework.checker.nullness.NullnessChecker"
    )
  }
}

tasks.withType<JavaCompile> {
  options.errorprone.isEnabled = project.hasProperty("enableErrorprone")

  options.errorprone {
    nullaway {
      annotatedPackages.add("com.github.mizosoft.methanol")
      excludedFieldAnnotations =
        listOf("org.checkerframework.checker.nullness.qual.MonotonicNonNull")
    }
  }
}

tasks.compileTestJava {
  options.errorprone.isEnabled = false
}
