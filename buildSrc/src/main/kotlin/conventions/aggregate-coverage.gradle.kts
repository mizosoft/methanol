package conventions

import extensions.isIncludedInCoverageReport
import java.io.File

plugins {
  id("conventions.jacoco")
  id("com.github.kt3k.coveralls")
}

val jacocoAggregateReport by tasks.registering(JacocoReport::class) {
  subprojects {
    project.plugins.withType<JacocoPlugin> {
      project.tasks.matching { it.extensions.findByType(JacocoTaskExtension::class) != null }
        .configureEach {
          this@registering.executionData(this@configureEach)
          this@registering.dependsOn(this@configureEach)
        }
    }
  }
}

coveralls {
  jacocoReportPath = jacocoAggregateReport.map { it.reports.xml.outputLocation }
}

tasks.named("coveralls") {
  dependsOn(jacocoAggregateReport)
  onlyIf { System.getenv().containsKey("GITHUB_ACTIONS") }
}

subprojects.filter { it.isIncludedInCoverageReport }
  .forEach { coveredProject ->
    coveredProject.plugins.withType<JavaLibraryPlugin> {
      val sourceSets: SourceSetContainer by coveredProject.extensions
      jacocoAggregateReport {
        sourceSets(sourceSets["main"])
      }
      coveralls {
        sourceDirs.addAll(sourceSets["main"].allSource.srcDirs.map(File::getPath))
      }
    }
  }
