plugins {
  id("conventions.java-library")
  id("conventions.java-testing")
  id("conventions.static-analysis")
  id("conventions.coverage")
  alias(libs.plugins.spring.boot)
  alias(libs.plugins.spring.dependency.management)
}

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

tasks.test {
  dependsOn(tasks.bootJar)
  doFirst {
    systemProperty(
      "com.github.mizosoft.methanol.springboot.test.bootJarPath",
      tasks.bootJar.flatMap { it.archiveFile }.get()
    )
  }
}
