/*
 * Copyright (c) 2025 Moataz Hussein
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

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.add
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.the

const val JAVADOC_JDK_VERSION = 11
const val JAVADOC_URL = "https://mizosoft.github.io/methanol/api/latest"

private const val MODULE_NAME_EXTENSIONS_NAME = "javaModuleName"

private fun Project.findModuleName() =
  project.the<SourceSetContainer>()["main"].run {
    allJava.filter { it.name == "module-info.java" }.firstOrNull()?.run {
      JavaParser().run {
        parserConfiguration.languageLevel = ParserConfiguration.LanguageLevel.JAVA_11
        parse(toPath()).result
          .flatMap { it.module }
          .map { it.name.toString() }
          .orElseThrow { IllegalStateException("Couldn't parse module-info.java") }
      }
    }
  }

val Project.optionalJavaModuleName: String?
  get() = extensions.findByName(MODULE_NAME_EXTENSIONS_NAME) as String?
    ?: findModuleName()?.also {
      extensions.add(String::class, MODULE_NAME_EXTENSIONS_NAME, it)
    }

val Project.javaModuleName: String
  get() = optionalJavaModuleName ?: throw IllegalStateException("Could not find Java module name for $name")

// These projects are included in coverage numbers.
val Project.coveredProjects
  get() = setOf(
    project(":methanol"),
    project(":methanol-brotli"),
    project(":methanol-gson"),
    project(":methanol-jackson"),
    project(":methanol-jackson-flux"),
    project(":methanol-jaxb"),
    project(":methanol-jaxb-jakarta"),
    project(":methanol-kotlin"),
    project(":methanol-moshi"),
    project(":methanol-protobuf"),
    project(":methanol-redis"),
  )

// These projects have tests that contribute to counting coverage.
val Project.testProjects
  get() = coveredProjects + setOf(
    project(":methanol-blackbox"),
    project(":spring-boot-test"),
    project("methanol-kotlin"),
    projectOrNull(":native-test"),
    projectOrNull(":quarkus-native-test"),
  ).filter { it != null }.map { it!! }

fun Project.projectOrNull(name: String): Project? = findProject(name)

val Project.javadocDocumentedProjects
  get() = setOf(
    project(":methanol"),
    project(":methanol-brotli"),
    project(":methanol-gson"),
    project(":methanol-jackson"),
    project(":methanol-jackson-flux"),
    project(":methanol-jaxb"),
    project(":methanol-jaxb-jakarta"),
    project(":methanol-kotlin"),
    project(":methanol-moshi"),
    project(":methanol-protobuf"),
    project(":methanol-redis"),
    project(":methanol-testing"),
  )

val Project.dokkaDocumentedProjects
  get() = setOf(
    project(":methanol-kotlin"),
    project(":methanol-moshi"),
  )

val Project.artifactId
  get() = project.name

val Project.libs
  get() = the<LibrariesForLibs>()

val Project.javaVersion
  get() = project.findProperty("javaVersion")?.toString()

val Project.javaVendor
  get() = project.findProperty("javaVendor")?.toString()

val Project.javaNativeImageCapable
  get() = project.findProperty("javaNativeImageCapable")?.toString()?.toBoolean() ?: false

val Project.enableErrorprone
  get() = project.hasProperty("enableErrorprone")

val Project.enableCheckerframework
  get() = project.hasProperty("enableCheckerframework")
