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
  get() = project !in setOf(
    project(":methanol-testing"),
    project(":methanol-benchmarks"),
    project(":methanol-samples"),
    project(":methanol-samples:crawler"),
    project(":methanol-samples:download-progress"),
    project(":methanol-samples:upload-progress"),
    project(":methanol-blackbox"),
    project(":spring-boot-test"),
    projectOrNull(":methanol-brotli:brotli-jni") // Optionally included.
  )

val Project.isIncludedInAggregateJavadoc
  get() = project !in setOf(
    project(":methanol-benchmarks"),
    project(":methanol-samples"),
    project(":methanol-samples:crawler"),
    project(":methanol-samples:download-progress"),
    project(":methanol-samples:upload-progress"),
    project(":methanol-blackbox"),
    project(":spring-boot-test"),
    projectOrNull(":methanol-brotli:brotli-jni") // Optionally included.
  )

val Project.artifactId
  get() = project.name

val Project.libs get() = the<LibrariesForLibs>()
