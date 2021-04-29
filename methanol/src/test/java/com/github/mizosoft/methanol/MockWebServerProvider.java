package com.github.mizosoft.methanol;

import static com.github.mizosoft.methanol.testutils.TestUtils.localhostSslContext;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import okhttp3.mockwebserver.MockWebServer;
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

/**
 * An extension that provides plain or secure {@code MockWebServers} and {@code Methanol.Builder}
 * either explicitly or by resolving parameters.
 */
public final class MockWebServerProvider
    implements BeforeAllCallback,
        BeforeEachCallback,
        AfterAllCallback,
        AfterEachCallback,
        ParameterResolver {
  private static final Namespace EXTENSION_NAMESPACE =
      Namespace.create(MockWebServerProvider.class);

  private ManagedServers explicitServers;

  @Override
  public void beforeEach(ExtensionContext context) {
    explicitServers = ManagedServers.get(context);
  }

  @Override
  public void beforeAll(ExtensionContext context) {
    explicitServers = ManagedServers.get(context);
  }

  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    ManagedServers.get(context).shutdownAll();
  }

  @Override
  public void afterAll(ExtensionContext context) throws Exception {
    ManagedServers.get(context).shutdownAll();
  }

  @Override
  public boolean supportsParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    var type = parameterContext.getParameter().getType();
    return type == MockWebServer.class || type == Methanol.Builder.class;
  }

  @Override
  public Object resolveParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    // TODO BUG with UseHttps!
    var type = parameterContext.getParameter().getType();
    var servers = ManagedServers.get(extensionContext);
    var executable = parameterContext.getDeclaringExecutable();
    boolean useHttps =
        executable.isAnnotationPresent(UseHttps.class)
            || extensionContext
                .getElement()
                .map(e -> e.isAnnotationPresent(UseHttps.class))
                .orElse(false);
    if (type == MockWebServer.class) {
      try {
        return servers.newServer(executable, useHttps);
      } catch (IOException e) {
        throw new ParameterResolutionException("couldn't start server", e);
      }
    } else if (type == Methanol.Builder.class) {
      return servers.newClientBuilder(executable, useHttps);
    } else {
      throw new UnsupportedOperationException("unsupported type: " + type.toString());
    }
  }

  public MockWebServer newServer(boolean useHttps) throws IOException {
    var servers = explicitServers;
    if (servers == null) {
      throw new IllegalStateException("expected beforeAll or beforeEach to be called");
    }
    return servers.newServer(null, useHttps); // use null key for default context
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
  public @interface UseHttps {}

  /**
   * Creates {@code MockWebServers} and {@code Methanol.Builder} possibly sharing the same {@code
   * SSLContext}.
   */
  private static final class ManagedServers implements CloseableResource {
    private final Map<Object, Context> contextMap = new HashMap<>();

    ManagedServers() {}

    MockWebServer newServer(@Nullable Object key, boolean useHttps) throws IOException {
      return getContext(key).newServer(useHttps);
    }

    Methanol.Builder newClientBuilder(@Nullable Object key, boolean useHttps) {
      return getContext(key).newClientBuilder(useHttps);
    }

    private Context getContext(Object key) {
      return contextMap.computeIfAbsent(key, __ -> new Context());
    }

    void shutdownAll() throws IOException {
      for (var ctx : contextMap.values()) {
        ctx.shutdownServers();
      }

      contextMap.clear();
    }

    @Override
    public void close() throws Throwable {
      shutdownAll();
    }

    static ManagedServers get(ExtensionContext context) {
      return context.getStore(EXTENSION_NAMESPACE).getOrComputeIfAbsent(ManagedServers.class);
    }

    private static final class Context {
      private final List<MockWebServer> servers = new ArrayList<>();
      private final SSLContext sslContext = localhostSslContext();

      Context() {}

      MockWebServer newServer(boolean useHttps) throws IOException {
        var server = new MockWebServer();
        if (useHttps) {
          server.useHttps(sslContext.getSocketFactory(), false);
        }
        servers.add(server);
        server.start();
        return server;
      }

      Methanol.Builder newClientBuilder(boolean useHttps) {
        return Methanol.newBuilder()
            .apply(
                builder -> {
                  if (useHttps) {
                    builder.sslContext(sslContext);
                  }
                });
      }

      void shutdownServers() throws IOException {
        for (var server : servers) {
          server.shutdown();
        }

        servers.clear();
      }
    }
  }
}
