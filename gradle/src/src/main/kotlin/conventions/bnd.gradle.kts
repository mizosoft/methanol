package conventions

import extensions.*
import org.gradle.api.artifacts.ExternalModuleDependency

plugins {
  id("biz.aQute.bnd.builder")
}

// We utilize version ranges for dependencies of integration modules. These version ranges do not translate to OSGI
// Import-Package ranges as bnd computes these based on the final version Gradle resolves, not the declared version
// constraint. For instance if Gradle resolves 2.20.1 for range [2.10.0, 3), bnd will use [2.20, 3). We provide bnd
// with a custom classpath that uses the minimum version from the range explicitly. This way, bnd will use it for
// Import-Package metadata.

// Create a custom configuration for bnd with minimum versions from version ranges.
val bndClasspath by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
  isTransitive = true
}

// Add dependencies to bndClasspath, using minimum versions where applicable.
afterEvaluate {
  configurations {
    compileClasspath {
      allDependencies.forEach { dep ->
        when (dep) {
          is ExternalModuleDependency -> {
            val versionConstraint = dep.versionConstraint
            val requiredVersion = versionConstraint.requiredVersion
            if (requiredVersion.startsWith("[") || requiredVersion.startsWith("(")) {
              // Extract the minimum version from the range and use it for bnd's analysis.
              dependencies {
                bndClasspath("${dep.group}:${dep.name}:${extractMinVersion(requiredVersion)}")
              }
            } else {
              // Not a version range, keep the original dependency.
              dependencies {
                bndClasspath(dep)
              }
            }
          }

          else -> {
            // For any other dependency types, add as-is.
            dependencies {
              bndClasspath(dep)
            }
          }
        }
      }
    }
  }
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
