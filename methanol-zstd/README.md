# methanol-zstd

Provides [Zstandard (Zstd)][zstd] decompression support through the [zstd-jni][zstd-jni] library.

## Installation

### Gradle

```gradle
implementation("com.github.mizosoft.methanol:methanol-zstd:1.9.0")
```

### Maven

```xml
<dependency>
  <groupId>com.github.mizosoft.methanol</groupId>
  <artifactId>methanol-zstd</artifactId>
  <version>1.9.0</version>
</dependency>
```

## Implementation notes

The decoder uses the [zstd-jni][zstd-jni] library which provides JNI bindings to the native Zstandard library. Unlike the brotli module, this module doesn't embed any native binaries itself.

### Supported platforms

The [zstd-jni][zstd-jni] library supports:

| OS       | x86-64 | ARM64 (aarch64) |
|----------|--------|-----------------|
| Windows  | ✔      | ✔              |
| Linux    | ✔      | ✔              |
| macOS    | ✔      | ✔              |
| FreeBSD  | ✔      | ✔              |

And many other platforms. Consult the [zstd-jni documentation][zstd-jni] for the complete list of supported platforms.

[zstd]: https://facebook.github.io/zstd/
[zstd-jni]: https://github.com/luben/zstd-jni
