plugins {
  id("conventions.java-testing")
  id("conventions.static-analysis")
}

// List of modules that should have OSGI metadata.
val osgiModules = listOf(
  "methanol",
  "methanol-brotli",
  "methanol-gson",
  "methanol-jackson",
  "methanol-jackson-flux",
  "methanol-jaxb",
  "methanol-jaxb-jakarta",
  "methanol-kotlin",
  "methanol-moshi",
  "methanol-protobuf",
  "methanol-redis"
)

dependencies {
  osgiModules.forEach { module ->
    testImplementation(project(":$module"))
  }

  testImplementation(libs.bnd.lib)
  testImplementation(libs.bnd.resolve)
  testImplementation(libs.bnd.repository)
  testImplementation(libs.eclipse.osgi)
  testImplementation(libs.kotlin.stdlib)
}

tasks.test {
  // Ensure JARs are built before running tests.
  dependsOn(osgiModules.map { ":$it:jar" }.toTypedArray())

  val jarFiles = osgiModules.map { module ->
    project(":$module").tasks.named<Jar>("jar").flatMap { task -> task.archiveFile }.map { prop -> prop.asFile }
  }.toTypedArray()

  doFirst {
    // Pass bundle JAR paths for validation.
    systemProperty(
      "com.github.mizosoft.methanol.osgi.test.bundlePaths",
      osgiModules.mapIndexed { index, module ->
        "$module=${jarFiles[index].get()}"
      }.joinToString(",")
    )

    systemProperty(
      "com.github.mizosoft.methanol.osgi.test.version",
      project.version.toString().replace("-", ".")
    )
  }
}

tasks.withType<JavaCompile> {
  onlyIf {
    java.toolchain.languageVersion.get().asInt() >= 17
  }
}

tasks.withType<Test> {
  onlyIf {
    java.toolchain.languageVersion.get().asInt() >= 17
  }
}
