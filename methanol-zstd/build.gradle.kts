plugins {
  id("conventions.java-library")
  id("conventions.java-testing")
  id("conventions.static-analysis")
  id("conventions.coverage")
  id("conventions.publishing")
}

dependencies {
  implementation(project(":methanol"))
  implementation(libs.zstd.jni)
  testImplementation(project(":methanol-testing"))
}
