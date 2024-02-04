package conventions

import extensions.*

plugins {
  jacoco
}

jacoco {
  toolVersion = maxOf(
    GradleVersion.version(toolVersion),
    GradleVersion.version(libs.versions.jacoco.get())
  ).version
}

tasks.withType<JacocoReport> {
  reports {
    xml.required = true
    html.required = true
  }
}
