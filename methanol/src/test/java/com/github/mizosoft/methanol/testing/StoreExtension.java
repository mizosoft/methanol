/*
 * Copyright (c) 2019-2021 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.testing;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.cache.Store;
import com.github.mizosoft.methanol.internal.function.Unchecked;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.AnnotatedElement;
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
public final class StoreExtension
    implements BeforeAllCallback,
        BeforeEachCallback,
        AfterAllCallback,
        AfterEachCallback,
        ArgumentsProvider,
        ParameterResolver {
  private static final Namespace EXTENSION_NAMESPACE = Namespace.create(StoreExtension.class);

  public StoreExtension() {}

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
                resolvedStoreConfig ->
                    resolveArguments(
                        Set.of(testMethod.getParameterTypes()),
                        stores.newContext(testMethod, resolvedStoreConfig))));
  }

  @Override
  public boolean supportsParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    var element = parameterContext.getDeclaringExecutable();

    // Do not compete with our ArgumentsProvider side
    var argSource = AnnotationSupport.findAnnotation(element, ArgumentsSource.class);
    if (argSource.isPresent() && argSource.get().value() == StoreExtension.class) {
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
                resolvedStoreConfig ->
                    resolveArguments(
                        Set.of(paramType),
                        stores.getFirstContext(executable, resolvedStoreConfig))))
        .flatMap(args -> Stream.of(args.get()))
        .findFirst()
        .orElseThrow(UnsupportedOperationException::new);
  }

  private static StoreConfig findStoreConfig(AnnotatedElement element) {
    return AnnotationSupport.findAnnotation(element, StoreConfig.class)
        .orElse(DEFAULT_STORE_CONFIG);
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

  private static Stream<ResolvedStoreConfig> resolveConfigs(StoreConfig config) {
    return cartesianProduct(
            Set.of(config.maxSize()),
            Set.of(config.store()),
            Set.of(config.fileSystem()),
            Set.of(config.execution()),
            Set.of(config.appVersion()),
            Set.of(config.indexUpdateDelaySeconds()),
            Set.of(config.autoInit()),
            Set.of(config.autoAdvanceClock()))
        .stream()
        .map(ResolvedStoreConfig::create)
        .filter(ResolvedStoreConfig::isCompatible);
  }

  private static Set<List<?>> cartesianProduct(Set<?>... sets) {
    // Cover empty sets case
    if (sets.length == 0) {
      return Set.of(List.of());
    }
    return cartesianProduct(List.of(sets));
  }

  private static Set<List<?>> cartesianProduct(List<Set<?>> sets) {
    // Cover base cases
    if (sets.isEmpty()) {
      return Set.of();
    } else if (sets.size() == 1) {
      return sets.get(0).stream()
          .map(List::of)
          .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // Generate a new product from a subproduct that is obtained recursively
    var subproduct = cartesianProduct(sets.subList(1, sets.size()));
    var product = new LinkedHashSet<List<Object>>();
    for (var element : sets.get(0)) {
      for (var subset : subproduct) {
        var newSubset = new ArrayList<>();
        newSubset.add(element);
        newSubset.addAll(subset);
        product.add(Collections.unmodifiableList(newSubset));
      }
    }
    return Collections.unmodifiableSet(product);
  }

  private static final StoreConfig DEFAULT_STORE_CONFIG;

  static {
    try {
      DEFAULT_STORE_CONFIG =
          StoreExtension.class
              .getDeclaredMethod("defaultStoreConfigHolder")
              .getAnnotation(StoreConfig.class);

      requireNonNull(DEFAULT_STORE_CONFIG);
    } catch (NoSuchMethodException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @StoreConfig
  private static void defaultStoreConfigHolder() {}

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
    private final Map<Object, List<StoreContext>> contextMap = new HashMap<>();

    ManagedStores() {}

    StoreContext newContext(Object key, ResolvedStoreConfig config) throws IOException {
      var context = config.createContext();
      contextMap.computeIfAbsent(key, __ -> new ArrayList<>()).add(context);
      return context;
    }

    /**
     * Gets the first available context or creates a new one if none is available. Used by
     * resolveParameters to associated provided params with the same context.
     */
    StoreContext getFirstContext(Object key, ResolvedStoreConfig config) throws IOException {
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
      var closeFailures = new ArrayList<Exception>();
      for (var contexts : contextMap.values()) {
        for (var context : contexts) {
          try {
            context.close();
          } catch (Exception t) {
            closeFailures.add(t);
          }
        }
      }
      contextMap.clear();

      if (!closeFailures.isEmpty()) {
        var toThrow = new IOException("encountered one or more exceptions while closing stores");
        closeFailures.forEach(toThrow::addSuppressed);
        throw toThrow;
      }
    }

    static ManagedStores get(ExtensionContext context) {
      return context.getStore(EXTENSION_NAMESPACE).getOrComputeIfAbsent(ManagedStores.class);
    }
  }
}
