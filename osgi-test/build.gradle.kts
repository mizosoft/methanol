plugins {
  id("conventions.java-testing")
  id("conventions.static-analysis")
}

// List of modules that should have OSGI metadata.
val java11OsgiModules = setOf(
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

// Jackson 3 modules require Java 17+
val otherOsgiModules = setOf(
  "methanol-jackson3",
  "methanol-jackson3-flux"
)

val osgiModules = if (java.toolchain.languageVersion.get().canCompileOrRun(17)) {
  java11OsgiModules + otherOsgiModules
} else {
  java11OsgiModules
}

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
