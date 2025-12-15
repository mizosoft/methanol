import extensions.extractMinVersion

plugins {
  id("conventions.java-library")
  id("conventions.java-testing")
  id("conventions.static-analysis")
  id("conventions.coverage")
  id("conventions.publishing")
  id("conventions.min-version-test")
  alias(libs.plugins.protobuf)
}

dependencies {
  api(project(":methanol"))
  api(libs.protobuf.java)
}

protobuf {
  protoc {
    // Use the minimum version from the version range to ensure generated code matches runtime even in minVersionTest.
    val compilerDep = libs.protobuf.compiler.get()
    artifact = "${compilerDep.module}:${extractMinVersion(compilerDep.versionConstraint.requiredVersion)}"
  }
}

// Configure minVersionTest to use the same proto sources as test.
sourceSets {
  named("minVersionTest") {
    proto {
      srcDir("src/test/proto")
    }
  }
}
