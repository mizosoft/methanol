package conventions

import extensions.*

plugins {
  `java-library`
}

val aggregateJavadoc by tasks.registering(Javadoc::class) {
  group = "Documentation"
  description = "Generates an aggregate Javadoc for published modules."

  title = "Methanol $version API"

  setDestinationDir(layout.buildDirectory.get().file("docs/aggregateJavadoc").asFile)

  standardOptions {
    links("https://docs.oracle.com/en/java/javase/$JAVADOC_JDK_VERSION/docs/api/")
    addBooleanOption("Xdoclint:-missing", true)
  }

  extensions.add(
    "moduleSourcePath",
    standardOptions.addMultilineStringsOption("-module-source-path")
  )

  // --module-source-path javadoc option is only supported on Java 12+.
  doFirst {
    if (javadocTool.get().metadata.languageVersion <= JavaLanguageVersion.of(12)) {
      throw GradleException("aggregateJavadoc uses tool options that are only available in Java 12 or higher.")
    }
  }

  // Expose the list of packages for each module.
  doLast {
    val packagesPerModule = mutableMapOf<String, MutableList<String>>()
    destinationDir!!.resolve("element-list").bufferedReader().use { reader ->
      var currentModule = ""
      while (true) {
        var line = reader.readLine() ?: break
        if (line.startsWith("module:")) {
          currentModule = line.substring("module:".length)
          packagesPerModule[currentModule] = mutableListOf<String>()
        } else {
          packagesPerModule[currentModule]!!.add(line)
        }
      }
    }

    packagesPerModule.forEach { module, packages ->
      destinationDir!!.resolve("$module/package-list").bufferedWriter().use { writer ->
        packages.forEach { writer.write(it + System.lineSeparator()) }
      }
    }
  }
}

subprojects.filter { it.isIncludedInAggregateJavadoc }
  .forEach { documentedProject ->
    val sourceSets: SourceSetContainer by documentedProject.extensions
    aggregateJavadoc {
      source(sourceSets["main"].allJava)
      classpath {
        from(sourceSets["main"].compileClasspath)
      }

      val moduleSourcePath: JavadocOptionFileOption<MutableList<String>> by extensions
      moduleSourcePath.value.add(
        "${documentedProject.javaModuleName}=${documentedProject.file("src/main/java")}"
      )
    }
  }
