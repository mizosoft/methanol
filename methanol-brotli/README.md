# methanol-brotli

Provides [brotli][brotli_google] decompression for Methanol.

## Installation

### Gradle

```gradle
dependencies {
  implementation 'com.github.mizosoft.methanol:methanol-brotli:1.4.0'
}
```

### Maven

```xml
<dependencies>
  <dependency>
    <groupId>com.github.mizosoft.methanol</groupId>
    <artifactId>methanol-brotli</artifactId>
    <version>1.4.0</version>
  </dependency>
</dependencies>
```

## Implementation notes

The Java brotli decoder provided by Google only exposes `InputStream` API. Thus, it cannot be used
to implement a non-blocking `BodyDecoder`. The C implementation is used instead through JNI
bindings (also provided by Google). To allow multi-platform support, native libraries for each
supported OS X Architecture are bundled with the Jar and extracted to a temp directory on use.

### Supported platforms

| OS       | x86 | x64 | Tool Chain   | Tested Machines     |
|----------|-----|-----|--------------|---------------------|
| Windows  | ✔   | ✔  | Visual C++   | Windows 10 and CI   |
| Linux    | ✔   | ✔  | GCC 9.2.1    | Ubuntu 19.10 and CI |
| Mac OS   | ❌  | ❌ |              |                     |

### Building from source

You can instead build from source if your platform is not supported. The build routine uses Gradle's
[native software plugin][gradle_native_plugin]. You need to have a tool chain
[supported by gradle][gradle_supported_toolchains] for your OS.

#### Steps

After cloning this repo, run gradle with the `installBrotli` and `assemble` tasks:

`gradlew installBrotli :methanol-brotli:assemble`

This will build the native libraries and copy them to `src/main/resources` for inclusion in the Jar.
You will find the assembled ready-to-use Jar in the `build/libs/` directory.

[brotli_google]: https://github.com/google/brotli
[gradle_native_plugin]: https://docs.gradle.org/current/userguide/native_software.html
[gradle_supported_toolchains]: https://docs.gradle.org/current/userguide/native_software.html#native-binaries:tool-chain-support
