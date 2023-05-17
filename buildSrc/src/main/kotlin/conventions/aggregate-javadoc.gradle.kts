package conventions

import extensions.classpath
import extensions.isIncludedInAggregateJavadoc
import extensions.javaModuleName
import extensions.standardOptions
import org.gradle.api.plugins.JavaLibraryPlugin

/** This task generates an aggregate Javadoc for all published modules (except benchmarks). */
val aggregateJavadoc by tasks.registering(Javadoc::class) {
  title = "Methanol $version API"
  setDestinationDir(file("${rootProject.buildDir}/docs/javadoc"))

  // Exclude internal APIs.
  exclude("**/internal**")

  val moduleSourcePath = standardOptions.addMultilineStringsOption("-module-source-path")
  subprojects.filter { it.isIncludedInAggregateJavadoc }
    .forEach { documentedProject ->
      documentedProject.plugins.withType<JavaLibraryPlugin> {
        val sourceSets: SourceSetContainer by documentedProject.extensions
        source(sourceSets["main"].allJava)
        classpath {
          from(sourceSets["main"].compileClasspath)
        }

        standardOptions {
          links("https://docs.oracle.com/en/java/javase/17/docs/api/")
          addBooleanOption("Xdoclint:-missing", true)

          moduleSourcePath.value.add(
            "${documentedProject.javaModuleName}=${documentedProject.file("src/main/java")}"
          )
        }
      }
    }
}
