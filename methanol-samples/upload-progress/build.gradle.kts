plugins {
  application
  id("conventions.java-library")
  id("conventions.static-analysis")
  alias(libs.plugins.javafx)
}

application {
  mainModule.set("methanol.samples.progress.upload")
  mainClass.set("com.github.mizosoft.methanol.samples.progress.upload.MultipartUploadProgress")
}

javafx {
  version = "20"
  modules("javafx.controls")
}

dependencies {
  implementation(project(":methanol"))
}
