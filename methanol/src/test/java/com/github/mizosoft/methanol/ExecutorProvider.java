package com.github.mizosoft.methanol;

import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.testutils.TestUtils;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

/** {@code Extension} that provides {@code Executors} and terminates them after tests. */
public final class ExecutorProvider
    implements AfterEachCallback, AfterAllCallback, ArgumentsProvider, ParameterResolver {
  private static final Namespace EXTENSION_NAMESPACE = Namespace.create(ExecutorProvider.class);

  private static final int FIXED_POOL_SIZE = 8;

  public ExecutorProvider() {}

  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    ManagedExecutors.get(context).shutdownAndTerminate();
  }

  @Override
  public void afterAll(ExtensionContext context) throws Exception {
    ManagedExecutors.get(context).shutdownAndTerminate();
  }

  @Override
  public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
    var config =
        context
            .getElement()
            .flatMap(el -> AnnotationSupport.findAnnotation(el, ExecutorConfig.class))
            .orElseThrow(() -> new UnsupportedOperationException("@ExecutorConfig not found"));
    var executors = ManagedExecutors.get(context);
    return Stream.of(config.value()).map(type -> Arguments.of(executors.newExecutor(type)));
  }

  @Override
  public boolean supportsParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    var element = parameterContext.getDeclaringExecutable();
    var config = AnnotationSupport.findAnnotation(element, ExecutorConfig.class);
    if (config.isEmpty()) {
      return false;
    }

    // Do not complete with our ArgumentsProvider side
    var argSource = AnnotationSupport.findAnnotation(element, ArgumentsSource.class);
    if (argSource.isPresent()
        && argSource.get().value() == ExecutorProvider.class
        && AnnotationSupport.isAnnotated(element, ParameterizedTest.class)) {
      return false;
    }
    return Stream.of(config.get().value())
        .anyMatch(executorType -> executorType.matches(parameterContext.getParameter().getType()));
  }

  @Override
  public Object resolveParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    var element = parameterContext.getDeclaringExecutable();
    var config = AnnotationSupport.findAnnotation(element, ExecutorConfig.class);
    if (config.isEmpty()) {
      throw new UnsupportedOperationException("@ExecutorConfig not found");
    }
    var executors = ManagedExecutors.get(extensionContext);
    return Stream.of(config.get().value())
        .filter(executorType -> executorType.matches(parameterContext.getParameter().getType()))
        .map(executors::newExecutor)
        .findFirst()
        .orElseThrow(UnsupportedOperationException::new);
  }

  @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
  @Retention(RetentionPolicy.RUNTIME)
  @ArgumentsSource(ExecutorProvider.class)
  public @interface ExecutorSource {}

  @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
  @Retention(RetentionPolicy.RUNTIME)
  @ParameterizedTest
  @ExecutorSource
  public @interface ExecutorParameterizedTest {}

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface ExecutorConfig {
    ExecutorType[] value() default {ExecutorType.FIXED_POOL, ExecutorType.SAME_THREAD};
  }

  public enum ExecutorType {
    SAME_THREAD(Executor.class) {
      @Override
      Executor createExecutor() {
        return FlowSupport.SYNC_EXECUTOR;
      }
    },
    FIXED_POOL(ThreadPoolExecutor.class) {
      private final ThreadFactory defaultThreadFactory = Executors.defaultThreadFactory();

      @Override
      Executor createExecutor() {
        return Executors.newFixedThreadPool(
            FIXED_POOL_SIZE,
            r -> {
              var thread = defaultThreadFactory.newThread(r);
              thread.setDaemon(true);
              return thread;
            });
      }
    },
    CACHED_POOL(ThreadPoolExecutor.class) {
      @Override
      Executor createExecutor() {
        return Executors.newCachedThreadPool();
      }
    },
    SCHEDULER(ScheduledThreadPoolExecutor.class) {
      @Override
      public Executor createExecutor() {
        return Executors.newScheduledThreadPool(FIXED_POOL_SIZE);
      }
    };

    private final Class<?> executorSubtype;

    ExecutorType(Class<?> executorSubtype) {
      this.executorSubtype = executorSubtype;
    }

    abstract Executor createExecutor();

    boolean matches(Class<?> paramType) {
      return paramType.isAssignableFrom(executorSubtype);
    }
  }

  private static final class ManagedExecutors implements CloseableResource {
    private static final int TERMINATION_TIMEOUT_SECS = 20;

    private final List<Executor> createdExecutors = new ArrayList<>();

    ManagedExecutors() {}

    Executor newExecutor(ExecutorType type) {
      var executor = type.createExecutor();
      createdExecutors.add(executor);
      return executor;
    }

    void shutdownAndTerminate() throws Exception {
      for (var e : createdExecutors) {
        TestUtils.shutdown(e);
        if (e instanceof ExecutorService
            && !((ExecutorService) e)
                .awaitTermination(TERMINATION_TIMEOUT_SECS, TimeUnit.SECONDS)) {
          throw new TimeoutException("timed out while waiting for pool termination: " + e);
        }
      }

      createdExecutors.clear();
    }

    @Override
    public void close() throws Throwable {
      shutdownAndTerminate();
    }

    static ManagedExecutors get(ExtensionContext context) {
      return context.getStore(EXTENSION_NAMESPACE).getOrComputeIfAbsent(ManagedExecutors.class);
    }
  }
}
