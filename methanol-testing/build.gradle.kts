import extensions.libs

plugins {
  id("conventions.java-library")
  id("conventions.static-analysis")
  id("conventions.publishing")
}

dependencies {
  api(project(":methanol"))
  api(platform(libs.junit.bom))
  api(libs.junit.params)
  api(libs.assertj)
  implementation(project(":methanol-redis"))
  implementation(libs.mockwebserver)
  implementation(libs.okhttp.tls)
}
