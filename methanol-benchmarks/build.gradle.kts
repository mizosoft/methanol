plugins {
  id("conventions.java-library")
  id("conventions.publishing")
  alias(libs.plugins.shadow)
}

dependencies {
  annotationProcessor(libs.jmh.annprocess)
  implementation(libs.jmh.core)

  implementation(project(":methanol"))
  implementation(project(":methanol-jackson"))
  implementation(project(":methanol-brotli"))
  implementation(project(":methanol-testing"))
  implementation(libs.mockwebserver)
  implementation(libs.brotli.dec)
  implementation(libs.checkerframework.qual)
  implementation(libs.errorprone.annotations)
}

tasks.shadowJar {
  archiveClassifier.set("all")
  mergeServiceFiles()
}

tasks.register<JavaExec>("jmh") {
  classpath = files(tasks.shadowJar)
  args("-foe", "true")
  var additionalArgs = System.getProperty("jmhArgs")
  if (additionalArgs != null) {
    if (additionalArgs.length > 2
      && listOf("\"", "\'").any(additionalArgs::startsWith)
      && listOf("\"", "\'").any(additionalArgs::endsWith)
    ) {
      additionalArgs = additionalArgs.substring(1, additionalArgs.length - 1)
    }
    args(additionalArgs)
  }
}
