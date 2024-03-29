package conventions

import extensions.classpath
import extensions.isIncludedInAggregateJavadoc
import extensions.javaModuleName
import extensions.standardOptions
import org.gradle.api.plugins.JavaLibraryPlugin

/** This task generates an aggregate Javadoc for all published modules (except benchmarks). */
val aggregateJavadoc by tasks.registering(Javadoc::class) {
  title = "Methanol $version API"
  setDestinationDir(layout.buildDirectory.get().file("docs/aggregateJavadoc").asFile)

  // Exclude internal APIs.
  exclude("**/internal**")

  standardOptions {
    links("https://docs.oracle.com/en/java/javase/11/docs/api/")
    addBooleanOption("Xdoclint:-missing", true)
  }

  extensions.add(
    "moduleSourcePath",
    standardOptions.addMultilineStringsOption("-module-source-path")
  )
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
