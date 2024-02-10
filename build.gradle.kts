import extensions.Version

plugins {
  id("conventions.aggregate-coverage")
  id("conventions.aggregate-javadoc")
  id("conventions.aggregate-testing")
  alias(libs.plugins.nexus.publish)
  alias(libs.plugins.versions)
}

allprojects {
  description = "Lightweight HTTP extensions for Java"
  group = "com.github.mizosoft.methanol"
  version = Version(
    major = 1,
    minor = 8,
    patch = 0,
    release = if (project.hasProperty("finalRelease")) Version.Release.FINAL else Version.Release.SNAPSHOT
  )

  repositories {
    mavenCentral()
  }
}

nexusPublishing {
  repositories {
    sonatype {
      username.set(project.findProperty("nexusUsername")?.toString())
      password.set(project.findProperty("nexusPassword")?.toString())
    }
  }
}

tasks.register("clean") {
  delete(rootProject.layout.buildDirectory)
}
