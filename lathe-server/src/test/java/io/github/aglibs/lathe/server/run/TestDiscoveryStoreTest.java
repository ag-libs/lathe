package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestDiscoveryStoreTest {

  @TempDir private Path tmp;

  // A stored discovery is reused for the test classes it was found for, and only for those; a
  // blocked one (no capture yet) is not stored.
  @Test
  void read_storedForTheSameOrOtherTestClasses_reusedOnlyWhenTheStampMatches() {
    final Path file = tmp.resolve("module/test-discovery.json");
    final FileTime stamp = FileTime.fromMillis(1_000);
    final var discovery =
        new TestDiscovery("module", List.of(), List.of(new DiscoveredTest("a.T#t()", "t", "")));
    final var blocked = new TestDiscovery("other", List.of("no capture"), List.of());

    TestDiscoveryStore.write(file, stamp, discovery);
    TestDiscoveryStore.write(tmp.resolve("other/test-discovery.json"), stamp, blocked);

    assertThat(TestDiscoveryStore.read(file, stamp)).contains(discovery);
    assertThat(TestDiscoveryStore.read(file, FileTime.fromMillis(2_000))).isEmpty();
    assertThat(TestDiscoveryStore.read(file, null)).isEmpty();
    assertThat(tmp.resolve("other/test-discovery.json")).doesNotExist();
  }

  // A module's discovery is found once for its test classes, then kept, also across stores (a new
  // session); new test classes find it again.
  @Test
  void discovery_sameOrNewTestClasses_discoveredOnlyWhenTheyChange() throws Exception {
    final Path stamps =
        Files.createDirectories(tmp.resolve(LatheLayout.moduleDir("app")))
            .resolve(LatheLayout.compiledStampsFileName(LatheLayout.TEST_CLASSES_DIR));
    Files.writeString(stamps, "{}");
    Files.setLastModifiedTime(stamps, FileTime.fromMillis(1_000));
    final var found =
        new TestDiscovery("app", List.of(), List.of(new DiscoveredTest("a.T#t()", "t", "")));
    final var launches = new AtomicInteger();
    final var store = new TestDiscoveryStore();

    store.discovery(tmp, "app", () -> discovered(launches, found));
    store.discovery(tmp, "app", () -> discovered(launches, found));
    new TestDiscoveryStore().discovery(tmp, "app", () -> discovered(launches, found));
    assertThat(launches).hasValue(1);

    Files.setLastModifiedTime(stamps, FileTime.fromMillis(2_000));
    assertThat(store.discovery(tmp, "app", () -> discovered(launches, found)))
        .isCompletedWithValue(found);
    assertThat(launches).hasValue(2);
  }

  // A blocked or failed discovery is not kept: the next request finds it again, though the test
  // classes are unchanged.
  @Test
  void discovery_blockedOrFailed_foundAgainOnTheNextRequest() {
    final var blocked = new TestDiscovery("app", List.of("no capture"), List.of());
    final var found =
        new TestDiscovery("app", List.of(), List.of(new DiscoveredTest("a.T#t()", "t", "")));
    final var store = new TestDiscoveryStore();

    store.discovery(tmp, "app", () -> CompletableFuture.completedFuture(blocked));
    store.discovery(
        tmp, "failed", () -> CompletableFuture.failedFuture(new IllegalStateException()));

    assertThat(store.discovery(tmp, "app", () -> CompletableFuture.completedFuture(found)))
        .isCompletedWithValue(found);
    assertThat(store.discovery(tmp, "failed", () -> CompletableFuture.completedFuture(found)))
        .isCompletedWithValue(found);
  }

  private static CompletableFuture<TestDiscovery> discovered(
      final AtomicInteger launches, final TestDiscovery found) {
    launches.incrementAndGet();
    return CompletableFuture.completedFuture(found);
  }
}
