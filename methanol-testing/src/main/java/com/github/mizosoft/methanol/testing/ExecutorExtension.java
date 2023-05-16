/*
 * Copyright (c) 2023 Moataz Abdelnasser
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

import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.AnnotatedElement;
import java.util.concurrent.*;
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

  private static final ExecutorSpec DEFAULT_EXECUTOR_SPEC;

  static {
    try {
      DEFAULT_EXECUTOR_SPEC =
          requireNonNull(
              ExecutorExtension.class
                  .getDeclaredMethod("defaultExecutorSpecHolder")
                  .getAnnotation(ExecutorSpec.class));
    } catch (NoSuchMethodException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  public ExecutorExtension() {}

  @Override
  public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
    var spec = findExecutorSpec(context.getRequiredTestMethod());
    var executors = ManagedExecutors.get(context);
    return Stream.of(spec.value())
        .map(executorType -> Arguments.of(executors.createExecutor(executorType)));
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

    return Stream.of(findExecutorSpec(parameterContext.getDeclaringExecutable()).value())
        .anyMatch(executorType -> executorType.matches(parameterContext.getParameter().getType()));
  }

  @Override
  public Object resolveParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    var executable = parameterContext.getDeclaringExecutable();
    var spec = findExecutorSpec(executable);
    var executors = ManagedExecutors.get(extensionContext);
    return Stream.of(spec.value())
        .filter(executorType -> executorType.matches(parameterContext.getParameter().getType()))
        .map(executors::createExecutor)
        .findFirst()
        .orElseThrow(UnsupportedOperationException::new);
  }

  private static ExecutorSpec findExecutorSpec(AnnotatedElement element) {
    return AnnotationSupport.findAnnotation(element, ExecutorSpec.class)
        .orElse(DEFAULT_EXECUTOR_SPEC);
  }

  @ExecutorSpec
  private static void defaultExecutorSpecHolder() {}

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
  public @interface ExecutorSpec {
    ExecutorType[] value() default {ExecutorType.CACHED_POOL, ExecutorType.SAME_THREAD};
  }

  public enum ExecutorType {
    SAME_THREAD(Executor.class) {
      @Override
      public Executor createExecutor(ThreadFactory ignored) {
        return FlowSupport.SYNC_EXECUTOR;
      }
    },
    CACHED_POOL(ThreadPoolExecutor.class) {
      @Override
      public Executor createExecutor(ThreadFactory threadFactory) {
        return Executors.newCachedThreadPool(threadFactory);
      }
    },
    SCHEDULER(ScheduledThreadPoolExecutor.class) {
      @Override
      public Executor createExecutor(ThreadFactory threadFactory) {
        return Executors.newScheduledThreadPool(1, threadFactory);
      }
    };

    private final Class<?> executorSubtype;

    ExecutorType(Class<?> executorSubtype) {
      this.executorSubtype = executorSubtype;
    }

    public abstract Executor createExecutor(ThreadFactory threadFactory);

    boolean matches(Class<?> paramType) {
      return paramType.isAssignableFrom(executorSubtype);
    }
  }

  private static final class ManagedExecutors implements CloseableResource {
    private final ExecutorContext context = new ExecutorContext();

    ManagedExecutors() {}

    Executor createExecutor(ExecutorType type) {
      return context.createExecutor(type);
    }

    @Override
    public void close() throws Throwable {
      context.close();
    }

    static ManagedExecutors get(ExtensionContext extensionContext) {
      return extensionContext
          .getStore(EXTENSION_NAMESPACE)
          .getOrComputeIfAbsent(ManagedExecutors.class);
    }
  }
}
