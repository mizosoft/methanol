# methanol-benchmarks

[JMH][jmh] tests for Methanol's performance.

## Running Benchmarks

Benchmarks are available as a runnable Jar in [Maven][benchmarks_maven]. You run them as following:

```bsh
java -jar benchmarks-1.8.3-all.jar
```

## Results

### BodyDecoder vs InputStream

Compare Methanol's non-blocking decoders with available `InputStream` ones:

| Decoder               | Mode  | Cnt | Score    | Error   | Units |
|-----------------------|-------|-----|----------|---------|-------|
| Gzip `BodyDecoder`    | thrpt | 5   | 4170.501 | 50.458  | ops/s |
| `GZIPInputStream`     | thrpt | 5   | 4108.730 | 70.605  | ops/s |
| Deflate `BodyDecoder` | thrpt | 5   | 4037.943 | 51.947  | ops/s |
| `InflaterInputStream` | thrpt | 5   | 4035.100 | 162.641 | ops/s |
| Brotli `BodyDecoder`  | thrpt | 5   | 4186.791 | 213.283 | ops/s |
| `BrotliInputStream`   | thrpt | 5   | 2631.312 | 136.291 | ops/s |

Results show that `BodyDecoder` implementations are on par with available `InputStream` based decoders.
Note that the brotli benchmark is biased as it also compares native C vs pure Java implementations.

[jmh]: https://openjdk.java.net/projects/code-tools/jmh/
[benchmarks_maven]: https://mvnrepository.com/artifact/com.github.mizosoft.methanol/benchmarks/
