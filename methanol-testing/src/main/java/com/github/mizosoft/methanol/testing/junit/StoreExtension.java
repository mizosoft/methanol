/*
 * Copyright (c) 2022 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.testing.junit;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.mizosoft.methanol.internal.cache.Store;
import com.github.mizosoft.methanol.internal.function.Unchecked;
import com.github.mizosoft.methanol.testing.junit.StoreSpec.Execution;
import com.github.mizosoft.methanol.testing.junit.StoreSpec.FileSystemType;
import com.github.mizosoft.methanol.testing.junit.StoreSpec.StoreType;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.AnnotatedElement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
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
public final class StoreExtension
    implements AfterAllCallback, AfterEachCallback, ArgumentsProvider, ParameterResolver {
  private static final Namespace EXTENSION_NAMESPACE = Namespace.create(StoreExtension.class);
  private static final StoreSpec DEFAULT_STORE_SPEC;

  static {
    try {
      DEFAULT_STORE_SPEC =
          requireNonNull(
              StoreExtension.class
                  .getDeclaredMethod("defaultSpecHolder")
                  .getAnnotation(StoreSpec.class));
    } catch (NoSuchMethodException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  public StoreExtension() {}

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
    return resolveSpec(findSpec(testMethod))
        .map(
            Unchecked.func(
                config ->
                    resolveArguments(
                        List.of(testMethod.getParameterTypes()),
                        ManagedStores.get(extensionContext).createContext(testMethod, config))));
  }

  @Override
  public boolean supportsParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    // Do not compete with our ArgumentsProvider side.
    boolean isPresentAsArgumentsProvider =
        AnnotationSupport.findAnnotation(
                parameterContext.getDeclaringExecutable(), ArgumentsSource.class)
            .map(ArgumentsSource::value)
            .filter(StoreExtension.class::equals)
            .isPresent();
    if (isPresentAsArgumentsProvider) {
      return false;
    }

    var parameterType = parameterContext.getParameter().getType();
    return Store.class.isAssignableFrom(parameterType)
        || StoreContext.class.isAssignableFrom(parameterType);
  }

  @Override
  public Object resolveParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    var executable = parameterContext.getDeclaringExecutable();
    var stores = ManagedStores.get(extensionContext);
    return resolveSpec(findSpec(executable))
        .map(
            Unchecked.func(
                config ->
                    resolveArguments(
                        List.of(parameterContext.getParameter().getType()),
                        stores.getOrCreateFirstContext(executable, config))))
        .flatMap(args -> Stream.of(args.get()).findFirst().stream())
        .findFirst()
        .orElseThrow(UnsupportedOperationException::new);
  }

  private static StoreSpec findSpec(AnnotatedElement element) {
    return AnnotationSupport.findAnnotation(element, StoreSpec.class).orElse(DEFAULT_STORE_SPEC);
  }

  private static Arguments resolveArguments(List<Class<?>> parameterTypes, StoreContext context)
      throws IOException {
    var arguments = new ArrayList<>();
    for (var type : parameterTypes) {
      if (StoreContext.class.isAssignableFrom(type)) {
        arguments.add(context);
      } else if (Store.class.isAssignableFrom(type)) {
        arguments.add(context.createAndRegisterStore());
      }
    }
    return Arguments.of(arguments.toArray());
  }

  private static Stream<StoreConfig> resolveSpec(StoreSpec spec) {
    return cartesianProduct(
            List.of(
                Set.of(spec.store()),
                Set.of(spec.autoInit()),
                Set.of(spec.maxSize()),
                Set.of(spec.fileSystem()),
                Set.of(spec.execution()),
                Set.of(spec.appVersion()),
                Set.of(spec.indexUpdateDelaySeconds()),
                Set.of(spec.autoAdvanceClock()),
                Set.of(spec.staleEntryExpiryMillis()),
                Set.of(spec.editorLockExpiryMillis())))
        .stream()
        .filter(StoreExtension::isCompatibleConfig)
        .map(StoreExtension::createConfig);
  }

  private static boolean isCompatibleConfig(List<?> tuple) {
    var storeType = (StoreType) tuple.get(0);
    var fileSystem = (FileSystemType) tuple.get(3);
    switch (storeType) {
      case MEMORY:
      case REDIS:
        return fileSystem == FileSystemType.NONE;
      case DISK:
        return fileSystem != FileSystemType.NONE;
      default:
        return fail();
    }
  }

  public static StoreConfig createConfig(List<?> tuple) {
    var storeType = (StoreType) tuple.get(0);
    switch (storeType) {
      case MEMORY:
        return createMemoryStoreConfig(tuple);
      case DISK:
        return createDiskStoreConfig(tuple);
      case REDIS:
        return resolveRedisStoreConfig(tuple);
      default:
        return fail();
    }
  }

  private static MemoryStoreConfig createMemoryStoreConfig(List<?> tuple) {
    boolean autoInit = (boolean) tuple.get(1);
    long maxSize = (long) tuple.get(2);
    return new MemoryStoreConfig(autoInit, maxSize);
  }

  private static DiskStoreConfig createDiskStoreConfig(List<?> tuple) {
    int i = 1;
    boolean autoInit = (boolean) tuple.get(i++);
    long maxSize = (long) tuple.get(i++);
    var fileSystemType = (FileSystemType) tuple.get(i++);
    var execution = (Execution) tuple.get(i++);
    var appVersion = (int) tuple.get(i++);
    long indexUpdateDelaySeconds = (long) tuple.get(i++);
    var indexUpdateDelay =
        indexUpdateDelaySeconds != StoreSpec.DEFAULT_INDEX_UPDATE_DELAY
            ? Duration.ofSeconds(indexUpdateDelaySeconds)
            : null;
    boolean autoAdvanceClock = (boolean) tuple.get(i);
    return new DiskStoreConfig(
        autoInit,
        maxSize,
        fileSystemType,
        execution,
        indexUpdateDelay,
        autoAdvanceClock,
        appVersion);
  }

  private static RedisStoreConfig resolveRedisStoreConfig(List<?> tuple) {
    boolean autoInit = (boolean) tuple.get(1);
    long editorLockTimeToLiveMillis = (long) tuple.get(8);
    long staleEntryTimeTLiveMillis = (long) tuple.get(9);
    int appVersion = (int) tuple.get(5);
    return new RedisStoreConfig(
        autoInit, editorLockTimeToLiveMillis, staleEntryTimeTLiveMillis, appVersion);
  }

  private static Set<List<?>> cartesianProduct(List<Set<?>> sets) {
    // Cover base cases.
    if (sets.isEmpty()) {
      return Set.of(List.of());
    } else if (sets.size() == 1) {
      return sets.get(0).stream()
          .map(List::of)
          .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // Generate a new product from a sub-product that is obtained recursively.
    var subProduct = cartesianProduct(sets.subList(1, sets.size()));
    var product = new LinkedHashSet<List<Object>>();
    for (var element : sets.get(0)) {
      for (var subset : subProduct) {
        var newSubset = new ArrayList<>();
        newSubset.add(element);
        newSubset.addAll(subset);
        product.add(Collections.unmodifiableList(newSubset));
      }
    }
    return Collections.unmodifiableSet(product);
  }

  @StoreSpec
  private static void defaultSpecHolder() {}

  @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
  @Retention(RetentionPolicy.RUNTIME)
  @ArgumentsSource(StoreExtension.class)
  public @interface StoreSource {}

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  @ParameterizedTest(name = "{displayName}[{index}]: {argumentsWithNames}")
  @StoreSource
  public @interface StoreParameterizedTest {}

  private static final class ManagedStores implements CloseableResource {
    private final Map<Object, List<StoreContext>> contexts = new HashMap<>();

    ManagedStores() {}

    StoreContext createContext(Object key, StoreConfig config) throws IOException {
      var context = StoreContext.from(config);
      contexts.computeIfAbsent(key, __ -> new ArrayList<>()).add(context);
      return context;
    }

    /**
     * Gets the first available context or creates a new one if none is available. Used by
     * resolveParameters to associated provided params with the same context.
     */
    StoreContext getOrCreateFirstContext(Object key, StoreConfig config) throws IOException {
      var contexts = this.contexts.computeIfAbsent(key, __ -> new ArrayList<>());
      if (contexts.isEmpty()) {
        contexts.add(StoreContext.from(config));
      }
      return contexts.get(0);
    }

    @Override
    public void close() throws Exception {
      var exceptions = new ArrayList<Exception>();
      for (var contexts : contexts.values()) {
        for (var context : contexts) {
          try {
            context.close();
          } catch (Exception e) {
            exceptions.add(e);
          }
        }
      }
      contexts.clear();

      if (exceptions.size() == 1) {
        throw exceptions.get(0);
      } else if (exceptions.size() > 1) {
        var compositeException =
            new IOException("encountered one or more exceptions while closing stores");
        exceptions.forEach(compositeException::addSuppressed);
        throw compositeException;
      }
    }

    static ManagedStores get(ExtensionContext context) {
      return context.getStore(EXTENSION_NAMESPACE).getOrComputeIfAbsent(ManagedStores.class);
    }
  }
}
