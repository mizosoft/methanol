package conventions

import extensions.*
import org.gradle.api.tasks.testing.Test

plugins {
  `java-library`
}

// This plugin creates a minVersionTest task using minimum versions from version ranges of
// integration module dependencies. This ensures that the code compiles and tests pass with
// the minimum declared versions, preventing accidental use of APIs only available in newer
// versions. Apply this plugin only to integration modules that rely on dependencies that
// use version ranges.

val sourceSetName = "minVersionTest"

sourceSets {
  create(sourceSetName) {
    // Use the same source files as the test source set.
    java.setSrcDirs(sourceSets.test.get().java.srcDirs)
    resources.setSrcDirs(sourceSets.test.get().resources.srcDirs)

    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
  }
}

// Make all minVersionTest configurations extend from corresponding test configurations.
configurations.matching { it.name.startsWith(sourceSetName) }.configureEach {
  configurations.findByName(name.replaceFirst(sourceSetName, "test"))?.let { testConfig ->
    extendsFrom(testConfig)
  }
}

// Force minimum versions from version ranges after dependencies are configured.
afterEvaluate {
  configurations.matching { it.name.startsWith(sourceSetName) }.configureEach {
    configurations.findByName(name.replaceFirst(sourceSetName, "test"))?.let { testConfig ->
      forceMinimumVersions(testConfig)
    }
  }
}

val minVersionTest by tasks.registering(Test::class) {
  description = "Runs tests with minimum dependency versions from version ranges"
  group = "verification"

  testClassesDirs = sourceSets[sourceSetName].output.classesDirs
  classpath = sourceSets[sourceSetName].runtimeClasspath

  applyJUnit5Conventions()
  shouldRunAfter(tasks.test)
}
