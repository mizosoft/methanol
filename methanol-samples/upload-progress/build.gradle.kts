plugins {
  application
  id("conventions.java-library")
  id("conventions.static-analysis")
  alias(libs.plugins.javafx)
}

application {
  mainModule = "methanol.samples.progress.upload"
  mainClass = "com.github.mizosoft.methanol.samples.progress.upload.MultipartUploadProgress"
}

javafx {
  version = libs.versions.javafx.platform.get()
  modules("javafx.controls")
}

dependencies {
  implementation(project(":methanol"))
}
