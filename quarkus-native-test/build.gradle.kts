/*
 * Copyright (c) 2024 Moataz Hussein
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

plugins {
  id("conventions.java-library")
  id("conventions.static-analysis")
  id("conventions.java-testing")
  id("conventions.coverage")
  alias(libs.plugins.quarkus)
}

dependencies {
  implementation(project(":methanol"))
  implementation(project(":methanol-jackson"))
  implementation(project(":methanol-testing"))
  implementation(libs.jackson.databind)
  implementation(libs.mockwebserver)
  implementation(libs.autoservice.annotations)
  annotationProcessor(libs.autoservice.annprocess)
  implementation(enforcedPlatform(libs.quarkus.bom))
  implementation(libs.quarkus.rest.jackson)
  testImplementation(libs.quarkus.junit5)
  testImplementation(libs.rest.assured)
}

tasks.withType<Test> {
  systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}

tasks.withType<JavaCompile> {
  // Generate metadata for reflection on method parameters
  options.compilerArgs.add("-parameters")
}

quarkus {
  buildForkOptions {
    // Always generate a native image.
    systemProperty("quarkus.package.type", "native")

    // Make ServiceLoader work.
    systemProperty("quarkus.native.auto-service-loader-registration", "true")

    systemProperty(
      "quarkus.native.additional-build-args",
      listOf(
        // For debuggability.
        "-H:+ReportExceptionStackTraces",

        // These depend on Inet4Address, which cannot be initialized in build-time. Discovered
        // through trial and error.
        "--initialize-at-run-time=io.lettuce.core.resource.DefaultClientResources",
        "--initialize-at-run-time=io.lettuce.core.resource.AddressResolverGroupProvider",
        "--initialize-at-run-time=io.lettuce.core.resource.AddressResolverGroupProvider\$DefaultDnsAddressResolverGroupWrapper",

        // Okhttp accesses internal GraalVM API.
        "-J--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.configure=ALL-UNNAMED"
      ).joinToString(",")
    )
  }
}
