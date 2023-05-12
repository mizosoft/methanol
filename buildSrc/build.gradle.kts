plugins {
  `kotlin-dsl`
}

repositories {
  mavenCentral()
  gradlePluginPortal()
}

dependencies {
  // See https://github.com/gradle/gradle/issues/15383.
  implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))

  implementation(libs.checkerframework.plugin)
  implementation(libs.errorprone.plugin)
  implementation(libs.nullaway.plugin)
  implementation(libs.javaparser)
  implementation(libs.coveralls)
}
