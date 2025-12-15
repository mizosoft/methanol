package conventions

import extensions.*

plugins {
  id("biz.aQute.bnd.builder")
}

// We utilize version ranges for dependencies of integration modules. These version ranges do not translate to OSGI
// Import-Package ranges as bnd computes these based on the final version Gradle resolves, not the declared version
// constraint. For instance if Gradle resolves 2.20.1 for range [2.10.0, 3), bnd will use [2.20, 3). We provide bnd
// with a custom classpath that uses the minimum version from each range explicitly. This way, bnd will use it for
// Import-Package metadata.

// Create a custom configuration for bnd with minimum versions from version ranges.
val bndClasspath by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
  isTransitive = true
  extendsFrom(configurations.compileClasspath.get())
}

// Force minimum versions from version ranges in compileClasspath.
afterEvaluate {
  bndClasspath.forceMinimumVersions(configurations.compileClasspath.get())
}

tasks.jar {
  bundle {
    // Set bnd's classpath to use our custom configuration with minimum versions.
    classpath.setFrom(sourceSets.main.get().output, bndClasspath)

    bnd(
      """
      -exportcontents: !*.internal*,*
      Import-Package: !org.checkerframework.*,!com.google.errorprone.*,*
      Bundle-Description: ${project.description}
    """
    )
  }
}
