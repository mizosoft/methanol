plugins {
  id("conventions.java-library")
  id("conventions.java-testing")
  id("conventions.static-analysis")
  id("conventions.coverage")
  id("conventions.publishing")
}

dependencies {
  implementation(project(":methanol"))
  testImplementation(project(":methanol-testing"))
  testImplementation(libs.brotli.dec)
}

val baseJar by tasks.registering(Jar::class) {
  archiveClassifier = "base"
  from(sourceSets.main.get().output) {
    exclude("native/**")
  }
}

val supportedPlatforms = setOf(
  "linux-x86-64",
  "linux-aarch64",
  "macos-x86-64",
  "macos-aarch64",
  "windows-x86-64",
  "windows-aarch64",
)

val nativeResourcesDir = layout.projectDirectory.dir("src/main/resources/native")

val nativeJarTasks = supportedPlatforms.map { platform ->
  tasks.register<Jar>("${platform.replace("-", "").replaceFirstChar { it.uppercase() }}Jar") {
    archiveClassifier = platform
    from(sourceSets.main.get().output) {
      exclude("native/**")
    }
    from(nativeResourcesDir.dir(platform)) {
      into("native/$platform")
    }
  }
}

publishing {
  publications {
    named<MavenPublication>("maven") {
      artifact(baseJar) {
        classifier = baseJar.flatMap { it.archiveClassifier }.get()
      }

      nativeJarTasks.forEach { task ->
        artifact(task) {
          classifier = task.flatMap { it.archiveClassifier }.get()
        }
      }
    }
  }
}
