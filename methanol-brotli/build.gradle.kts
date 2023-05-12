import org.gradle.nativeplatform.internal.DefaultTargetMachineFactory

plugins {
  id("conventions.java-library")
  id("conventions.static-analysis")
  id("conventions.testing")
  id("conventions.jacoco")
  id("conventions.publishing")
}

val libRoot = "native"

val targetFactory = DefaultTargetMachineFactory(objects)
val targets by extra(
  listOf(
    targetFactory.linux.x86, targetFactory.linux.x86_64,
    targetFactory.windows.x86, targetFactory.windows.x86_64,
    targetFactory.macOS.x86, targetFactory.macOS.x86_64
  )
)

val optimizedAttribute = Attribute.of("org.gradle.native.optimized", Boolean::class.javaObjectType)
val osAttribute =
  Attribute.of("org.gradle.native.operatingSystem", OperatingSystemFamily::class.java)
val archAttribute = Attribute.of("org.gradle.native.architecture", MachineArchitecture::class.java)

val jniConfigPrefix = "jniRuntime"
val getJniConfigName = { target: TargetMachine ->
  jniConfigPrefix + target.operatingSystemFamily.toString().capitalize() +
      target.architecture.toString().capitalize()
}
val getJniConfigs = { configs: ConfigurationContainer ->
  configs.filter { config -> config.name.startsWith(jniConfigPrefix) }
}

// Create consumer configuration for each target variant.
targets.forEach { target ->
  configurations.create(getJniConfigName(target)) {
    isCanBeResolved = true
    isCanBeConsumed = false
    attributes {
      attribute(optimizedAttribute, true) // Use optimized version, normally the "release" variant.
      attribute(osAttribute, target.operatingSystemFamily)
      attribute(archAttribute, target.architecture)
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.NATIVE_RUNTIME))
    }
  }
}

dependencies {
  implementation(project(":methanol"))

  if (project.findProject("brotli-jni") != null) { // Project is included in the build.
    getJniConfigs(configurations).forEach { config ->
      dependencies.add(config.name, project("brotli-jni"))
    }
  }

  testImplementation(libs.brotli.dec)
  testImplementation(project(":methanol-testing"))
}

val installBrotli by tasks.registering(Copy::class) {
  description = "Builds and copies brotli jni natives to the resources directory"
  getJniConfigs(configurations).forEach { config: Configuration ->
    if (!config.resolvedConfiguration.hasError()) {
      from(config) {
        // Attach os/arch path segments to copied artifacts.
        eachFile(closureOf<FileCopyDetails> {
          relativePath = relativePath.prepend(
            config.attributes.getAttribute(osAttribute).toString().toLowerCase(),
            config.attributes.getAttribute(archAttribute).toString().toLowerCase()
          )
        })
      }
      into("src/main/resources/$libRoot")
      includeEmptyDirs = false
    }
  }
}

// Ensure jni resources are copied first if installBrotli ran with some build task.
tasks.processResources {
  mustRunAfter(installBrotli)
}
