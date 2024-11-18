package conventions

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.jvm.JvmTargetValidationMode
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  id("org.jetbrains.kotlin.jvm")
}

tasks.withType<KotlinCompile>().configureEach {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_11)

    // We don't include Java sources in kotlin projects so perceived target incompatibilty causes no
    // issues.
    jvmTargetValidationMode.set(JvmTargetValidationMode.IGNORE)
  }
}
