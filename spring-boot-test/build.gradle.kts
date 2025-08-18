plugins {
  id("conventions.java-library")
  id("conventions.java-testing")
  id("conventions.static-analysis")
  id("conventions.coverage")
  alias(libs.plugins.spring.boot)
}

apply(plugin = libs.plugins.spring.dependency.management.get().pluginId)

dependencies {
  implementation(project(":methanol"))
  implementation(project(":methanol-jackson"))
  implementation(project(":methanol-testing"))
  implementation(libs.mockwebserver)

  // Must explicitly declare okhttp dep to avoid a weird NoClassDefFoundError due to an old okhttp
  // version spring-boot puts in the boot jar.
  implementation(libs.okhttp)
  implementation(libs.spring.boot.starter.web)
  implementation(libs.autoservice.annotations)
  annotationProcessor(libs.autoservice.annprocess)
}

// Make sure we only run this in Java 17+ setups.

tasks.withType<JavaCompile>().configureEach {
  onlyIf {
    java.toolchain.languageVersion.get().asInt() >= 17
  }
  options.release = 17 // Override to look for the correct dependencies.
}

tasks.withType<Test>().configureEach {
  onlyIf {
    java.toolchain.languageVersion.get().asInt() >= 17
  }
}

tasks.bootJar {
  onlyIf {
    java.toolchain.languageVersion.get().asInt() >= 17
  }
}

tasks.test {
  dependsOn(tasks.bootJar)
  doFirst {
    systemProperty(
      "com.github.mizosoft.methanol.springboot.test.bootJarPath",
      tasks.bootJar.flatMap { it.archiveFile }.get()
    )
  }
}
