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

import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.testing.TestUtils;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.AnnotatedElement;
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
public final class ExecutorExtension implements ArgumentsProvider, ParameterResolver {
  private static final Namespace EXTENSION_NAMESPACE = Namespace.create(ExecutorExtension.class);

  private static final ExecutorConfig DEFAULT_EXECUTOR_CONFIG;

  static {
    try {
      DEFAULT_EXECUTOR_CONFIG =
          requireNonNull(
              ExecutorExtension.class
                  .getDeclaredMethod("defaultExecutorConfigHolder")
                  .getAnnotation(ExecutorConfig.class));
    } catch (NoSuchMethodException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static final int FIXED_POOL_SIZE = 8;

  public ExecutorExtension() {}

  @Override
  public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
    var config = findExecutorConfig(context.getRequiredTestMethod());
    var executors = ManagedExecutors.get(context);
    return Stream.of(config.value())
        .map(executorType -> Arguments.of(executors.newExecutor(executorType)));
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
            .filter(ExecutorExtension.class::equals)
            .isPresent();
    if (isPresentAsArgumentsProvider) {
      return false;
    }

    return Stream.of(findExecutorConfig(parameterContext.getDeclaringExecutable()).value())
        .anyMatch(executorType -> executorType.matches(parameterContext.getParameter().getType()));
  }

  @Override
  public Object resolveParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    var executable = parameterContext.getDeclaringExecutable();
    var config = findExecutorConfig(executable);
    var executors = ManagedExecutors.get(extensionContext);
    return Stream.of(config.value())
        .filter(executorType -> executorType.matches(parameterContext.getParameter().getType()))
        .map(executors::newExecutor)
        .findFirst()
        .orElseThrow(UnsupportedOperationException::new);
  }

  private static ExecutorConfig findExecutorConfig(AnnotatedElement element) {
    return AnnotationSupport.findAnnotation(element, ExecutorConfig.class)
        .orElse(DEFAULT_EXECUTOR_CONFIG);
  }

  @ExecutorConfig
  private static void defaultExecutorConfigHolder() {}

  @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
  @Retention(RetentionPolicy.RUNTIME)
  @ArgumentsSource(ExecutorExtension.class)
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
      public Executor createExecutor() {
        return FlowSupport.SYNC_EXECUTOR;
      }
    },
    FIXED_POOL(ThreadPoolExecutor.class) {
      private final ThreadFactory defaultThreadFactory = Executors.defaultThreadFactory();

      @Override
      public Executor createExecutor() {
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
      public Executor createExecutor() {
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

    public abstract Executor createExecutor();

    boolean matches(Class<?> paramType) {
      return paramType.isAssignableFrom(executorSubtype);
    }
  }

  private static final class ManagedExecutors implements CloseableResource {
    private final List<Executor> executors = new ArrayList<>();

    ManagedExecutors() {}

    Executor newExecutor(ExecutorType type) {
      var executor = type.createExecutor();
      executors.add(executor);
      return executor;
    }

    void shutdownAndTerminate() throws Exception {
      for (var executor : executors) {
        TestUtils.shutdown(executor);
        // Clear interruption flag to not throw from awaitTermination if this thread is interrupted
        // by some test.
        boolean interrupted = Thread.interrupted();
        try {
          if (executor instanceof ExecutorService
              && !((ExecutorService) executor).awaitTermination(5, TimeUnit.MINUTES)) {
            throw new TimeoutException("timed out while waiting for pool termination: " + executor);
          }
        } finally {
          if (interrupted) {
            Thread.currentThread().interrupt();
          }
        }
      }

      executors.clear();
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
