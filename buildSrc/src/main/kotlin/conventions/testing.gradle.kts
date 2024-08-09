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
  // For consistency, the default timeout must be in sync with TestUtils.TIMEOUT_SECONDS.
  systemProperty("junit.jupiter.execution.timeout.default", "2s")
  systemProperty("junit.jupiter.execution.timeout.mode", "disabled_on_debug")
  reports {
    junitXml.apply {
      isOutputPerTestCase = true
    }
  }
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
    html.required = true
    junitXml.required = true
    junitXml.isOutputPerTestCase = true
  }
}
