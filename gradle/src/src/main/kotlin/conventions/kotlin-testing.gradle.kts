package conventions

import extensions.applyJUnit5Conventions
import extensions.applyLoggingAndReportingConventions
import extensions.libs

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
