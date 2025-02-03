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

import extensions.Version

plugins {
  id("conventions.aggregate-coverage")
  id("conventions.aggregate-javadoc")
  id("conventions.aggregate-dokka")
  id("conventions.aggregate-testing")
  alias(libs.plugins.nexus.publish)
  alias(libs.plugins.versions)
}

allprojects {
  description = "Lightweight HTTP extensions for Java"
  group = "com.github.mizosoft.methanol"
  version = Version(
    major = 1,
    minor = 8,
    patch = 1,
    release = if (project.hasProperty("finalRelease")) Version.Release.FINAL else Version.Release.SNAPSHOT
  )

  repositories {
    mavenCentral()
  }
}

nexusPublishing {
  repositories {
    sonatype {
      username = project.findProperty("nexusUsername")?.toString()
      password = project.findProperty("nexusPassword")?.toString()
    }
  }
}
