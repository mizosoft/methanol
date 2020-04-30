# methanol-benchmarks

[JMH][jmh] tests for Methanol's performance.

## Run

Benchmarks are available as a runnable Jar in [Maven][benchmarks_maven]. Download then run as
following:

```bsh
java -jar benchmarks-1.2.0-all.jar
```

## Results

### `BodyDecoder` vs `InputStream`

Compare Methanol's non-blocking decoders with available `InputStream` ones:

| Encoding | Decoder               | Mode  | Cnt | Score    | Error   | Units |
|----------|-----------------------|-------|-----|----------|---------|-------|
| gzip     | `BodyDecoder`         | thrpt | 5   | 4170.501 | 50.458  | ops/s |
| gzip     | `GZIPInputStream`     | thrpt | 5   | 4108.730 | 70.605  | ops/s |
| deflate  | `BodyDecoder`         | thrpt | 5   | 4037.943 | 51.947  | ops/s |
| deflate  | `InflaterInputStream` | thrpt | 5   | 4035.100 | 162.641 | ops/s |
| brotli   | `BodyDecoder`         | thrpt | 5   | 4186.791 | 213.283 | ops/s |
| brotli   | `BrotliInputStream`   | thrpt | 5   | 2631.312 | 136.291 | ops/s |

Results show that `BodyDecoder` implementations are on par with available `InputStream` based
decoders (brotli result is biased as it also compares native C vs pure Java implementations).

### Jackson UTF-8 coercion

[methanol-jackson][methanol_jackson] uses Jackson's non-blocking JSON parser for better memory
efficiency. The parser however only supports UTF-8 and ASCII. Instead of falling back to the
byte array parser for other response charsets, an efficient operator is used to decode the response
from source charset then encode it back to UTF-8. This might seem awkward at first but measurement
shows that performance is not that different from the byte array parser (tested with UTF-16):

| Parser            | Mode  | Cnt | Score     | Error   | Units |
|-------------------|-------|-----|-----------|---------|-------|
| ASYNC_PARSER      | thrpt | 5   | 10557.752 | 133.519 | ops/s |
| BYTE_ARRAY_PARSER | thrpt | 5   | 11167.702 | 128.353 | ops/s |

[jmh]: https://openjdk.java.net/projects/code-tools/jmh/
[benchmarks_maven]: https://mvnrepository.com/artifact/com.github.mizosoft.methanol/benchmarks/
[methanol_jackson]: https://github.com/mizosoft/methanol/tree/master/methanol-jackson
