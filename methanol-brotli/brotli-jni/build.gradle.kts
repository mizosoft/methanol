import org.gradle.nativeplatform.internal.DefaultTargetMachineFactory

plugins {
  id("cpp-library")
}

library {
  baseName.set("brotlijni")
  linkage.add(Linkage.SHARED)

  val targets: List<TargetMachine> by parent!!.extra
  targetMachines.set(targets)

  source.from("src/jni")
  privateHeaders.from(
    files(
      "src/include",
      "src/common"
    )
  ) // src/common has dictionary.h for common_jni.cc

  tasks.withType<CppCompile>() {
    // C sources had to be defined here for some reason.
    source.from(fileTree("src/common") {
      include("*.c")
    })
    source.from(fileTree("src/dec") {
      include("*.c")
    })

    // Add jni include paths.
    var javaHome = System.getProperty("java.home") ?: throw GradleException("java.home not found")
    if (javaHome.endsWith("/")) {
      javaHome = javaHome.substring(0, javaHome.length - 1)
    }
    includes("$javaHome/include")
    val os = (machines as DefaultTargetMachineFactory).host().operatingSystemFamily
    if (os.isLinux) {
      includes("$javaHome/include/linux")
    } else if (os.isWindows) {
      includes("$javaHome/include/win32")
    } else if (os.isMacOs) {
      includes("$javaHome/include/darwin")
    } else {
      throw GradleException("unsupported OS: $os")
    }

    // Dictionary is bundled separately to save space.
    macros["BROTLI_EXTERNAL_DICTIONARY_DATA"] = null

    // Set strict warning options for gcc (these are enabled in the main brotli repo).
    if (toolChain is Gcc || toolChain is Clang) {
      compilerArgs.addAll(
        listOf(
          "--pedantic-errors",
          "-Wall",
          "-Wconversion",
          "-Werror",
          "-Wextra",
          "-Wlong-long",
          "-Wmissing-declarations",
          "-Wmissing-prototypes",
          "-Wno-strict-aliasing",
          "-Wshadow",
          "-Wsign-compare"
        )
      )
    }
  }
}
