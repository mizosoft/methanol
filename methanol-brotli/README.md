# methanol-brotli

Provides [brotli][brotli] decompression.

## Installation

### Gradle

```gradle
implementation("com.github.mizosoft.methanol:methanol-brotli:1.8.4")
```

### Maven

```xml
<dependency>
  <groupId>com.github.mizosoft.methanol</groupId>
  <artifactId>methanol-brotli</artifactId>
    <version>1.8.4</version>
</dependency>
```

## Implementation notes

The Java brotli decoder provided by Google only exposes
`InputStream` APIs. It cannot be used to implement a non-blocking
`BodyDecoder`. The C implementation is used instead through JNI bindings (also provided by Google). To allow multi-platform support, native libraries for each supported OS and architecture are bundled with the JAR and extracted to a temp directory on use.

### Supported platforms

| OS       | x86-64 | ARM64 (aarch64) |
|----------|--------|-----------------|
| Windows  | ✔      | ✔              |
| Linux    | ✔      | ✔              |
| macOS    | ✔      | ✔              |

### JAR variants

The library is published with multiple JAR variants to support different use cases:

#### 1. Default (Fat JAR)

```gradle
implementation("com.github.mizosoft.methanol:methanol-brotli:1.8.4")
```

- **Includes:** Java classes + native libraries for all supported platforms
- **Use when:
  ** You want the simplest setup that works for all supported platforms and don't mind the larger JAR size (~2MB)

#### 2. Platform-specific JAR

```gradle
implementation("com.github.mizosoft.methanol:methanol-brotli:1.8.4") {
  artifact {
    classifier = "linux-x86-64"
  }
}
```

- **Includes:** Java classes + native library for one specific platform
- **Use when:** You know your target platform and want a smaller JAR
- **Available classifiers:** `linux-x86-64`, `linux-aarch64`, `macos-x86-64`, `macos-aarch64`, `windows-x86-64`,
  `windows-aarch64`

#### 3. Base JAR (No natives)

```gradle
implementation("com.github.mizosoft.methanol:methanol-brotli:1.8.4") {
  artifact {
    classifier = "base"
  }
}
```

- **Includes:** Only Java classes (no native libraries)
- **Use when:** You want to provide your own native library build
- **Requires:** Setting custom library path

### Custom native library loading

You can provide your own native library build through system properties. The library tries to load natives in this order:

1. **Custom directory path** (if specified):
   ```bash
   java -Dcom.github.mizosoft.methanol.brotli.libraryPath=/path/to/lib/dir -jar app.jar
   ```
   Looks for the platform-specific library file (e.g., `libbrotlijni.so`, `brotlijni.dll`) in the specified directory.

2. **Standard `java.library.path`**:
   ```bash
   java -Djava.library.path=/path/to/lib/dir -jar app.jar
   ```
   Uses Java's built-in library search mechanism.

3. **Bundled extraction (default)**:
   If no custom path is specified or loading fails, extracts the bundled native library from the JAR to a temp directory.

#### Building from source

If your platform is not supported, you can build from the source in the `native` directory.

```bash
# Build your custom brotli native library.
cd native
cmake .
cmake --buid .
```

Then you can include the base jar and provide a custom load directory.

[brotli]: https://github.com/google/brotli
