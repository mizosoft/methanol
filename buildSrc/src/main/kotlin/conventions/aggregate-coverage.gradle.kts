package conventions

import extensions.isIncludedInCoverageReport
import java.io.File

plugins {
  id("conventions.jacoco")
  id("com.github.kt3k.coveralls")
}

val jacocoAggregateReport by tasks.registering(JacocoReport::class) {
  subprojects {
    tasks.matching { it.extensions.findByType(JacocoPluginExtension::class) != null }
      .configureEach {
        executionData(this@configureEach)
        dependsOn(this@configureEach)
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
    // Configure when the project's java-library plugin is applied.
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

