package conventions

import extensions.libs
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
  `java-library`
}

dependencies {
  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.assertj)
  testImplementation(libs.awaitility)
  testImplementation(libs.hamcrest)
}

tasks.test {
  useJUnitPlatform()
}

tasks.withType<Test> {
  testLogging {
    events = setOf(TestLogEvent.SKIPPED, TestLogEvent.FAILED)
    exceptionFormat = TestExceptionFormat.FULL
    showCauses = true
    showExceptions = true
    showStackTraces = true
    showStandardStreams = true
  }

  reports {
    html.required.set(true)
    junitXml.required.set(true)
    junitXml.isOutputPerTestCase = true
  }
}
