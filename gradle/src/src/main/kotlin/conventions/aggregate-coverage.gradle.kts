package conventions

import extensions.isIncludedInCoverageReport

plugins {
  id("conventions.coverage")
}

val jacocoAggregateReport by tasks.registering(JacocoReport::class)

subprojects.filter { it.isIncludedInCoverageReport }
  .forEach { coveredProject ->
    coveredProject.plugins.withType<JavaLibraryPlugin> {
      val sourceSets: SourceSetContainer by coveredProject.extensions
      jacocoAggregateReport {
        sourceSets(sourceSets["main"])
        mustRunAfter(coveredProject.tasks.withType<Test>())
      }

      // Gather executionData when JacocoPlugin is applied.
      coveredProject.plugins.withType<JacocoPlugin> {
        jacocoAggregateReport {
          executionData(
            coveredProject.tasks.matching {
              it.extensions.findByType(JacocoTaskExtension::class) != null
            })
        }
      }
    }
  }
