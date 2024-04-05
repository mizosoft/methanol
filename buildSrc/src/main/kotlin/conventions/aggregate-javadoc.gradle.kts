package conventions

import extensions.classpath
import extensions.isIncludedInAggregateJavadoc
import extensions.javaModuleName
import extensions.standardOptions
import org.gradle.api.plugins.JavaLibraryPlugin

plugins {
  `java-library`
}

val aggregateJavadoc by tasks.registering(Javadoc::class) {
  title = "Methanol $version API"
  description = "Generates an aggregate Javadoc for all published modules (except benchmarks)."

  setDestinationDir(layout.buildDirectory.get().file("docs/aggregateJavadoc").asFile)

  standardOptions {
    links("https://docs.oracle.com/en/java/javase/17/docs/api/")
    addBooleanOption("Xdoclint:-missing", true)
  }

  extensions.add(
    "moduleSourcePath",
    standardOptions.addMultilineStringsOption("-module-source-path")
  )

  // --module-source-path javadoc option is only supported on Java 12+.
  onlyIf("aggregateJavadoc uses tool options that are available in Java 12 or higher.") {
    java.toolchain.languageVersion.get() >= JavaLanguageVersion.of(12)
  }
}

subprojects.filter { it.isIncludedInAggregateJavadoc }
  .forEach { documentedProject ->
    documentedProject.plugins.withType<JavaLibraryPlugin> {
      val sourceSets: SourceSetContainer by documentedProject.extensions
      aggregateJavadoc {
        source(sourceSets["main"].allJava)
        classpath {
          from(sourceSets["main"].compileClasspath)
        }

        val moduleSourcePath: JavadocOptionFileOption<MutableList<String>> by extensions
        moduleSourcePath.value.add(
          "${documentedProject.javaModuleName}=${documentedProject.file("src/main/java")}"
        )
      }
    }
  }
