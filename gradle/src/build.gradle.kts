plugins {
  `kotlin-dsl`
  alias(libs.plugins.versions)
}

repositories {
  mavenCentral()
  gradlePluginPortal()
}

dependencies {
  // See https://github.com/gradle/gradle/issues/15383.
  implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))

  implementation(libs.javaparser)
  implementation(plugin(libs.plugins.checkerframework))
  implementation(plugin(libs.plugins.errorprone))
  implementation(plugin(libs.plugins.nullaway))
  implementation(plugin(libs.plugins.kotlin.jvm))
  implementation(plugin(libs.plugins.dokka))
  implementation(plugin(libs.plugins.coveralls.jacoco))
}

fun plugin(plugin: Provider<PluginDependency>): Provider<String> {
  return plugin.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }
}
