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
  mainModule.set("methanol.samples.crawler")
  mainClass.set("com.github.mizosoft.methanol.samples.crawler.Crawler")
}
