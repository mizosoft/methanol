plugins {
  id("conventions.java-library")
  id("conventions.static-analysis")
  id("conventions.testing")
  id("conventions.jacoco")
  alias(libs.plugins.protobuf)
  alias(libs.plugins.extraJavaModuleInfo)
}

dependencies {
  testImplementation(project(":methanol"))
  testImplementation(project(":methanol-jackson"))
  testImplementation(project(":methanol-jackson-flux"))
  testImplementation(project(":methanol-protobuf"))
  testImplementation(project(":methanol-jaxb"))
  testImplementation(project(":methanol-brotli"))
  testImplementation(project(":methanol-testing"))
  testImplementation(libs.reactor.core)
  testImplementation(libs.mockwebserver)
  testImplementation(libs.brotli.dec)
  testImplementation(libs.reactivestreams)
  testImplementation(libs.reactivestreams.examples)
  testImplementation(libs.moxy)
}

tasks.test {
  // Add test resources to the module path. This is not done automatically for some reason.
  jvmArgs(
    "--patch-module", "methanol.blackbox=${sourceSets.test.map { it.output.resourcesDir!! }.get()}"
  )
}

extraJavaModuleInfo {
  failOnMissingModuleInfo.set(false)
  automaticModule(libs.moxy.get().module.toString(), "org.eclipse.persistence.moxy")
}

protobuf {
  protoc {
    artifact = libs.protobuf.compiler.get().toString()
  }
}