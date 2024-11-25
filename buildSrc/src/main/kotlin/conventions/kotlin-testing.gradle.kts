package conventions

import extensions.applyJUnit5Conventions
import extensions.applyLoggingAndReportingConventions
import extensions.libs
import gradle.kotlin.dsl.accessors._e054d9723d982fdb55b1e388b8ab0cbf.testImplementation

plugins {
  id("org.jetbrains.kotlin.jvm")
}

dependencies {
  testImplementation(kotlin("test"))
  testImplementation(project(":methanol-testing"))
  testImplementation(libs.assertk)
}

tasks.test {
  applyJUnit5Conventions()
}

tasks.withType<Test> {
  applyLoggingAndReportingConventions()
}
