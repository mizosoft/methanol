package conventions

import extensions.optionalJavaModuleName
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

project.optionalJavaModuleName?.let {
  // Provide compiled Kotlin classes to javac – needed for Java/Kotlin mixed sources to work.
  tasks.named<JavaCompile>("compileJava") {
    options.compilerArgumentProviders.add(CommandLineArgumentProvider {
      listOf("--patch-module", "$it=${sourceSets["main"].output.asPath}")
    })
  }

  tasks.withType<DokkaTaskPartial> {
    moduleName = it
  }
}
