package conventions

import extensions.enableCheckerframework
import extensions.enableErrorprone
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
  if (project.enableCheckerframework) {
    checkers = listOf(
      "org.checkerframework.checker.nullness.NullnessChecker"
    )
  }
}

tasks.withType<JavaCompile> {
  options.errorprone {
    isEnabled = project.enableErrorprone
    nullaway {
      annotatedPackages.add("com.github.mizosoft.methanol")
      checkOptionalEmptiness = true
      suggestSuppressions = true
      isAssertsEnabled = true
      handleTestAssertionLibraries = true
    }
  }
}

tasks.compileTestJava {
  options.errorprone.isEnabled = false
}
