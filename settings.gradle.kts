import java.io.FileNotFoundException
import java.util.*

rootProject.name = "methanol-parent"

include("methanol")
include("methanol-testing")
include("methanol-gson")
include("methanol-jackson")
include("methanol-jackson-flux")
include("methanol-protobuf")
include("methanol-jaxb")
include("methanol-jaxb-jakarta")
include("methanol-brotli")
include("methanol-blackbox")
include("methanol-benchmarks")
include("methanol-samples")
include("methanol-samples:crawler")
include("methanol-samples:download-progress")
include("methanol-samples:upload-progress")
include("methanol-samples:kotlin")
include("spring-boot-test")
include("methanol-redis")
include("methanol-kotlin")
include("methanol-moshi")

// Load local properties while giving precedence to properties defined through CLI.
try {
  rootDir.resolve("local.properties")
    .inputStream()
    .run { use { stream -> Properties().apply { load(stream) } } }
    .filter { !gradle.startParameter.projectProperties.containsKey(it.key) }
    .also { localProperties ->
      localProperties.forEach { (name, value) -> settings.extra[name.toString()] = value }
      gradle.rootProject {
        localProperties.forEach { (name, value) -> project.extra[name.toString()] = value }
      }
    }
} catch (_: FileNotFoundException) {
}

val includeNativeTests: String? by settings
if (includeNativeTests != null) {
  include("quarkus-native-test")
  include("native-test")
}

// Only include native brotli-jni project if explicitly requested.
val includeBrotliJni: String? by settings
if (includeBrotliJni != null
  || settings.gradle.startParameter.taskNames.contains("installBrotli")
) {
  include("methanol-brotli:brotli-jni")
}
