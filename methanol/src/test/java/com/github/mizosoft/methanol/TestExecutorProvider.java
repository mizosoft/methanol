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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

/** An extension that provides sync and async {@code Executors} and terminates them after tests. */
public final class TestExecutorProvider
    implements ArgumentsProvider,
        BeforeAllCallback,
        BeforeEachCallback,
        AfterEachCallback,
        AfterAllCallback {
  private static final Namespace EXTENSION_NAMESPACE = Namespace.create(TestExecutorProvider.class);

  private static final int FIXED_POOL_SIZE = 8;

  /** Executors created from {@code newExecutor}. */
  private @MonotonicNonNull ManagedExecutors explicitExecutors;

  public TestExecutorProvider() {}

  @Override
  public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
    var executors = ManagedExecutors.get(context);
    return Stream.of(ExecutorType.values()).map(type -> Arguments.of(executors.newExecutor(type)));
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    explicitExecutors = ManagedExecutors.get(context);
  }

  @Override
  public void beforeAll(ExtensionContext context) {
    explicitExecutors = ManagedExecutors.get(context);
  }

  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    ManagedExecutors.get(context).shutdownAndTerminate();
  }

  @Override
  public void afterAll(ExtensionContext context) throws Exception {
    ManagedExecutors.get(context).shutdownAndTerminate();
  }

  public Executor newExecutor(ExecutorType type) {
    var executors = explicitExecutors;
    if (executors == null) {
      throw new IllegalStateException("expected beforeAll or beforeEach to be called");
    }
    return executors.newExecutor(type);
  }

  @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
  @Retention(RetentionPolicy.RUNTIME)
  @ArgumentsSource(TestExecutorProvider.class)
  public @interface ExecutorSource {}

  @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
  @Retention(RetentionPolicy.RUNTIME)
  @ParameterizedTest
  @ExecutorSource
  public @interface TestWithExecutor {}

  public enum ExecutorType {
    SAME_THREAD {
      @Override
      Executor createExecutor() {
        return FlowSupport.SYNC_EXECUTOR;
      }
    },
    FIXED_POOL {
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
    };

    abstract Executor createExecutor();
  }

  private static final class ManagedExecutors implements CloseableResource {
    private static final int TERMINATION_TIMEOUT_SECS = 3;

    private final List<Executor> executors = new ArrayList<>();

    ManagedExecutors() {}

    Executor newExecutor(ExecutorType type) {
      var executor = type.createExecutor();
      executors.add(executor);
      return executor;
    }

    void shutdownAndTerminate() throws InterruptedException {
      for (var e : executors) {
        TestUtils.shutdown(e);
        if (e instanceof ExecutorService
            && !((ExecutorService) e)
                .awaitTermination(TERMINATION_TIMEOUT_SECS, TimeUnit.SECONDS)) {
          throw new RuntimeException("timed out while waiting for pool termination: " + e);
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
