package io.github.aglibs.lathe.server;

import static io.github.aglibs.lathe.server.ServiceTestBase.TIMEOUT_S;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.aglibs.lathe.analysis.testing.TestJavac;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

// The index refresh after a build scans every tree off the event loop: requests meanwhile answer
// from the index a build behind, and newer state on the loop wins over the scan.
class ReactorTypeIndexTest {

  private static final long SCAN_WAIT_MS = 50;

  @TempDir Path tmp;

  private final TestLoop loop = new TestLoop();
  private final HeldScans scans = new HeldScans();
  // Read on the loop only, as the session's open documents are.
  private final Set<Path> openSources = new HashSet<>();
  private WorkspaceState state;

  @AfterEach
  void closeLoop() {
    loop.close();
  }

  // One refresh at a time: two asked for while one scans run as one more after it, and an answer
  // waiting on the first waits on that one too.
  @Test
  void refresh_scanInFlight_loopAnswersFromTheOldIndex_thenTheNewOneApplies() throws Exception {
    final ReactorTypeIndex index = loadedIndex("module");
    compileIntoMirror("module", "B");

    loop.run(index::refresh);
    final CompletableFuture<Void> rebuilt = loop.call(index::rebuilt);

    assertThat(loop.call(() -> isIndexed(index, "B")))
        .as("the old index, a build behind")
        .isFalse();
    loop.run(index::refresh);
    loop.run(index::refresh);
    assertThat(scans.held()).as("one scan at a time").isEqualTo(1);

    scans.releaseOne();
    loop.run(() -> {});

    assertThat(loop.call(() -> isIndexed(index, "B"))).isTrue();
    assertThat(scans.held()).as("the two asked for meanwhile, as one").isEqualTo(1);
    assertThat(rebuilt).as("waits on the refresh asked for meanwhile").isNotDone();
    scans.releaseOne();
    rebuilt.get(TIMEOUT_S, TimeUnit.SECONDS);
    assertThat(scans.held()).isZero();
  }

  // A write rescanning a tree while a refresh scans keeps its shard; a reload meanwhile drops the
  // refresh's result.
  @Test
  void refresh_writeOrReloadMeanwhile_theirStateStands() throws Exception {
    final ReactorTypeIndex index = loadedIndex("module");
    final ModuleSourceConfig module = onlyConfig();

    loop.run(index::refresh);
    final Path written = compileIntoMirror("module", "Written");
    loop.run(() -> index.refreshShard(module));
    Files.delete(written);
    awaitRefresh(index);

    assertThat(loop.call(() -> isIndexed(index, "Written")))
        .as("the write's shard, not the scan that ran after the class was gone")
        .isTrue();

    loop.run(index::refresh);
    final Path afterReload = compileIntoMirror("module", "AfterReload");
    loop.run(index::load);
    Files.delete(afterReload);
    awaitRefresh(index);

    assertThat(loop.call(() -> isIndexed(index, "AfterReload")))
        .as("the reload's scan, not the refresh's")
        .isTrue();
  }

  // An external-output tree's write rebuilds its shard from the cached base, so the scan in flight,
  // which read the build's new output, is the newer one.
  @Test
  void refresh_externalOutputTreeWrittenMeanwhile_takesTheScan() throws Exception {
    final Path output = tmp.resolve("build/ext/classes");
    TestJavac.compileToDir(output, outsideSource("A"));
    TestCompiler.writeExternalOutputParams(tmp, "ext", tmp.resolve("ext/src"), output);
    final ReactorTypeIndex index = loadedIndex();
    final ModuleSourceConfig ext = onlyConfig();

    loop.run(index::refresh);
    TestJavac.compileToDir(output, outsideSource("Built"));
    loop.run(() -> index.refreshShard(ext));
    awaitRefresh(index);

    assertThat(loop.call(() -> isIndexed(index, "Built"))).isTrue();
  }

