package conventions

import extensions.isIncludedInCoverageReport

plugins {
  id("conventions.coverage")
  id("com.github.nbaztec.coveralls-jacoco")
}

val jacocoAggregateReport by tasks.registering(JacocoReport::class) {
  group = "Coverage reports"
  description = "Generates an aggregate report for all subprojects"
  reports {
    xml.required = true
    html.required = true
  }
}

tasks.named("coverallsJacoco") {
  dependsOn(jacocoAggregateReport)
  onlyIf { System.getenv().containsKey("GITHUB_ACTIONS") }
  inputs.files(jacocoAggregateReport.map { it.outputs.files })
}

coverallsJacoco {
  reportPath = layout.buildDirectory.file(
    "reports/jacoco/jacocoAggregateReport/jacocoAggregateReport.xml"
  ).get().asFile.path
}

subprojects.filter { it.isIncludedInCoverageReport }
  .forEach { coveredProject ->
    coveredProject.plugins.withType<JavaLibraryPlugin> {
      val sourceSets: SourceSetContainer by coveredProject.extensions
      jacocoAggregateReport {
        sourceSets(sourceSets["main"])
        mustRunAfter(coveredProject.tasks.withType<Test>())
      }

      coverallsJacoco {
        reportSourceSets += sourceSets["main"].allSource.srcDirs
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
