package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.launch.JdwpOptions;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import io.github.aglibs.lathe.server.run.LaunchOutcome;
import io.github.aglibs.lathe.server.run.TestResult;
import io.github.aglibs.lathe.server.run.TranscriptLine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceSessionTest {

  @TempDir private Path tmp;

  private Path sourceRoot;
  private Path sourceFile;
  private ModuleSourceConfig config;
  private Path outputDir;

  @BeforeEach
  void setUp() throws IOException {
    sourceRoot = tmp.resolve("module/src/main/java");
    sourceFile = sourceRoot.resolve("com/example/Foo.java");
    config = config(sourceRoot);
    outputDir = config.latheClassesDir().resolve("com/example");
    Files.createDirectories(outputDir);
  }

  @Test
  void deleteClassOutputs_javaSource_removesTopLevelAndNestedClassFiles() throws Exception {
    Files.writeString(outputDir.resolve("Foo.class"), "");
    Files.writeString(outputDir.resolve("Foo$Inner.class"), "");
    Files.writeString(outputDir.resolve("Foo$1.class"), "");
    Files.writeString(outputDir.resolve("Foobar.class"), "");
    Files.writeString(outputDir.resolve("Bar.class"), "");

    final int deleted = WorkspaceSession.deleteClassOutputs(config, sourceFile);

    assertThat(deleted).isEqualTo(3);
    assertThat(outputDir.resolve("Foo.class")).doesNotExist();
    assertThat(outputDir.resolve("Foo$Inner.class")).doesNotExist();
    assertThat(outputDir.resolve("Foo$1.class")).doesNotExist();
    assertThat(outputDir.resolve("Foobar.class")).exists();
    assertThat(outputDir.resolve("Bar.class")).exists();
  }

  @Test
  void deleteClassOutputs_nonJavaSource_removesNothing() throws Exception {
    final var txtFile = tmp.resolve("module/src/main/java/com/example/Foo.txt");
    Files.writeString(outputDir.resolve("Foo.class"), "");

    final int deleted = WorkspaceSession.deleteClassOutputs(config, txtFile);

    assertThat(deleted).isZero();
    assertThat(outputDir.resolve("Foo.class")).exists();
  }

  @Test
  void deleteStaleClassOutputs_namedInnerClassRemoved_deletesStaleClassFile() throws Exception {
    Files.writeString(outputDir.resolve("Foo.class"), "");
    Files.writeString(outputDir.resolve("Foo$Inner.class"), "");

    final int deleted =
        WorkspaceSession.deleteStaleClassOutputs(config, sourceFile, Set.of("com.example.Foo"));

    assertThat(deleted).isEqualTo(1);
    assertThat(outputDir.resolve("Foo.class")).exists();
    assertThat(outputDir.resolve("Foo$Inner.class")).doesNotExist();
  }

  @Test
  void deleteStaleClassOutputs_anonymousClassRemoved_deletesStaleClassFile() throws Exception {
    Files.writeString(outputDir.resolve("Foo.class"), "");
    Files.writeString(outputDir.resolve("Foo$1.class"), "");

    final int deleted =
        WorkspaceSession.deleteStaleClassOutputs(config, sourceFile, Set.of("com.example.Foo"));

    assertThat(deleted).isEqualTo(1);
    assertThat(outputDir.resolve("Foo.class")).exists();
    assertThat(outputDir.resolve("Foo$1.class")).doesNotExist();
  }

  @Test
  void deleteStaleClassOutputs_outerClass_isUntouched() throws Exception {
    Files.writeString(outputDir.resolve("Foo.class"), "");

    final int deleted =
        WorkspaceSession.deleteStaleClassOutputs(config, sourceFile, Set.of("com.example.Foo"));

    assertThat(deleted).isZero();
    assertThat(outputDir.resolve("Foo.class")).exists();
  }

  @Test
  void deleteStaleClassOutputs_sibling_isUntouched() throws Exception {
    Files.writeString(outputDir.resolve("Foo.class"), "");
    Files.writeString(outputDir.resolve("Foo$Inner.class"), "");
    Files.writeString(outputDir.resolve("Bar.class"), "");

    WorkspaceSession.deleteStaleClassOutputs(config, sourceFile, Set.of("com.example.Foo"));

    assertThat(outputDir.resolve("Bar.class")).exists();
  }

  // GAP: package-private sibling types (e.g. `class Helper {}` co-declared in Foo.java) produce
  // Helper.class with no Foo$ prefix; deleteStaleClassOutputs only considers Foo$* files and
  // cannot identify Helper.class as stale without sidecar tracking.
  @Disabled
  @Test
  void deleteStaleClassOutputs_packagePrivateSiblingRemoved_deletesStaleClassFile()
      throws Exception {
    Files.writeString(outputDir.resolve("Foo.class"), "");
    Files.writeString(outputDir.resolve("Helper.class"), "");

    final int deleted =
        WorkspaceSession.deleteStaleClassOutputs(config, sourceFile, Set.of("com.example.Foo"));

    assertThat(deleted).isEqualTo(1);
    assertThat(outputDir.resolve("Foo.class")).exists();
    assertThat(outputDir.resolve("Helper.class")).doesNotExist();
  }

  @Test
  void newestStaleMtime_returnsNewestSourceWhoseClassIsStaleOrMissing() throws Exception {
    writeClass("Edited", 1_000L); // compiled, then edited after → stale
    writeJava("Edited", 5_000L);
    writeJava("Added", 9_000L); // never compiled (a newly added file) → stale, and the newest
    writeClass("Fresh", 8_000L); // compiled after its last edit → up to date, ignored
    writeJava("Fresh", 2_000L);

    assertThat(WorkspaceSession.newestStaleMtime(List.of(config), Set.of())).isEqualTo(9_000L);
  }

  @Test
  void newestStaleMtime_ignoresOpenFilesGeneratedRootsAndPackageInfo() throws Exception {
    final var open = writeJava("Open", 9_000L); // stale (no class) but open → the editor owns it
    writeJava("package-info", 9_500L); // no <name>.class ever → excluded, though it is the newest
    writeJava("Real", 5_000L); // stale, and the only source that should count

    // A second module whose sole source root IS its annotation-processor output: wholly excluded.
    final var genRoot = tmp.resolve("gen-module/target/generated-sources/annotations");
    TestCompiler.writeAt(genRoot.resolve("com/example/Gen.java"), "", 8_000L);
    final var genConfig =
        TestCompiler.moduleConfig(
            tmp.resolve(".lathe/gen-module"),
            tmp.resolve("gen-module/target/classes"),
            genRoot,
            genRoot);

    assertThat(WorkspaceSession.newestStaleMtime(List.of(config, genConfig), Set.of(open)))
        .isEqualTo(5_000L);
  }

  @Test
  void isInPackageScope_generatedSourcesCandidate_reactorScope_inScope() {
    // FR-012/FR-013: a reactor-scoped search uses a null packageRel; the generated builder lives
    // under the generated-sources root, never under a regular source root, yet must stay in scope.
    final var sourceRoot = tmp.resolve("module/src/main/java");
    final var genRoot = tmp.resolve("module/target/generated-sources/annotations");
    final var genCandidate = genRoot.resolve("com/example/FooBuilder.java");
    final List<Path> roots = ReferenceCandidatePlanner.packageSearchRoots(configWithGen(genRoot));

    assertThat(WorkspaceSession.isInPackageScope(genCandidate, roots, null)).isTrue();
    assertThat(
            WorkspaceSession.isInPackageScope(
                sourceRoot.resolve("com/example/Foo.java"), roots, null))
        .isTrue();
  }

  @Test
  void isInPackageScope_pathOutsideEverySearchRoot_notInScope() {
    final var genRoot = tmp.resolve("module/target/generated-sources/annotations");
    final var outside = tmp.resolve("other-module/target/classes/com/example/Bar.java");
    final List<Path> roots = ReferenceCandidatePlanner.packageSearchRoots(configWithGen(genRoot));

    assertThat(WorkspaceSession.isInPackageScope(outside, roots, null)).isFalse();
  }

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
        WorkspaceSession.jdwpReadyConsumer(new JdwpOptions(37591), ready, seen::set);

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
        WorkspaceSession.jdwpReadyConsumer(new JdwpOptions(37591), ready, seen::set);

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
            List.of(new TestResult("com.example.Foo", "bar", "", "passed", "", -1, null)));

    assertThat(WorkspaceSession.finishedOutcome(completed, null)).isSameAs(completed);
  }

  @Test
  void finishedOutcome_nullOutcome_returnsBlockedNamingTheFailure() {
    final var error = new IllegalStateException("jvm crashed");

    final LaunchOutcome finished = WorkspaceSession.finishedOutcome(null, error);

    assertThat(finished.launched()).isFalse();
    assertThat(finished.blockedReasons()).hasSize(1);
    assertThat(finished.blockedReasons().getFirst()).contains("jvm crashed");
  }

  private Path writeJava(final String typeName, final long mtime) throws IOException {
    return TestCompiler.writeAt(sourceRoot.resolve("com/example/" + typeName + ".java"), "", mtime);
  }

  private void writeClass(final String typeName, final long mtime) throws IOException {
    TestCompiler.writeAt(outputDir.resolve(typeName + ".class"), "", mtime);
  }

  private ModuleSourceConfig config(final Path sourceRoot) {
    return TestCompiler.moduleConfig(
        tmp.resolve(".lathe/module"), tmp.resolve("module/target/classes"), sourceRoot);
  }

  private ModuleSourceConfig configWithGen(final Path genRoot) {
    return TestCompiler.moduleConfig(
        tmp.resolve(".lathe/module"),
        tmp.resolve("module/target/classes"),
        tmp.resolve("module/src/main/java"),
        genRoot);
  }
}
