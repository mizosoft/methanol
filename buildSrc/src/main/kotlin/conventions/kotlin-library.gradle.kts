package conventions

import extensions.javaModuleName
import org.jetbrains.dokka.gradle.DokkaTaskPartial
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.jvm.JvmTargetValidationMode
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("org.jetbrains.dokka")
}

tasks.withType<KotlinCompile>().configureEach {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_11)

    // We don't include Java sources in Kotlin projects, so perceived target incompatibility causes
    // no issues.
    jvmTargetValidationMode.set(JvmTargetValidationMode.IGNORE)
  }
}

tasks.withType<DokkaTaskPartial> {
  try {
    moduleName = project.javaModuleName
  } catch (e: IllegalStateException) {
    project.logger.warn("Couldn't get Java module name for Kotlin project (${project.name})", e)
  }
}
