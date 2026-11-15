package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.analysis.testing.TestJavac;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.junit.jupiter.api.io.TempDir;

// The index refresh after a build scans every tree off the event loop: requests meanwhile answer
// from the index a build behind, and newer state on the loop wins over the scan.
class ReactorTypeIndexTest {

  @TempDir Path tmp;

  private final TestLoop loop = new TestLoop();
  private final HeldScans scans = new HeldScans();
  private WorkspaceState state;

  @AfterEach
  void closeLoop() {
    loop.close();
  }

  // One refresh at a time: two asked for while one scans run as one more after it.
  @Test
  void refresh_scanInFlight_loopAnswersFromTheOldIndex_thenTheNewOneApplies() throws Exception {
    final ReactorTypeIndex index = loadedIndex();
    compileIntoMirror("B");

    loop.run(index::refresh);
    final CompletableFuture<Void> rebuilt = loop.call(index::rebuilt);

    assertThat(loop.call(() -> isIndexed(index, "B")))
        .as("the old index, a build behind")
        .isFalse();
    assertThat(rebuilt).isNotDone();
    loop.run(index::refresh);
    loop.run(index::refresh);
    assertThat(scans.held()).as("one scan at a time").isEqualTo(1);

    scans.releaseOne();
    rebuilt.get(ServiceTestBase.TIMEOUT_S, TimeUnit.SECONDS);

    assertThat(loop.call(() -> isIndexed(index, "B"))).isTrue();
    // The refresh asked for meanwhile starts in the loop task that completed the first.
    loop.run(() -> {});
    assertThat(scans.held()).as("the two asked for meanwhile, as one").isEqualTo(1);
    scans.releaseOne();
    loop.call(index::rebuilt).get(ServiceTestBase.TIMEOUT_S, TimeUnit.SECONDS);
    assertThat(scans.held()).isZero();
  }

  // A write rescanning a tree while a refresh scans keeps its shard; a reload meanwhile drops the
  // refresh's result.
  @Test
  void refresh_writeOrReloadMeanwhile_theirStateStands() throws Exception {
    final ReactorTypeIndex index = loadedIndex();
    final ModuleSourceConfig module =
        loop.call(() -> List.copyOf(state.registry().allConfigs())).get(0);

    loop.run(index::refresh);
    final Path written = compileIntoMirror("Written");
    loop.run(() -> index.refreshShard(module));
    Files.delete(written);
    scans.releaseOne();
    loop.call(index::rebuilt).get(ServiceTestBase.TIMEOUT_S, TimeUnit.SECONDS);

    assertThat(loop.call(() -> isIndexed(index, "Written")))
        .as("the write's shard, not the scan that ran after the class was gone")
        .isTrue();

    loop.run(index::refresh);
    compileIntoMirror("AfterReload");
    loop.run(index::load);
    Files.delete(mirror().resolve("com/example/AfterReload.class"));
    scans.releaseOne();
    loop.call(index::rebuilt).get(ServiceTestBase.TIMEOUT_S, TimeUnit.SECONDS);

    assertThat(loop.call(() -> isIndexed(index, "AfterReload")))
        .as("the reload's scan, not the refresh's")
        .isTrue();
  }

  private ReactorTypeIndex loadedIndex() throws Exception {
    TestCompiler.writeMirroredModule(
        tmp,
        "module",
        Map.of("com/example/A.java", "package com.example; public class A {}"),
        List.of(),
        1_000L);
    state = new WorkspaceState(loop.eventLoop);
    final var index = new ReactorTypeIndex(loop.eventLoop, state, Set::of, scans);
    loop.run(
        () -> {
          state.load(tmp).close();
          index.load();
        });
    assertThat(loop.call(() -> isIndexed(index, "A"))).isTrue();
    return index;
  }

  // A class a build put in the mirror, from a source outside the module's roots.
  private Path compileIntoMirror(final String name) throws Exception {
    final Path source =
        TestCompiler.writeAt(
            tmp.resolve("outside/com/example/%s.java".formatted(name)),
            "package com.example; public class %s {}".formatted(name),
            1_000L);
    TestJavac.compileToDir(mirror(), source);
    return mirror().resolve("com/example/%s.class".formatted(name));
  }

  private Path mirror() {
    return tmp.resolve(".lathe/module/classes");
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
      held.poll(ServiceTestBase.TIMEOUT_S, TimeUnit.SECONDS).run();
    }
  }
}
