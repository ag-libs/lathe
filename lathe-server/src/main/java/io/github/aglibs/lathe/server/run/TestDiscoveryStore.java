package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.validcheck.ValidCheck;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

// The test discoveries of a workspace's modules, valid while a module's test classes are unchanged
// (sync and save compiles rewrite its test-classes stamp): kept in memory, and on disk so a new
// session reuses them instead of launching a JVM per module again. Owned by the run coordinator,
// on the event loop.
public final class TestDiscoveryStore {

  private final Map<String, CachedDiscovery> discoveries = new HashMap<>();

  // The module's discovery for its current test classes: the one kept, else the one stored, else
  // a new one from discover, stored once it completes. A kept one that failed or was blocked is
  // found again: what blocked it (no capture yet) may be gone without the test classes changing.
  public CompletableFuture<TestDiscovery> discovery(
      final Path workspaceRoot,
      final String moduleRel,
      final Supplier<CompletableFuture<TestDiscovery>> discover) {
    final Path moduleDir = workspaceRoot.resolve(LatheLayout.moduleDir(moduleRel));
    final FileTime stamp = testClassesStamp(moduleDir);
    final CachedDiscovery cached = discoveries.get(moduleRel);
    if (cached != null && Objects.equals(cached.stamp(), stamp) && !spent(cached.result())) {
      return cached.result();
    }

    final Path file = moduleDir.resolve(LatheLayout.TEST_DISCOVERY_FILE);
    final CompletableFuture<TestDiscovery> result =
        read(file, stamp)
            .map(CompletableFuture::completedFuture)
            .orElseGet(() -> discover.get().thenApply(found -> write(file, stamp, found)));
    discoveries.put(moduleRel, new CachedDiscovery(stamp, result));
    return result;
  }

  private static boolean spent(final CompletableFuture<TestDiscovery> result) {
    return result.isCompletedExceptionally()
        || (result.isDone() && !result.getNow(null).blockedReasons().isEmpty());
  }

  // The stored discovery, when it was found for the module's current test classes.
  static Optional<TestDiscovery> read(final Path file, final FileTime stamp) {
    if (stamp == null || !Files.exists(file)) {
      return Optional.empty();
    }

    final PersistedDiscovery persisted =
        IOUtil.unchecked(() -> Json.read(file, PersistedDiscovery.class));
    return persisted.stampMillis() == stamp.toMillis()
        ? Optional.of(persisted.discovery())
        : Optional.empty();
  }

  // Stores a complete discovery; a blocked one (no capture yet) is not worth keeping.
  static TestDiscovery write(final Path file, final FileTime stamp, final TestDiscovery discovery) {
    if (stamp != null && discovery.blockedReasons().isEmpty()) {
      IOUtil.unchecked(
          () ->
              FileUtil.writeIfChanged(
                  file.getParent(),
                  file,
                  Json.toJson(new PersistedDiscovery(stamp.toMillis(), discovery)),
                  false));
    }

    return discovery;
  }

  private static FileTime testClassesStamp(final Path moduleDir) {
    final Path stamps =
        moduleDir.resolve(LatheLayout.compiledStampsFileName(LatheLayout.TEST_CLASSES_DIR));
    return Files.exists(stamps) ? IOUtil.unchecked(() -> Files.getLastModifiedTime(stamps)) : null;
  }

  private record CachedDiscovery(FileTime stamp, CompletableFuture<TestDiscovery> result) {

    CachedDiscovery {
      ValidCheck.check().notNull(result, "result").validate();
    }
  }

  // A module's discovery on disk with the test-classes stamp it was found for.
  private record PersistedDiscovery(long stampMillis, TestDiscovery discovery) {

    PersistedDiscovery {
      ValidCheck.check().notNull(discovery, "discovery").validate();
    }
  }
}
