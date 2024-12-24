/*
 * Copyright (c) 2024 Moataz Hussein
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

package com.github.mizosoft.methanol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.TestUtils;
import java.lang.reflect.InvocationTargetException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

@EnabledForJreRange(min = JRE.JAVA_21)
@Timeout(TestUtils.SLOW_TIMEOUT_SECONDS) // Mockito may take some time to load.
class MethanolJava21OrLaterCompatibilityTest {
  @Test
  void shutdown() throws Exception {
    var backend = mockClient();
    var client = Methanol.newBuilder(backend).build();
    var shutdown = HttpClient.class.getMethod("shutdown");
    shutdown.invoke(client);
    shutdown.invoke(verify(backend, times(1)));
  }

  @Test
  void shutdownWithRuntimeException() throws Exception {
    var backend = mockClient();
    var shutdown = HttpClient.class.getMethod("shutdown");
    shutdown.invoke(doThrow(new TestException()).when(backend));

    var client = Methanol.newBuilder(backend).build();
    assertThatThrownBy(() -> shutdown.invoke(client))
        .isInstanceOf(InvocationTargetException.class)
        .hasCauseInstanceOf(TestException.class);
    shutdown.invoke(verify(backend, times(1)));
  }

  @Test
  void awaitTermination() throws Exception {
    var backend = mockClient();
    var client = Methanol.newBuilder(backend).build();
    var awaitTermination = HttpClient.class.getMethod("awaitTermination", Duration.class);
    awaitTermination.invoke(client, Duration.ofSeconds(1));
    awaitTermination.invoke(verify(backend, times(1)), Duration.ofSeconds(1));
  }

  @Test
  void awaitTerminationWithRuntimeException() throws Exception {
    var backend = mockClient();
    var awaitTermination = HttpClient.class.getMethod("awaitTermination", Duration.class);
    awaitTermination.invoke(doThrow(new TestException()).when(backend), Duration.ofSeconds(1));

    var client = Methanol.newBuilder(backend).build();
    assertThatThrownBy(() -> awaitTermination.invoke(client, Duration.ofSeconds(1)))
        .isInstanceOf(InvocationTargetException.class)
        .hasCauseInstanceOf(TestException.class);
    awaitTermination.invoke(verify(backend, times(1)), Duration.ofSeconds(1));
  }

  @Test
  void awaitTerminationWithInterruptedExceptionException() throws Exception {
    var backend = mockClient();
    var awaitTermination = HttpClient.class.getMethod("awaitTermination", Duration.class);
    awaitTermination.invoke(
        doThrow(new InterruptedException()).when(backend), Duration.ofSeconds(1));

    var client = Methanol.newBuilder(backend).build();
    assertThatThrownBy(() -> awaitTermination.invoke(client, Duration.ofSeconds(1)))
        .isInstanceOf(InvocationTargetException.class)
        .hasCauseInstanceOf(InterruptedException.class);
    awaitTermination.invoke(verify(backend, times(1)), Duration.ofSeconds(1));
  }

  @Test
  void isTerminated() throws Exception {
    var backend = mockClient();
    var isTerminated = HttpClient.class.getMethod("isTerminated");
    when(isTerminated.invoke(backend)).thenReturn(true);

    var client = Methanol.newBuilder(backend).build();
    assertThat(isTerminated.invoke(client)).isEqualTo(Boolean.TRUE);
    isTerminated.invoke(verify(backend, times(1)));
  }

  @Test
  void isTerminatedWithRuntimeException() throws Exception {
    var backend = mockClient();
    var isTerminated = HttpClient.class.getMethod("isTerminated");
    isTerminated.invoke(doThrow(new TestException()).when(backend));

    var client = Methanol.newBuilder(backend).build();
    assertThatThrownBy(() -> isTerminated.invoke(client))
        .isInstanceOf(InvocationTargetException.class)
        .hasCauseInstanceOf(TestException.class);
    isTerminated.invoke(verify(backend, times(1)));
  }

  @Test
  void shutdownNow() throws Exception {
    var backend = mockClient();
    var shutdownNow = HttpClient.class.getMethod("shutdownNow");
    var client = Methanol.newBuilder(backend).build();
    shutdownNow.invoke(client);
    shutdownNow.invoke(verify(backend, times(1)));
  }

  @Test
  void shutdownNowWithRuntimeException() throws Exception {
    var backend = mockClient();
    var shutdownNow = HttpClient.class.getMethod("shutdownNow");
    shutdownNow.invoke(doThrow(new TestException()).when(backend));

    var client = Methanol.newBuilder(backend).build();
    assertThatThrownBy(() -> shutdownNow.invoke(client))
        .isInstanceOf(InvocationTargetException.class)
        .hasCauseInstanceOf(TestException.class);
    shutdownNow.invoke(verify(backend, times(1)));
  }

  private static HttpClient mockClient() {
    var client = mock(HttpClient.class);
    when(client.followRedirects())
        .thenReturn(HttpClient.Redirect.NEVER); // Accessed by Methanol's constructor.
    return client;
  }
}
