/*
 * Copyright (c) 2023 Moataz Abdelnasser
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

plugins {
  id("conventions.java-library")
  id("conventions.publishing")
  alias(libs.plugins.shadow)
}

dependencies {
  annotationProcessor(libs.jmh.annprocess)
  implementation(libs.jmh.core)

  implementation(project(":methanol"))
  implementation(project(":methanol-jackson"))
  implementation(project(":methanol-brotli"))
  implementation(project(":methanol-testing"))
  implementation(libs.mockwebserver)
  implementation(libs.brotli.dec)
  implementation(libs.checkerframework.qual)
  implementation(libs.errorprone.annotations)
}

tasks.shadowJar {
  archiveClassifier.set("all")
  mergeServiceFiles()
}

tasks.register<JavaExec>("jmh") {
  classpath = files(tasks.shadowJar)
  args("-foe", "true")
  var additionalArgs = System.getProperty("jmhArgs")
  if (additionalArgs != null) {
    if (additionalArgs.length > 2
      && listOf("\"", "\'").any(additionalArgs::startsWith)
      && listOf("\"", "\'").any(additionalArgs::endsWith)
    ) {
      additionalArgs = additionalArgs.substring(1, additionalArgs.length - 1)
    }
    args(additionalArgs)
  }
}
