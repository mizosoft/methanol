package extensions

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.kotlin.dsl.assign

val Javadoc.standardOptions
  get() = options as StandardJavadocDocletOptions

fun Javadoc.standardOptions(block: StandardJavadocDocletOptions.() -> Unit) {
  standardOptions.apply(block)
}

fun Javadoc.classpath(block: ConfigurableFileCollection.() -> Unit) {
  (classpath as ConfigurableFileCollection).block()
}

fun Test.applyJUnit5Conventions() {
  useJUnitPlatform()

  // For consistency, the default timeout must be in sync with TestUtils.TIMEOUT_SECONDS.
  systemProperty("junit.jupiter.execution.timeout.default", "4s")
  systemProperty("junit.jupiter.execution.timeout.mode", "disabled_on_debug")
}

fun Test.applyLoggingAndReportingConventions() {
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
    junitXml.apply {
      required = true
      isOutputPerTestCase = true
    }
  }
}
