package conventions

import extensions.applyJUnit5Conventions
import extensions.applyLoggingAndReportingConventions
import extensions.libs

plugins {
  `java-library`
}

dependencies {
  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testImplementation(project(":methanol-testing"))
  testImplementation(libs.assertj)
  testImplementation(libs.awaitility)
  testImplementation(libs.hamcrest)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
  applyJUnit5Conventions()
}

tasks.withType<Test> {
  applyLoggingAndReportingConventions()
}
