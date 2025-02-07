# methanol-redis

Redis storage extension for the HTTP cache, built on [lettuce](https://github.com/redis/lettuce). The extension supports
Redis Standalone & Redis Cluster setups.

## Installation

### Gradle

```kotlin
implementation("com.github.mizosoft.methanol:methanol-redis:1.8.1")
```

### Maven

```xml
<dependency>
  <groupId>com.github.mizosoft.methanol</groupId>
  <artifactId>methanol-redis</artifactId>
  <version>1.8.1</version>
</dependency>
```

## Usage

Plug in a [RedisStorageExtension](https://mizosoft.github.io/methanol/api/latest/methanol.redis/com/github/mizosoft/methanol/store/redis/RedisStorageExtension.html) instance.
The easiest way is to create one from a `RedisURI`.

### Redis Standalone

```java
var redisUrl = RedisURI.create("redis://localhost:6379");
var cache =
    HttpCache.newBuilder()
        .cacheOn(
            RedisStorageExtension.newBuilder()
                .standalone(redisUri)
                .build())
        .build();
var client = Methanol.newBuilder().cache(cache).build();
```

### Redis Cluster

```java
var redisUris = List.of(
    RedisURI.create("redis://localhost:6379"),
    RedisURI.create("redis://localhost:6380"),
    ...);
var cache =
    HttpCache.newBuilder()
        .cacheOn(
            RedisStorageExtension.newBuilder()
                .cluster(redisUris)
                .build())
        .build();
var client = Methanol.newBuilder().cache(cache).build();
```

Don't forget to close the cache!

You can also pass your own `Redis[Cluster]Client`, but then you'll be responsible for its closure.

### `RedisConnectionProvider`

Another way to create a `RedisStorageExtension` is to provide your implementation of [`RedisConnectionProvider`](https://mizosoft.github.io/methanol/api/latest/methanol.redis/com/github/mizosoft/methanol/store/redis/RedisConnectionProvider.html),
which is an abstraction for the provision and release of redis connections. Currently, the implementation relies on
a single connection during lifetime of the cache, and releases it when the cache is closed. `RedisConnectionProvider::close` is also invoked when the cache is closed.

### Timeouts

For better memory efficiency, the extension implements stream semantics upon Redis. This means that whatever
read or written is seen as a stream of bytes that is acquired progressively as chunks, rather than first loaded entirely
in memory. As the program utilizing the cache can fail at any time, this requires having timeouts so that temporary
streams that are not utilized anymore are deleted. There are two:

- `editorLockInactiveTtlSeconds`: The number of seconds an uncommitted entry that is still being edited can live during
  an editor's inactivity. Default is `5` seconds.
- `staleEntryInactiveTtlSeconds`: the number of seconds a deleted entry is allowed to live during an inactivity from  
  a potential concurrent reader. Default is `3` seconds.

You can customize these with `RedisStorageExtension.Builder`.
