plugins {
  id("conventions.java-library")
  id("conventions.static-analysis")
  id("conventions.java-testing")
  id("conventions.coverage")
  alias(libs.plugins.quarkus)
}

dependencies {
  implementation(project(":methanol"))
  implementation(project(":methanol-jackson"))
  implementation(project(":methanol-testing"))
  implementation(libs.jackson.databind)
  implementation(libs.mockwebserver)
  implementation(libs.autoservice.annotations)
  annotationProcessor(libs.autoservice.annprocess)
  implementation(enforcedPlatform(libs.quarkus.bom))
  implementation(libs.quarkus.rest.jackson)
  testImplementation(libs.quarkus.junit5)
  testImplementation(libs.rest.assured)
}

configurations.all {
  exclude(group = "io.lettuce", module = "lettuce-core")
}

tasks.withType<Test> {
  systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}

tasks.withType<JavaCompile> {
  // Generate metadata for reflection on method parameters
  options.compilerArgs.add("-parameters")
}
