/*
 * Copyright (c) 2024 Moataz Hussein
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
  systemProperty("junit.jupiter.execution.timeout.default", "6s")
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
