/*
 * Copyright (c) 2024 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.store.redis;

import static com.github.mizosoft.methanol.testing.store.StoreTesting.assertEntryEquals;
import static com.github.mizosoft.methanol.testing.store.StoreTesting.edit;
import static com.github.mizosoft.methanol.testing.store.StoreTesting.view;
import static com.github.mizosoft.methanol.testing.store.StoreTesting.write;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.awaitility.Awaitility.await;

import com.github.mizosoft.methanol.internal.cache.Store.EntryReader;
import com.github.mizosoft.methanol.testing.TestUtils;
import com.github.mizosoft.methanol.testing.store.RedisClusterStoreContext;
import com.github.mizosoft.methanol.testing.store.RedisStandaloneStoreContext;
import com.github.mizosoft.methanol.testing.store.StoreConfig.StoreType;
import com.github.mizosoft.methanol.testing.store.StoreContext;
import com.github.mizosoft.methanol.testing.store.StoreExtension;
import com.github.mizosoft.methanol.testing.store.StoreExtension.StoreParameterizedTest;
import com.github.mizosoft.methanol.testing.store.StoreSpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Optional;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;

@Timeout(TestUtils.VERY_SLOW_TIMEOUT_SECONDS)
@ExtendWith(StoreExtension.class)
@EnabledIf("isRedisStandaloneOrClusterAvailable")
class RedisStoreTest {
  @BeforeAll
  static void setUp() {
    Awaitility.setDefaultPollDelay(Duration.ZERO);
    Awaitility.setDefaultPollInterval(Duration.ofMillis(20));
    Awaitility.setDefaultTimeout(Duration.ofSeconds(TestUtils.SLOW_TIMEOUT_SECONDS));
  }

  /*
   * Unfortunately, there doesn't seem to be a way to mock time in redis. So tests that want to test
   * expiry behaviour will have to sleep for >= the expiry time.
   */

  @StoreParameterizedTest
  @StoreSpec(tested = {StoreType.REDIS_STANDALONE, StoreType.REDIS_CLUSTER})
  void readValueWrittenByAnotherStore(StoreContext context) throws IOException {
    var firstStore = context.createAndRegisterStore();
    var secondStore = context.createAndRegisterStore();
    write(firstStore, "e1", "a", "b");
    assertEntryEquals(secondStore, "e1", "a", "b");
  }

  @StoreParameterizedTest
  @StoreSpec(tested = {StoreType.REDIS_STANDALONE, StoreType.REDIS_CLUSTER})
  void editValueBeingEditedByAnotherStore(StoreContext context) throws IOException {
    var firstStore = context.createAndRegisterStore();
    var secondStore = context.createAndRegisterStore();
    try (var ignored = firstStore.edit("e1").orElseThrow()) {
      assertThat(secondStore.edit("e1")).isEmpty();
    }

    // Releasing the editor lock is done asynchronously so we must retry.
    //noinspection OptionalGetWithoutIsPresent
    try (var ignored =
        await()
            .pollDelay(Duration.ZERO)
            .until(() -> secondStore.edit("e1"), Optional::isPresent)
            .get()) {
      assertThat(firstStore.edit("e1")).isEmpty();
    }
  }

  @StoreParameterizedTest
  @StoreSpec(tested = {StoreType.REDIS_STANDALONE, StoreType.REDIS_CLUSTER})
  void overwriteEntryBeingViewedByAnotherStore(StoreContext context) throws Exception {
    var firstStore = context.createAndRegisterStore();
    var secondStore = context.createAndRegisterStore();

    write(firstStore, "e1", "a", "b");

    var firstViewer = view(firstStore, "e1");
    write(secondStore, "e1", "x", "y");
    assertEntryEquals(firstViewer, "a", "b");
    assertEntryEquals(firstStore, "e1", "x", "y"); // New viewers see new data.
  }

  @StoreParameterizedTest
  @StoreSpec(
      tested = {StoreType.REDIS_STANDALONE, StoreType.REDIS_CLUSTER},
      editorLockInactiveTtlSeconds = 1)
  void expiredEdit(StoreContext context) throws Exception {
    var firstStore = context.createAndRegisterStore();
    var secondStore = context.createAndRegisterStore();

    try (var firstEditor = edit(firstStore, "e1")) {
      firstEditor.writer().write(ByteBuffer.wrap(new byte[] {'a'}));
      assertThat(secondStore.edit("e1")).isEmpty();
      Thread.sleep(1100);
      assertThatIllegalStateException()
          .isThrownBy(() -> firstEditor.writer().write(ByteBuffer.wrap(new byte[] {'a'})));
      assertThatIllegalStateException()
          .isThrownBy(() -> firstEditor.commit(ByteBuffer.wrap(new byte[] {'b'})));
    }

    // We can continue writing from the second store.
    write(secondStore, "e1", "x", "y");
    assertEntryEquals(firstStore, "e1", "x", "y");
    assertEntryEquals(secondStore, "e1", "x", "y");
  }

  @StoreParameterizedTest
  @StoreSpec(
      tested = {StoreType.REDIS_STANDALONE, StoreType.REDIS_CLUSTER},
      staleEntryInactiveTtlSeconds = 1)
  void expireStaleViewerByOverwriteBeforeStaleRead(StoreContext context) throws Exception {
    var firstStore = context.createAndRegisterStore();
    var secondStore = context.createAndRegisterStore();

    write(firstStore, "e1", "a", "bbb");

    var firstViewer = view(secondStore, "e1");
    var firstReader = firstViewer.newReader();
    assertThat(readAsciiChar(firstReader)).isEqualTo('b');
    write(secondStore, "e1", "x", "yyy"); // Stale by overwrite.
    Thread.sleep(1100);
    assertThatIllegalStateException().isThrownBy(() -> readAsciiChar(firstReader));
    assertThatIllegalStateException().isThrownBy(() -> readAsciiChar(firstViewer.newReader()));
  }

  @StoreParameterizedTest
  @StoreSpec(
      tested = {StoreType.REDIS_STANDALONE, StoreType.REDIS_CLUSTER},
      staleEntryInactiveTtlSeconds = 1)
  void expireStaleViewerByOverwriteAfterStaleRead(StoreContext context) throws Exception {
    var firstStore = context.createAndRegisterStore();
    var secondStore = context.createAndRegisterStore();

    write(firstStore, "e1", "a", "bbb");

    var firstViewer = view(secondStore, "e1");
    var firstReader = firstViewer.newReader();
    assertThat(readAsciiChar(firstReader)).isEqualTo('b');
    write(secondStore, "e1", "x", "yyy"); // Stale by overwrite.
    assertThat(readAsciiChar(firstReader)).isEqualTo('b');
    Thread.sleep(1100);
    assertThatIllegalStateException().isThrownBy(() -> readAsciiChar(firstReader));
    assertThatIllegalStateException().isThrownBy(() -> readAsciiChar(firstViewer.newReader()));
  }

  @StoreParameterizedTest
  @StoreSpec(
      tested = {StoreType.REDIS_STANDALONE, StoreType.REDIS_CLUSTER},
      staleEntryInactiveTtlSeconds = 1)
  void expireStaleViewerByRemovalBeforeStaleRead(StoreContext context) throws Exception {
    var firstStore = context.createAndRegisterStore();
    var secondStore = context.createAndRegisterStore();

    write(firstStore, "e1", "a", "bbb");

    var firstViewer = view(secondStore, "e1");
    var firstReader = firstViewer.newReader();
    assertThat(readAsciiChar(firstReader)).isEqualTo('b');
    assertThat(secondStore.remove("e1")).isTrue(); // Stale by removal.
    Thread.sleep(1100);
    assertThatIllegalStateException().isThrownBy(() -> readAsciiChar(firstReader));
    assertThatIllegalStateException().isThrownBy(() -> readAsciiChar(firstViewer.newReader()));
  }

  @StoreParameterizedTest
  @StoreSpec(
      tested = {StoreType.REDIS_STANDALONE, StoreType.REDIS_CLUSTER},
      staleEntryInactiveTtlSeconds = 1)
  void expireStaleViewerByRemovalAfterStaleRead(StoreContext context) throws Exception {
    var firstStore = context.createAndRegisterStore();
    var secondStore = context.createAndRegisterStore();

    write(firstStore, "e1", "a", "bbb");

    var firstViewer = view(secondStore, "e1");
    var firstReader = firstViewer.newReader();
    assertThat(readAsciiChar(firstReader)).isEqualTo('b');
    assertThat(secondStore.remove("e1")).isTrue(); // Stale by removal.
    assertThat(readAsciiChar(firstReader)).isEqualTo('b');
    Thread.sleep(1100);
    assertThatIllegalStateException().isThrownBy(() -> readAsciiChar(firstReader));
    assertThatIllegalStateException().isThrownBy(() -> readAsciiChar(firstViewer.newReader()));
  }

  @StoreParameterizedTest
  @StoreSpec(
      tested = {StoreType.REDIS_STANDALONE, StoreType.REDIS_CLUSTER},
      staleEntryInactiveTtlSeconds = 1)
  void expireStaleViewerByClearingBeforeStaleRead(StoreContext context) throws Exception {
    var firstStore = context.createAndRegisterStore();
    var secondStore = context.createAndRegisterStore();

    write(firstStore, "e1", "a", "bbb");

    var firstViewer = view(secondStore, "e1");
    var firstReader = firstViewer.newReader();
    assertThat(readAsciiChar(firstReader)).isEqualTo('b');
    secondStore.clear(); // Stale by clearing.
    Thread.sleep(1100);
    assertThatIllegalStateException().isThrownBy(() -> readAsciiChar(firstReader));
    assertThatIllegalStateException().isThrownBy(() -> readAsciiChar(firstViewer.newReader()));
  }

  @StoreParameterizedTest
  @StoreSpec(
      tested = {StoreType.REDIS_STANDALONE, StoreType.REDIS_CLUSTER},
      staleEntryInactiveTtlSeconds = 1)
  void expireStaleViewerByClearingAfterStaleRead(StoreContext context) throws Exception {
    var firstStore = context.createAndRegisterStore();
    var secondStore = context.createAndRegisterStore();

    write(firstStore, "e1", "a", "bbb");

    var firstViewer = view(secondStore, "e1");
    var firstReader = firstViewer.newReader();
    assertThat(readAsciiChar(firstReader)).isEqualTo('b');
    secondStore.clear(); // Stale by clearing.
    assertThat(readAsciiChar(firstReader)).isEqualTo('b');
    Thread.sleep(1100);
    assertThatIllegalStateException().isThrownBy(() -> readAsciiChar(firstReader));
    assertThatIllegalStateException().isThrownBy(() -> readAsciiChar(firstViewer.newReader()));
  }

  @StoreParameterizedTest
  @StoreSpec(tested = StoreType.REDIS_STANDALONE)
  void editorDataSizeInconsistency_redisStandalone(RedisStandaloneStoreContext context)
      throws IOException {
    var store = (AbstractRedisStore<?, ?, ?>) context.createAndRegisterStore();
    try (var editor = edit(store, "e1");
        var connection = context.connect()) {
      editor.writer().write(ByteBuffer.wrap(new byte[] {'a', 'a'}));
      connection.sync().set(store.wipDataKey(editor), "a");
      assertThatIllegalStateException()
          .isThrownBy(() -> editor.writer().write(ByteBuffer.allocate(1)));
    }
  }

  @StoreParameterizedTest
  @StoreSpec(tested = StoreType.REDIS_CLUSTER)
  void editorDataSizeInconsistency_redisCluster(RedisClusterStoreContext context)
      throws IOException {
    var store = (AbstractRedisStore<?, ?, ?>) context.createAndRegisterStore();
    try (var editor = edit(store, "e1");
        var connection = context.connect()) {
      editor.writer().write(ByteBuffer.wrap(new byte[] {'a', 'a'}));
      connection.sync().set(store.wipDataKey(editor), "a");
      assertThatIllegalStateException()
          .isThrownBy(() -> editor.writer().write(ByteBuffer.allocate(1)));
    }
  }

  private static char readAsciiChar(EntryReader reader) throws IOException {
    var buffer = ByteBuffer.allocate(1);
    assertThat(reader.read(buffer)).isOne();
    return (char) buffer.flip().get();
  }

  public static boolean isRedisStandaloneOrClusterAvailable() {
    return RedisStandaloneStoreContext.isAvailable() || RedisClusterStoreContext.isAvailable();
  }
}
