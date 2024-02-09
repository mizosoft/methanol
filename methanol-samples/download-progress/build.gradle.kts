plugins {
  application
  id("conventions.java-library")
  id("conventions.static-analysis")
  alias(libs.plugins.javafx)
}

application {
  mainModule = "methanol.samples.progress.download"
  mainClass = "com.github.mizosoft.methanol.samples.progress.download.DownloadProgress"
}

javafx {
  version = libs.versions.javafx.platform.get()
  modules("javafx.controls")
}

dependencies {
  implementation(project(":methanol"))
}