  // The server's own writes run beside a refresh's scan: a tree whose walk fails keeps its last
  // scan, and the other trees take theirs.
  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "an unreadable directory needs POSIX modes")
  void refresh_treeScanFails_keepsItsLastScan_othersTakeTheirs() throws Exception {
    assumeFalse("root".equals(System.getProperty("user.name")), "root reads any directory");
    TestCompiler.writeMirroredModule(
        tmp,
        "two",
        Map.of("com/example/Two.java", "package com.example; public class Two {}"),
        List.of(),
        1_000L);
    final ReactorTypeIndex index = loadedIndex("module");
    final Path locked = Files.createDirectories(mirror("module").resolve("com/example/locked"));

    loop.run(index::refresh);
    compileIntoMirror("two", "Newer");
    Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
    try {
      awaitRefresh(index);
    } finally {
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
    }

    assertThat(loop.call(() -> isIndexed(index, "A"))).as("the failed tree's last scan").isTrue();
    assertThat(loop.call(() -> isIndexed(index, "Newer"))).isTrue();
  }

  // A document opened while a refresh scans brings its tree types no build has written, which the
  // scan's snapshot of the open documents missed: one more refresh takes them in. None follows a
  // scan the open documents did not change under.
  @Test
  void refresh_documentOpenedMeanwhile_refreshesAgain() throws Exception {
    final ReactorTypeIndex index = loadedIndex("module");

    loop.run(index::refresh);
    scans.releaseOne();
    loop.run(() -> {});
    assertThat(scans.held()).as("no change meanwhile").isZero();

    loop.run(index::refresh);
    loop.run(() -> openSources.add(tmp.resolve("module/src/main/java/com/example/Opened.java")));
    scans.releaseOne();
    loop.run(() -> {});

    assertThat(scans.held()).as("one more refresh").isEqualTo(1);
    awaitRefresh(index);
  }

  // The workspace with a mirrored module of that name holding com.example.A, loaded.
  private ReactorTypeIndex loadedIndex(final String module) throws Exception {
    TestCompiler.writeMirroredModule(
        tmp,
        module,
        Map.of("com/example/A.java", "package com.example; public class A {}"),
        List.of(),
        1_000L);
    final ReactorTypeIndex index = loadedIndex();
    assertThat(loop.call(() -> isIndexed(index, "A"))).isTrue();
    return index;
  }

  private ReactorTypeIndex loadedIndex() throws Exception {
    state = new WorkspaceState(loop.eventLoop);
    final var index = new ReactorTypeIndex(loop.eventLoop, state, () -> openSources, scans);
    loop.run(
        () -> {
          state.load(tmp).close();
          index.load();
        });
    return index;
  }

  private ModuleSourceConfig onlyConfig() throws Exception {
    return loop.call(() -> List.copyOf(state.registry().allConfigs())).get(0);
  }

  // Releases every scan the refresh, and one it asks for meanwhile, hands over, and waits for it.
  // The index rebuild after the last scan runs on the common pool with no scan held, so each wait
  // for a scan is short: a full-timeout wait there held every such test for 30s.
  private void awaitRefresh(final ReactorTypeIndex index) throws Exception {
    final CompletableFuture<Void> rebuilt = loop.call(index::rebuilt);
    final var waiting = Stopwatch.start();
    while (!rebuilt.isDone() && waiting.elapsedMs() < TimeUnit.SECONDS.toMillis(TIMEOUT_S)) {
      scans.releaseWithin(SCAN_WAIT_MS);
      loop.run(() -> {});
    }

    rebuilt.get(TIMEOUT_S, TimeUnit.SECONDS);
  }

  // A class a build put in the module's mirror, from a source outside the module's roots.
  private Path compileIntoMirror(final String module, final String name) throws Exception {
    TestJavac.compileToDir(mirror(module), outsideSource(name));
    return mirror(module).resolve("com/example/%s.class".formatted(name));
  }

  private Path outsideSource(final String name) throws Exception {
    return TestCompiler.writeAt(
        tmp.resolve("outside/com/example/%s.java".formatted(name)),
        "package com.example; public class %s {}".formatted(name),
        1_000L);
  }

  private Path mirror(final String module) {
    return tmp.resolve(".lathe/%s/classes".formatted(module));
  }

  private static boolean isIndexed(final ReactorTypeIndex index, final String name) {
    return index.current().isReactorType("com.example.%s".formatted(name));
  }

  // Runs each scan only when the test releases it, on the test thread.
  private static final class HeldScans implements Executor {

    private final BlockingQueue<Runnable> held = new LinkedBlockingQueue<>();

    @Override
    public void execute(final Runnable task) {
      held.add(task);
    }

    int held() {
      return held.size();
    }

    void releaseOne() throws Exception {
      releaseWithin(TimeUnit.SECONDS.toMillis(TIMEOUT_S));
    }

    void releaseWithin(final long millis) throws Exception {
      final Runnable task = held.poll(millis, TimeUnit.MILLISECONDS);
      if (task != null) {
        task.run();
      }
    }
  }
}
