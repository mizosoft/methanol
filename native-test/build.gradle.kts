plugins {
  id("conventions.java-library")
  id("conventions.static-analysis")
  id("conventions.java-testing")
  id("conventions.coverage")
  alias(libs.plugins.graalvm)
}

dependencies {
  implementation(project(":methanol"))
  implementation(project(":methanol-jackson"))
  implementation(project(":methanol-testing"))
  implementation(libs.jackson.databind)
  implementation(libs.mockwebserver)
  implementation(libs.autoservice.annotations)
  annotationProcessor(libs.autoservice.annprocess)
}

graalvmNative {
  agent {
    binaries {
      named("main") {
        // For debuggability.
        buildArgs("-H:+ReportExceptionStackTraces")
      }

      named("test") {
        buildArgs(
          "--initialize-at-build-time=org.junit.platform",
          "--initialize-at-build-time=org.junit.jupiter"
        )
      }
    }
  }
}

// Always run native tests.
tasks.named("check") {
  dependsOn(tasks.named("nativeTest"))
}
