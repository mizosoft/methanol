/*
 * Copyright (c) 2023 Moataz Abdelnasser
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

rootProject.name = "methanol-parent"

include("methanol")
include("methanol-testing")
include("methanol-gson")
include("methanol-jackson")
include("methanol-jackson-flux")
include("methanol-protobuf")
include("methanol-jaxb")
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
