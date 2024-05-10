plugins {
  id("conventions.java-library")
  id("conventions.static-analysis")
  id("conventions.testing")
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

tasks.withType<Test> {
  systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}

tasks.withType<JavaCompile> {
  // Generate metadata for reflection on method parameters
  options.compilerArgs.add("-parameters")
}

quarkus {
  buildForkOptions {
    // Always generate a native image.
    systemProperty("quarkus.package.type", "native")

    // Make ServiceLoader work.
    systemProperty("quarkus.native.auto-service-loader-registration", "true")

    systemProperty(
      "quarkus.native.additional-build-args",
      listOf(
        // For debuggability.
        "-H:+ReportExceptionStackTraces",

        // These depend on Inet4Address, which cannot be initialized in build-time. Discovered
        // through trial and error.
        "--initialize-at-run-time=io.lettuce.core.resource.DefaultClientResources",
        "--initialize-at-run-time=io.lettuce.core.resource.AddressResolverGroupProvider",
        "--initialize-at-run-time=io.lettuce.core.resource.AddressResolverGroupProvider\$DefaultDnsAddressResolverGroupWrapper",

        // Okhttp accesses internal GraalVM API.
        "-J--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.configure=ALL-UNNAMED"
      ).joinToString(",")
    )
  }
}
