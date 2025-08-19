package conventions

import extensions.coveredProjects
import extensions.testProjects

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

coveredProjects
  .forEach { project ->
    project.plugins.withType<JavaLibraryPlugin> {
      val sourceSets: SourceSetContainer by project.extensions
      jacocoAggregateReport {
        sourceSets(sourceSets["main"])
      }

      coverallsJacoco {
        reportSourceSets += sourceSets["main"].allSource.srcDirs
      }
    }
  }

testProjects.forEach { project ->
  jacocoAggregateReport {
    mustRunAfter(project.tasks.withType<Test>())
  }

  // Gather executionData when JacocoPlugin is applied.
  project.plugins.withType<JacocoPlugin> {
    jacocoAggregateReport {
      executionData(project.tasks.matching {
        it.extensions.findByType(JacocoTaskExtension::class) != null
      })
    }
  }
}
