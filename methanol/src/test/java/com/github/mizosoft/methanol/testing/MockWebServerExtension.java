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

import static com.github.mizosoft.methanol.testutils.TestUtils.localhostSslContext;

import com.github.mizosoft.methanol.Methanol;
import com.github.mizosoft.methanol.Methanol.Builder;
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
import mockwebserver3.MockWebServer;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * An extension that provides plain or secure {@code MockWebServers} and {@code Methanol.Builder}
 * either explicitly or by resolving parameters.
 */
public final class MockWebServerExtension
    implements AfterAllCallback, AfterEachCallback, ParameterResolver {
  private static final Namespace EXTENSION_NAMESPACE =
      Namespace.create(MockWebServerExtension.class);

  public MockWebServerExtension() {}

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
    return type == MockWebServer.class || type == Builder.class;
  }

  @Override
  public Object resolveParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    var type = parameterContext.getParameter().getType();
    var servers = ManagedServers.get(extensionContext);
    var executable = parameterContext.getDeclaringExecutable();
    boolean useHttps =
        AnnotationSupport.isAnnotated(executable, UseHttps.class)
            || AnnotationSupport.isAnnotated(extensionContext.getElement(), UseHttps.class);
    if (type == MockWebServer.class) {
      try {
        return servers.newServer(executable, useHttps);
      } catch (IOException e) {
        throw new ParameterResolutionException("couldn't start server", e);
      }
    } else if (type == Builder.class) {
      return servers.newClientBuilder(executable, useHttps);
    } else {
      throw new UnsupportedOperationException("unsupported type: " + type.toString());
    }
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

    Builder newClientBuilder(@Nullable Object key, boolean useHttps) {
      return getContext(key).newClientBuilder(useHttps);
    }

    private Context getContext(Object key) {
      return contextMap.computeIfAbsent(key, __ -> new Context());
    }

    void shutdownAll() throws IOException {
      for (var context : contextMap.values()) {
        context.shutdownServers();
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

      MockWebServer newServer(boolean useHttps) {
        var server = new MockWebServer();
        if (useHttps) {
          server.useHttps(sslContext.getSocketFactory(), false);
        }
        servers.add(server);
        return server;
      }

      Builder newClientBuilder(boolean useHttps) {
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
