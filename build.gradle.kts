plugins {
  id("conventions.aggregate-coverage")
  id("conventions.aggregate-javadoc")
  alias(libs.plugins.nexus.publish)
  alias(libs.plugins.versions)
}

allprojects {
  description = "Lightweight HTTP extensions for Java"
  group = "com.github.mizosoft.methanol"
  version = "1.8.0-SNAPSHOT"

  repositories {
    mavenCentral()
  }
}

nexusPublishing {
  this.repositories {
    sonatype {
      username.set(project.findProperty("nexusUsername")?.toString())
      password.set(project.findProperty("nexusPassword")?.toString())
    }
  }
}

//tasks.register("clean") {
//  delete(rootProject.buildDir)
//}
