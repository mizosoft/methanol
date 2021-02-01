/*
 * Copyright (c) 2019, 2020 Moataz Abdelnasser
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.mizosoft.methanol.testing.extensions;

import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.mizosoft.methanol.internal.cache.DiskStore;
import com.github.mizosoft.methanol.internal.cache.DiskStore.Delayer;
import com.github.mizosoft.methanol.internal.cache.DiskStore.Hash;
import com.github.mizosoft.methanol.internal.cache.MemoryStore;
import com.github.mizosoft.methanol.internal.cache.Store;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.function.Unchecked;
import com.github.mizosoft.methanol.testing.extensions.StoreProvider.StoreConfig.Execution;
import com.github.mizosoft.methanol.testing.extensions.StoreProvider.StoreConfig.FileSystemType;
import com.github.mizosoft.methanol.testing.extensions.StoreProvider.StoreConfig.StoreType;
import com.github.mizosoft.methanol.testutils.MockClock;
import com.github.mizosoft.methanol.testutils.MockExecutor;
import com.google.common.collect.Sets;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.AnnotatedElement;
import java.nio.ByteBuffer;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.platform.commons.support.AnnotationSupport;

/** {@code Extension} that provides {@code Store} instances with multiple configurations. */
public final class StoreProvider
    implements BeforeAllCallback,
        BeforeEachCallback,
        AfterAllCallback,
        AfterEachCallback,
        ArgumentsProvider,
        ParameterResolver {
  private static final Namespace EXTENSION_NAMESPACE = Namespace.create(StoreProvider.class);

  @Override
  public void beforeAll(ExtensionContext context) throws Exception {
    ManagedStores.get(context).initializeAll();
  }

  @Override
  public void beforeEach(ExtensionContext context) throws Exception {
    ManagedStores.get(context).initializeAll();
  }

  @Override
  public void afterAll(ExtensionContext context) throws Exception {
    ManagedStores.get(context).close();
  }

  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    ManagedStores.get(context).close();
  }

  @Override
  public Stream<? extends Arguments> provideArguments(ExtensionContext extensionContext) {
    var testMethod = extensionContext.getRequiredTestMethod();
    var storeConfig = findStoreConfig(testMethod);
    var stores = ManagedStores.get(extensionContext);
    return resolveConfigs(storeConfig)
        .map(
            Unchecked.func(
                resolvedConfig ->
                    resolveArguments(
                        Set.of(testMethod.getParameterTypes()),
                        stores.newContext(testMethod, resolvedConfig))));
  }

  @Override
  public boolean supportsParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    var element = parameterContext.getDeclaringExecutable();
    if (!AnnotationSupport.isAnnotated(element, StoreConfig.class)) {
      return false;
    }

    // Do not complete with our ArgumentsProvider side
    var argSource = AnnotationSupport.findAnnotation(element, ArgumentsSource.class);
    if (argSource.isPresent() && argSource.get().value() == StoreProvider.class) {
      return false;
    }

    var paramType = parameterContext.getParameter().getType();
    return paramType == Store.class || paramType == StoreContext.class;
  }

  @Override
  public Object resolveParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    var executable = parameterContext.getDeclaringExecutable();
    var storeConfig = findStoreConfig(executable);
    var stores = ManagedStores.get(extensionContext);
    var paramType = parameterContext.getParameter().getType();
    return resolveConfigs(storeConfig)
        .map(
            Unchecked.func(
                resolvedConfig ->
                    resolveArguments(
                        Set.of(paramType), stores.getFirstContext(executable, resolvedConfig))))
        .flatMap(args -> Stream.of(args.get()))
        .findFirst()
        .orElseThrow(UnsupportedOperationException::new);
  }

  private static StoreConfig findStoreConfig(AnnotatedElement element) {
    return AnnotationSupport.findAnnotation(element, StoreConfig.class)
        .orElseThrow(() -> new UnsupportedOperationException("@StoreConfig not found"));
  }

  private static Arguments resolveArguments(Set<Class<?>> params, StoreContext context)
      throws IOException {
    // Provide the StoreContext or a new Store or both
    if (params.containsAll(Set.of(Store.class, StoreContext.class))) {
      return Arguments.of(context.newStore(), context);
    } else if (params.contains(StoreContext.class)) {
      return Arguments.of(context);
    } else if (params.contains(Store.class)) {
      return Arguments.of(context.newStore());
    } else {
      return Arguments.of(); // Let JUnit handle that
    }
  }

  private static Stream<ResolvedConfig> resolveConfigs(StoreConfig config) {
    // TODO add an explicit dep on Guava so this doesn't magically disappear
    return Sets.<Object>cartesianProduct(
            Set.of(config.maxSize()),
            Set.of(config.store()),
            Set.of(config.fileSystem()),
            Set.of(config.execution()),
            Set.of(config.appVersion()),
            Set.of(config.indexUpdateDelaySeconds()),
            Set.of(config.autoInit()),
            Set.of(config.autoAdvanceClock()))
        .stream()
        .map(ResolvedConfig::create)
        .filter(ResolvedConfig::isCompatible);
  }

  @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
  @Retention(RetentionPolicy.RUNTIME)
  @ArgumentsSource(StoreProvider.class)
  public @interface StoreSource {}

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  @ParameterizedTest
  @StoreSource
  public @interface StoreParameterizedTest {}

  /** Specifies one or more {@code Store} configuration to be provided to a test case. */
  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface StoreConfig {
    int DEFAULT_FLUSH_DELAY = -1;

    long maxSize() default Long.MAX_VALUE;

    StoreType[] store() default {StoreType.MEMORY, StoreType.DISK};

    FileSystemType[] fileSystem() default {FileSystemType.MEMORY, FileSystemType.SYSTEM};

    Execution execution() default Execution.ASYNC;

    int appVersion() default 1;

    /** Delay between automatic index updates done by the disk store. */
    long indexUpdateDelaySeconds() default DEFAULT_FLUSH_DELAY;

    /** Automatically initialize a created store. */
    boolean autoInit() default true;

    /** Whether {@link MockClock} should automatically advance itself by 1 second. */
    boolean autoAdvanceClock() default true;

    enum StoreType {
      MEMORY,
      DISK
    }

    enum FileSystemType {
      MEMORY,
      SYSTEM
    }

    enum Execution {
      QUEUED {
        @Override
        Executor newExecutor() {
          return new MockExecutor();
        }
      },
      SAME_THREAD {
        @Override
        Executor newExecutor() {
          return FlowSupport.SYNC_EXECUTOR;
        }
      },
      ASYNC {
        @Override
        Executor newExecutor() {
          return Executors.newFixedThreadPool(8);
        }
      };

      abstract Executor newExecutor();
    }
  }

  public static final class ResolvedConfig {
    private static final String TEMP_DIR_PREFIX = "methanol-store-extension-junit-";

    final long maxSize;
    final StoreType storeType;

    // DiskStore-only fields

    final FileSystemType fileSystemType;
    final Execution execution;
    final int appVersion;
    final @Nullable Duration indexUpdateDelay;
    final boolean autoInit;
    final boolean autoAdvanceClock;

    private ResolvedConfig(
        long maxSize,
        StoreType storeType,
        FileSystemType fileSystemType,
        Execution execution,
        int appVersion,
        @Nullable Duration indexUpdateDelay,
        boolean autoInit,
        boolean autoAdvanceClock) {
      this.maxSize = maxSize;
      this.storeType = storeType;
      this.fileSystemType = fileSystemType;
      this.execution = execution;
      this.appVersion = appVersion;
      this.indexUpdateDelay = indexUpdateDelay;
      this.autoInit = autoInit;
      this.autoAdvanceClock = autoAdvanceClock;
    }

    public long maxSize() {
      return maxSize;
    }

    public StoreType storeType() {
      return storeType;
    }

    public FileSystemType fileSystemType() {
      return fileSystemType;
    }

    public Execution execution() {
      return execution;
    }

    public int appVersion() {
      return appVersion;
    }

    public @Nullable Duration indexUpdateDelay() {
      return indexUpdateDelay;
    }

    public boolean autoInit() {
      return autoInit;
    }

    public boolean autoAdvanceClock() {
      return autoAdvanceClock;
    }

    boolean isCompatible() {
      // Memory store doesn't use a FileSystem, so ensure it's only generated
      // once by only pairing it with FileSystemType.MEMORY (can't use empty
      // FileSystemType array as the cartesian product itself will be empty).
      return storeType != StoreType.MEMORY || fileSystemType == FileSystemType.MEMORY;
    }

    StoreContext createContext() throws IOException {
      return storeType == StoreType.MEMORY
          ? new StoreContext(this, null, null, null, null, null)
          : createDiskStoreContext();
    }

    private StoreContext createDiskStoreContext() throws IOException {
      FileSystem fileSystem;
      Path tempDirectory;
      if (fileSystemType == FileSystemType.SYSTEM) {
        // Do not record the default filesystem to not attempt to close
        // it (which will throw UnsupportedOperationException anyways).
        fileSystem = null;
        tempDirectory = Files.createTempDirectory(TEMP_DIR_PREFIX);
      } else {
        fileSystem = Jimfs.newFileSystem(Configuration.unix());
        var root = fileSystem.getRootDirectories().iterator().next();
        var tempDirectories = Files.createDirectories(root.resolve("temp"));
        tempDirectory = Files.createTempDirectory(tempDirectories, TEMP_DIR_PREFIX);
      }
      var clock = new MockClock();
      if (autoAdvanceClock) {
        clock.autoAdvance(Duration.ofSeconds(1));
      }
      return new StoreContext(
          this, tempDirectory, fileSystem, execution.newExecutor(), new MockHasher(), clock);
    }

    static ResolvedConfig create(List<Object> tuple) {
      int i = 0;
      long maxSize = (long) tuple.get(i++);
      var storeType = (StoreType) tuple.get(i++);
      var fileSystemType = (FileSystemType) tuple.get(i++);
      var execution = (Execution) tuple.get(i++);
      var appVersion = (int) tuple.get(i++);
      long indexUpdateDelaySeconds = (long) tuple.get(i++);
      var indexUpdateDelay =
          indexUpdateDelaySeconds != StoreConfig.DEFAULT_FLUSH_DELAY
              ? Duration.ofSeconds(indexUpdateDelaySeconds)
              : null;
      boolean autoInit = (boolean) tuple.get(i++);
      boolean autoAdvanceClock = (boolean) tuple.get(i);
      return new ResolvedConfig(
          maxSize,
          storeType,
          fileSystemType,
          execution,
          appVersion,
          indexUpdateDelay,
          autoInit,
          autoAdvanceClock);
    }
  }

  /** {@code DiskStore.Hasher} allowing to explicitly set fake hash codes for some keys. */
  public static final class MockHasher implements DiskStore.Hasher {
    private final Map<String, DiskStore.Hash> mockHashCodes = new ConcurrentHashMap<>();

    MockHasher() {}

    @Override
    public Hash hash(String key) {
      // Fallback to default hasher if a fake hash is not set
      var mockHash = mockHashCodes.get(key);
      return mockHash != null ? mockHash : TRUNCATED_SHA_256.hash(key);
    }

    public void setHash(String key, long upperHashBits) {
      mockHashCodes.put(
          key,
          new DiskStore.Hash(
              ByteBuffer.allocate(80).putLong(upperHashBits).putShort((short) 0).flip()));
    }
  }

  /** A Delayer that delays tasks based on a MockClock's time. */
  public static final class MockDelayer implements Delayer {
    private final MockClock clock;
    private final Queue<TimestampedTask> taskQueue =
        new PriorityQueue<>(Comparator.comparing(task -> task.stamp));

    MockDelayer(MockClock clock) {
      this.clock = clock;
      clock.onTick((instant, ticks) -> dispatchExpiredTasks(instant.plus(ticks), false));
    }

    @Override
    public CompletableFuture<Void> delay(Executor executor, Runnable task, Duration delay) {
      var now = clock.peekInstant(); // Do not advance clock
      var timestampedTask = new TimestampedTask(executor, task, now.plus(delay));
      synchronized (taskQueue) {
        taskQueue.add(timestampedTask);
      }

      dispatchExpiredTasks(now, false);
      return timestampedTask.future;
    }

    public int taskCount() {
      synchronized (taskQueue) {
        return taskQueue.size();
      }
    }

    void dispatchExpiredTasks(Instant now, boolean ignoreRejected) {
      TimestampedTask task;
      synchronized (taskQueue) {
        while ((task = taskQueue.peek()) != null && now.compareTo(task.stamp) >= 0) {
          taskQueue.poll();
          try {
            task.dispatch();
          } catch (RejectedExecutionException e) {
            if (!ignoreRejected) {
              throw e;
            }
          }
        }
      }
    }

    private static final class TimestampedTask {
      final Executor executor;
      final Runnable task;
      final Instant stamp;
      final CompletableFuture<Void> future = new CompletableFuture<>();

      TimestampedTask(Executor executor, Runnable task, Instant stamp) {
        this.task = task;
        this.stamp = stamp;
        this.executor = executor;
      }

      void dispatch() {
        future.completeAsync(
            () -> {
              task.run();
              return null;
            },
            executor);
      }
    }
  }

  /** Context for a store configuration. */
  public static final class StoreContext {
    private final ResolvedConfig config;
    private final @Nullable Path directory;
    private final @Nullable FileSystem fileSystem;
    private final @Nullable Executor executor;
    private final @Nullable MockHasher hasher;
    private final @Nullable MockClock clock;
    private final @Nullable MockDelayer delayer;

    private final List<Store> createdStores = new ArrayList<>();

    StoreContext(
        ResolvedConfig config,
        @Nullable Path directory,
        @Nullable FileSystem fileSystem,
        @Nullable Executor executor,
        @Nullable MockHasher hasher,
        @Nullable MockClock clock) {
      this.config = config;
      this.directory = directory;
      this.fileSystem = fileSystem;
      this.executor = executor;
      this.hasher = hasher;
      this.clock = clock;
      this.delayer = clock != null ? new MockDelayer(clock) : null;
    }

    public long maxSize() {
      return config.maxSize;
    }

    public MockExecutor mockExecutor() {
      if (!(executor instanceof MockExecutor)) {
        throw new UnsupportedOperationException("unavailable MockExecutor");
      }
      return ((MockExecutor) executor);
    }

    public MockHasher hasher() {
      if (hasher == null) {
        throw new UnsupportedOperationException("unavailable mockHasher");
      }
      return hasher;
    }

    public MockClock clock() {
      if (clock == null) {
        throw new UnsupportedOperationException("unavailable MockClock");
      }
      return clock;
    }

    public MockDelayer delayer() {
      if (delayer == null) {
        throw new UnsupportedOperationException("unavailable delayer");
      }
      return delayer;
    }

    public Path directory() {
      if (directory == null) {
        throw new UnsupportedOperationException("unavailable directory");
      }
      return directory;
    }

    public ResolvedConfig config() {
      return config;
    }

    /** If execution is mocked, makes sure all tasks queued so far are executed. */
    public void drainQueuedTasks() {
      if (delayer != null) {
        delayer.dispatchExpiredTasks(Instant.MAX, false);
      }
      if (executor instanceof MockExecutor) {
        ((MockExecutor) executor).runAll();
      }
    }

    public Store newStore() throws IOException {
      var store = createStore();
      createdStores.add(store);
      initializeAll();
      return store;
    }

    private Store createStore() {
      switch (config.storeType) {
        case MEMORY:
          return new MemoryStore(config.maxSize);
        case DISK:
          var builder =
              DiskStore.newBuilder()
                  .maxSize(config.maxSize)
                  .directory(directory)
                  .executor(executor)
                  .hasher(hasher)
                  .clock(clock)
                  .delayer(delayer)
                  .appVersion(config.appVersion);
          return config.indexUpdateDelay != null
              ? builder.indexUpdateDelay(config.indexUpdateDelay).build()
              : builder.build();
        default:
          return fail("unexpected StoreType: " + config.storeType);
      }
    }

    void initializeAll() throws IOException {
      executeOnSameThreadIfQueuedExecution();
      for (var store : createdStores) {
        if (config().autoInit()) {
          store.initialize();
        }
      }
      resetExecuteOnSameThreadIfQueued();
    }

    void close() throws Exception {
      Exception caughtException = null;

      // First make sure no more tasks are queued
      if (delayer != null) {
        // Ignore rejected tasks as the test might have caused an executor to shutdown
        delayer.dispatchExpiredTasks(Instant.MAX, true);
      }
      if (executor instanceof MockExecutor) {
        try {
          var mockExecutor = (MockExecutor) executor;
          mockExecutor.executeOnSameThread(true);
          mockExecutor.runAll();
        } catch (Exception e) {
          caughtException = e;
        }
      }

      // Then close created stores
      for (var store : createdStores) {
        try {
          store.close();
        } catch (IOException ioe) {
          if (caughtException != null) {
            caughtException.addSuppressed(ioe);
          } else {
            caughtException = ioe;
          }
        }
      }

      // Then await ExecutorService termination if we have one
      if (executor instanceof ExecutorService) {
        var service = (ExecutorService) executor;
        service.shutdown();
        try {
          if (!service.awaitTermination(20, TimeUnit.SECONDS)) {
            throw new TimeoutException(
                "timed-out while waiting for pool's termination: " + service);
          }
        } catch (InterruptedException | TimeoutException e) {
          if (caughtException != null) {
            caughtException.addSuppressed(e);
          } else {
            caughtException = e;
          }
        }
      }

      // Then delete the temp directory if we have one
      if (directory != null) {
        try {
          Files.walkFileTree(
              directory,
              new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                  Files.deleteIfExists(file);
                  return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                  if (exc != null) {
                    throw exc;
                  }
                  Files.deleteIfExists(dir);
                  return FileVisitResult.CONTINUE;
                }
              });
        } catch (NoSuchFileException | ClosedFileSystemException ignored) {
          // OK
        } catch (IOException ioe) {
          if (caughtException != null) {
            caughtException.addSuppressed(ioe);
          } else {
            caughtException = ioe;
          }
        }
      }

      // Finally close the FileSystem if we have one
      if (fileSystem != null) {
        try {
          fileSystem.close();
        } catch (IOException ioe) {
          if (caughtException != null) {
            caughtException.addSuppressed(ioe);
          } else {
            throw ioe;
          }
        }
      }

      if (caughtException != null) {
        throw caughtException;
      }
    }

    /**
     * If the execution is queued, configures it to run tasks on the same thread instead of queueing
     * them.
     */
    private void executeOnSameThreadIfQueuedExecution() {
      if (config.execution == Execution.QUEUED) {
        castNonNull((MockExecutor) executor).executeOnSameThread(true);
      }
    }

    private void resetExecuteOnSameThreadIfQueued() {
      if (config.execution == Execution.QUEUED) {
        castNonNull((MockExecutor) executor).executeOnSameThread(false);
      }
    }
  }

  static final class ManagedStores implements CloseableResource {
    private final Map<Object, List<StoreContext>> contextMap = new HashMap<>();

    ManagedStores() {}

    StoreContext newContext(Object key, ResolvedConfig config) throws IOException {
      var context = config.createContext();
      contextMap.computeIfAbsent(key, __ -> new ArrayList<>()).add(context);
      return context;
    }

    /**
     * Gets the first available context or creates a new one if none is available. Used by
     * resolveParameters to associated provided params with the same context.
     */
    StoreContext getFirstContext(Object key, ResolvedConfig config) throws IOException {
      var contexts = contextMap.computeIfAbsent(key, __ -> new ArrayList<>());
      if (contexts.isEmpty()) {
        contexts.add(config.createContext());
      }
      return contexts.get(0);
    }

    void initializeAll() throws IOException {
      for (var contexts : contextMap.values()) {
        for (var context : contexts) {
          context.initializeAll();
        }
      }
    }

    @Override
    public void close() throws Exception {
      var thrown = new ArrayList<Exception>();
      for (var contexts : contextMap.values()) {
        for (var context : contexts) {
          try {
            context.close();
          } catch (Exception e) {
            thrown.add(e);
          }
        }
      }
      contextMap.clear();

      if (!thrown.isEmpty()) {
        var toThrow =
            new IOException("encountered one or more exceptions while closing created stores");
        thrown.forEach(toThrow::addSuppressed);
        throw toThrow;
      }
    }

    static ManagedStores get(ExtensionContext context) {
      return context.getStore(EXTENSION_NAMESPACE).getOrComputeIfAbsent(ManagedStores.class);
    }
  }
}
