plugins {
  id("conventions.java-library")
  id("conventions.static-analysis")
  id("conventions.testing")
  id("conventions.coverage")
  id("conventions.publishing")
}

dependencies {
  api(project(":methanol"))
  implementation(project(":methanol-redis"))
  implementation(platform(libs.junit.bom))
  implementation(libs.okhttp.tls)
  implementation(libs.assertj)
  implementation(libs.junit.params)
  implementation(libs.mockwebserver)
}
