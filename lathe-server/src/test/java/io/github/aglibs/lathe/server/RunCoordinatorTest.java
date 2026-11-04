package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.launch.JdwpOptions;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.launch.TestSelectionKind;
import io.github.aglibs.lathe.core.schema.TestFilterData;
import io.github.aglibs.lathe.server.run.LaunchOutcome;
import io.github.aglibs.lathe.server.run.TestResult;
import io.github.aglibs.lathe.server.run.TranscriptLine;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunCoordinatorTest {

  // The debug readiness gate: attachDebugHost waits on the future jdwpReadyConsumer completes when
  // the JVM's JDWP "Listening for transport ..." banner is drained -- replacing the old TCP probe
  // that the agent misread as a failed handshake. Covered here at the consumer, so no suspended JVM
  // or socket is needed; a probe-based test would exercise only the removed
  // PortUtil.awaitAccepting.
  @Test
  void jdwpReadyConsumer_listeningBanner_completesReadyAndForwards() {
    final var ready = new CompletableFuture<Void>();
    final var seen = new AtomicReference<TranscriptLine>();
    final Consumer<TranscriptLine> consumer =
        RunCoordinator.jdwpReadyConsumer(new JdwpOptions(37591), ready, seen::set);

    final var banner =
        new TranscriptLine(
            TranscriptLine.Stream.STDOUT, "Listening for transport dt_socket at address: 37591");
    consumer.accept(banner);

    assertThat(ready).isCompleted();
    assertThat(seen.get()).isEqualTo(banner);
  }

  @Test
  void jdwpReadyConsumer_nonBannerLine_forwardsWithoutCompleting() {
    final var ready = new CompletableFuture<Void>();
    final var seen = new AtomicReference<TranscriptLine>();
    final Consumer<TranscriptLine> consumer =
        RunCoordinator.jdwpReadyConsumer(new JdwpOptions(37591), ready, seen::set);

    final var line = new TranscriptLine(TranscriptLine.Stream.STDOUT, "Starting AppServer");
    consumer.accept(line);

    assertThat(ready).isNotCompleted();
    assertThat(seen.get()).isEqualTo(line);
  }

  // The debug session-end outcome: attachDebugHost publishes what session.onExit() produced, or a
  // blocked outcome when the JVM died before one was read -- so the client's results wait always
  // completes. Covered here at the derivation, so no suspended JVM or DAP host is needed.
  @Test
  void finishedOutcome_completedOutcome_returnedAsIs() {
    final var completed =
        LaunchOutcome.completed(
            0,
            List.of(new TranscriptLine(TranscriptLine.Stream.STDOUT, "ok")),
            List.of(
                new TestResult(
                    "com.example.Foo",
                    "bar",
                    "",
                    "passed",
                    "",
                    -1,
                    -1,
                    "bar()",
                    null,
                    null,
                    List.of(),
                    null)));

    assertThat(RunCoordinator.finishedOutcome(completed, null)).isSameAs(completed);
  }

  @Test
  void finishedOutcome_nullOutcome_returnsBlockedNamingTheFailure() {
    final var error = new IllegalStateException("jvm crashed");

    final LaunchOutcome finished = RunCoordinator.finishedOutcome(null, error);

    assertThat(finished.launched()).isFalse();
    assertThat(finished.blockedReasons()).hasSize(1);
    assertThat(finished.blockedReasons().get(0)).contains("jvm crashed");
  }

  // A discover run's records become one entry per position id (an inherited @Nested class reaches
  // the same test twice), labelled by method, with the source file's URI when there is one.
  @Test
  void discoveredTests_discoverRecords_oneEntryPerPositionWithItsSource(@TempDir final Path dir) {
    final Path source = dir.resolve("FooTest.java");
    final List<TestResult> records =
        List.of(
            discovered("com.x.FooTest", "a", ""),
            discovered("com.x.FooTest", "a", ""),
            discovered("com.x.FooTest", "p", "java.lang.String"),
            discovered("com.x.NoSourceTest", "b", ""));

    assertThat(
            RunCoordinator.discoveredTests(
                records, Map.of("com.x.FooTest", source.toString(), "com.x.NoSourceTest", "")))
        .containsExactly(
            new DiscoveredTest("com.x.FooTest#a()", "a", LatheUri.fromPath(source)),
            new DiscoveredTest("com.x.FooTest#p(java.lang.String)", "p", LatheUri.fromPath(source)),
            new DiscoveredTest("com.x.NoSourceTest#b()", "b", ""));
  }

  private static TestResult discovered(
      final String className, final String methodName, final String parameterTypes) {
    return new TestResult(
        className,
        methodName,
        parameterTypes,
        "discovered",
        "",
        -1,
        -1,
        methodName,
        null,
        null,
        List.of(),
        null);
  }

  // A stored discovery is reused for the test classes it was found for, and only for those; a
  // blocked one (no capture yet) is not stored.
  @Test
  void readDiscovery_storedForTheSameOrOtherTestClasses_reusedOnlyWhenTheStampMatches(
      @TempDir final Path dir) {
    final Path file = dir.resolve("module/test-discovery.json");
    final FileTime stamp = FileTime.fromMillis(1_000);
    final var discovery =
        new TestDiscovery("module", List.of(), List.of(new DiscoveredTest("a.T#t()", "t", "")));
    final var blocked = new TestDiscovery("other", List.of("no capture"), List.of());

    RunCoordinator.writeDiscovery(file, stamp, discovery);
    RunCoordinator.writeDiscovery(dir.resolve("other/test-discovery.json"), stamp, blocked);

    assertThat(RunCoordinator.readDiscovery(file, stamp)).contains(discovery);
    assertThat(RunCoordinator.readDiscovery(file, FileTime.fromMillis(2_000))).isEmpty();
    assertThat(RunCoordinator.readDiscovery(file, null)).isEmpty();
    assertThat(dir.resolve("other/test-discovery.json")).doesNotExist();
  }

  // A package or module run takes the build's class-name filter; a run naming a class or a method,
  // alone or among packages, runs exactly what it names.
  @Test
  void withClassNameFilter_packageModuleOrNamedRuns_filtersOnlyTheWideOnes() {
    final var filter = new TestFilterData(List.of(".*Test"), List.of(".*\\$.*"));
    final var pkg = new TestSelection(TestSelectionKind.PACKAGE, "com.x");
    final var module = new TestSelection(TestSelectionKind.MODULE, "com.x.mod");
    final var cls = new TestSelection(TestSelectionKind.CLASS, "com.x.FooIT");
    final var method = new TestSelection(TestSelectionKind.METHOD, "com.x.FooIT#a()");

    assertThat(RunCoordinator.withClassNameFilter(List.of(pkg, module), filter))
        .containsExactly(
            pkg,
            module,
            new TestSelection(TestSelectionKind.CLASS_NAME_INCLUDE, ".*Test"),
            new TestSelection(TestSelectionKind.CLASS_NAME_EXCLUDE, ".*\\$.*"));
    assertThat(RunCoordinator.withClassNameFilter(List.of(cls), filter)).containsExactly(cls);
    assertThat(RunCoordinator.withClassNameFilter(List.of(method), filter)).containsExactly(method);
    assertThat(RunCoordinator.withClassNameFilter(List.of(pkg, cls), filter))
        .containsExactly(pkg, cls);
  }
}
