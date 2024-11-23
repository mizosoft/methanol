package extensions

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.add
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.the

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
    } ?: throw IllegalStateException("No module-info.java in " + this@findModuleName)
  }

val Project.javaModuleName: String
  get() = extensions.findByName(MODULE_NAME_EXTENSIONS_NAME) as String?
    ?: findModuleName().also {
      extensions.add(String::class, MODULE_NAME_EXTENSIONS_NAME, it)
    }

fun Project.projectOrNull(name: String) = findProject(name)

val Project.isIncludedInCoverageReport
  get() = project in setOf(
    project(":methanol"),
    project(":methanol-blackbox"),
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
    projectOrNull(":quarkus-native-test"), // Optionally included in build.
    projectOrNull(":native-image-test"), // Optionally included in build.
    project(":spring-boot-test"),
  )

val Project.isIncludedInAggregateJavadoc
  get() = project in setOf(
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

val Project.isIncludedInAggregateDokka
  get() = project in setOf(
    project(":methanol-kotlin"),
    project(":methanol-moshi"),
  )

val Project.artifactId
  get() = project.name

val Project.libs
  get() = the<LibrariesForLibs>()

val Project.javaVersion: String?
  get() = project.findProperty("javaVersion")?.toString()

val Project.javaVendor: String?
  get() = project.findProperty("javaVendor")?.toString()

val Project.enableErrorprone: Boolean
  get() = project.hasProperty("enableErrorprone")

val Project.enableCheckerframework: Boolean
  get() = project.hasProperty("enableCheckerframework")
