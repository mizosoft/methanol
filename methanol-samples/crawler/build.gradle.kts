plugins {
  id("conventions.java-library")
  id("conventions.static-analysis")
  application
}

dependencies {
  implementation(project(":methanol"))
  implementation(libs.jsoup)
}

application {
  mainModule = "methanol.samples.crawler"
  mainClass = "com.github.mizosoft.methanol.samples.crawler.Crawler"
}
