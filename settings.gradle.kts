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
include("spring-boot-test")
include("methanol-redis")

// Only include native brotli-jni project if explicitly requested.
val includeBrotliJni: String? by settings
if (includeBrotliJni != null
  || settings.gradle.startParameter.taskNames.contains("installBrotli")
) {
  include("methanol-brotli:brotli-jni")
}
