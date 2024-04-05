package conventions

import extensions.isIncludedInCoverageReport
import java.io.File

plugins {
  id("conventions.coverage")
  id("com.github.kt3k.coveralls")
}

val jacocoAggregateReport by tasks.registering(JacocoReport::class)

coveralls {
  jacocoReportPath = jacocoAggregateReport.flatMap { it.reports.xml.outputLocation }
}

tasks.named("coveralls") {
  dependsOn(jacocoAggregateReport)
  onlyIf { System.getenv().containsKey("GITHUB_ACTIONS") }
}

subprojects.filter { it.isIncludedInCoverageReport }
  .forEach { coveredProject ->
    coveredProject.plugins.withType<JavaLibraryPlugin> {
      val sourceSets: SourceSetContainer by coveredProject.extensions
      coveralls.sourceDirs.addAll(sourceSets["main"].allSource.srcDirs.map(File::getPath))
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
