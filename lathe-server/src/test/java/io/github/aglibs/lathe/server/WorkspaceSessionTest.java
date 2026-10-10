package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.launch.JdwpOptions;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import io.github.aglibs.lathe.server.run.LaunchOutcome;
import io.github.aglibs.lathe.server.run.TestResult;
import io.github.aglibs.lathe.server.run.TranscriptLine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
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

    final int deleted = WorkspaceSession.deleteClassOutputs(config, sourceFile, Set.of());

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

    final int deleted = WorkspaceSession.deleteClassOutputs(config, txtFile, Set.of());

    assertThat(deleted).isZero();
    assertThat(outputDir.resolve("Foo.class")).exists();
  }

  @Test
  void deleteOrphanedClassOutputs_deletedSource_removesClassesAndPrunesStamp() throws Exception {
    writeJava("Live", 2_000L);
    Files.writeString(outputDir.resolve("Live.class"), "");
    Files.writeString(outputDir.resolve("Ghost.class"), "");
    Files.writeString(outputDir.resolve("Ghost$Inner.class"), "");
    writeStamps(Map.of("com/example/Live.java", 2_000L, "com/example/Ghost.java", 1_000L));

    final int removed = WorkspaceSession.deleteOrphanedClassOutputs(config);

    assertThat(removed).isEqualTo(2);
    assertThat(outputDir.resolve("Ghost.class")).doesNotExist();
    assertThat(outputDir.resolve("Ghost$Inner.class")).doesNotExist();
    assertThat(outputDir.resolve("Live.class")).exists();
    assertThat(CompiledStamps.load(config.moduleDir(), config.sourceTree()))
        .containsOnlyKeys("com/example/Live.java");
  }

  @Test
  void deleteOrphanedClassOutputs_allSourcesPresent_noOpKeepsStamps() throws Exception {
    writeJava("Live", 2_000L);
    Files.writeString(outputDir.resolve("Live.class"), "");
    writeStamps(Map.of("com/example/Live.java", 2_000L));

    final int removed = WorkspaceSession.deleteOrphanedClassOutputs(config);

    assertThat(removed).isZero();
    assertThat(outputDir.resolve("Live.class")).exists();
    assertThat(CompiledStamps.load(config.moduleDir(), config.sourceTree()))
        .containsOnlyKeys("com/example/Live.java");
  }

  @Test
  void deleteClassOutputs_nestedAndAnonymousRemoved_deletesOnlyUnkeptOutputsOfTheSource()
      throws Exception {
    Files.writeString(outputDir.resolve("Foo.class"), "");
    Files.writeString(outputDir.resolve("Foo$Inner.class"), "");
    Files.writeString(outputDir.resolve("Foo$1.class"), "");
    Files.writeString(outputDir.resolve("Bar.class"), "");

    final int deleted =
        WorkspaceSession.deleteClassOutputs(config, sourceFile, Set.of("com.example.Foo"));

    assertThat(deleted).isEqualTo(2);
    assertThat(outputDir.resolve("Foo$Inner.class")).doesNotExist();
    assertThat(outputDir.resolve("Foo$1.class")).doesNotExist();
    assertThat(outputDir.resolve("Foo.class")).exists();
    assertThat(outputDir.resolve("Bar.class")).exists();
  }

  @Test
  void deleteClassOutputs_packagePrivateSiblingRemoved_deletesOnlyItsOwnSibling() throws Exception {
    // Helper was declared in Foo.java and is gone from the latest compile; Other is a sibling of
    // Bar.java, which this save did not touch.
    compileIntoMirror(
        Map.of(
            "Foo.java", "class Foo {} class Helper {}", "Bar.java", "class Bar {} class Other {}"));

    final int deleted =
        WorkspaceSession.deleteClassOutputs(config, sourceFile, Set.of("com.example.Foo"));

    assertThat(deleted).isEqualTo(1);
    assertThat(outputDir.resolve("Helper.class")).doesNotExist();
    assertThat(outputDir.resolve("Foo.class")).exists();
    assertThat(outputDir.resolve("Bar.class")).exists();
    assertThat(outputDir.resolve("Other.class")).exists();
  }

  @Test
  void wroteClassOf_sourceDeclaringNoTypeNamedAfterIt_matchesOnlyItsOwnWrittenClasses()
      throws Exception {
    // Foo.java declares no Foo, so its outputs are tied to it only by the SourceFile attribute.
    compileIntoMirror(
        Map.of(
            "Foo.java", "class Alpha {} class Beta {}", "Bar.java", "class Bar {} class Other {}"));

    assertThat(WorkspaceSession.wroteClassOf(config, sourceFile, Set.of("com.example.Alpha")))
        .isTrue();
    // A failed compile writes nothing; another source's classes do not count either.
    assertThat(WorkspaceSession.wroteClassOf(config, sourceFile, Set.of())).isFalse();
    assertThat(WorkspaceSession.wroteClassOf(config, sourceFile, Set.of("com.example.Other")))
        .isFalse();
  }

  @Test
  void withoutFailedCompiles_unchangedSinceFailure_skipped_editedOrNeverFailedKept() {
    final var broken = sourceRoot.resolve("com/example/Broken.java");
    final var edited = sourceRoot.resolve("com/example/Edited.java");
    final var fresh = sourceRoot.resolve("com/example/Fresh.java");
    final Map<Path, Long> stale = Map.of(broken, 100L, edited, 200L, fresh, 300L);
    final Map<Path, Long> failed = Map.of(broken, 100L, edited, 150L);

    assertThat(WorkspaceSession.withoutFailedCompiles(stale, failed))
        .containsOnlyKeys(edited, fresh);
  }

  @Test
  void dependentRefreshMerge_unionsScopes_skipsOnlyFilesEveryRequestExcluded() {
    final var api = tmp.resolve(".lathe/api");
    final var app = tmp.resolve(".lathe/app");
    // Each save excludes the file it just compiled; a file stays excluded only if no merged
    // request needs it refreshed.
    final var first = new WorkspaceSession.DependentRefresh(Set.of(api), Set.of("A", "Shared"));
    final var second = new WorkspaceSession.DependentRefresh(Set.of(app), Set.of("Shared"));

    final var merged = first.merge(second);

    assertThat(merged.scope()).containsExactlyInAnyOrder(api, app);
    assertThat(merged.exclusions()).containsExactly("Shared");
  }

  @Test
  void stableSources_unchangedMtimeAcrossTicks_included_newOrMovedExcluded() {
    final var settled = sourceRoot.resolve("com/example/Settled.java");
    final var moving = sourceRoot.resolve("com/example/Moving.java");
    final var fresh = sourceRoot.resolve("com/example/Fresh.java");
    final Map<Path, Long> previous = Map.of(settled, 100L, moving, 100L);
    final Map<Path, Long> current = Map.of(settled, 100L, moving, 200L, fresh, 300L);

    // settled held its mtime across both ticks; moving changed since last tick; fresh is brand new.
    assertThat(WorkspaceSession.stableSources(current, previous)).containsExactly(settled);
  }

  @Test
  void reconcileDue_idleWithNothingSettling_waitsForIdleInterval_activityOrSettlingForcesIt() {
    final long idle = WorkspaceSession.ACTIVE_WINDOW_MS;
    final long interval = WorkspaceSession.IDLE_RECONCILE_INTERVAL_MS;

    assertThat(WorkspaceSession.reconcileDue(idle, 2_000L, false)).isFalse();
    assertThat(WorkspaceSession.reconcileDue(idle, interval - 1, false)).isFalse();
    assertThat(WorkspaceSession.reconcileDue(idle, interval, false)).isTrue();
    assertThat(WorkspaceSession.reconcileDue(idle - 1, 2_000L, false)).isTrue();
    assertThat(WorkspaceSession.reconcileDue(idle, 2_000L, true)).isTrue();
  }

  @Test
  void staleModules_returnsNewestMtimeAndTheStaleModule() throws Exception {
    writeJava("Edited", 5_000L); // stamped at 1000, edited after → stale
    writeJava("Added", 9_000L); // no stamp (a newly added file) → stale, and the newest
    writeJava("Fresh", 2_000L); // stamp matches its mtime → up to date, ignored
    writeStamps(Map.of("com/example/Edited.java", 1_000L, "com/example/Fresh.java", 2_000L));

    final var scan = WorkspaceSession.staleModules(List.of(config));

    assertThat(scan.newestMtime()).isEqualTo(9_000L);
    assertThat(scan.modules()).containsExactly(config);
  }

  @Test
  void staleModules_missingClassButStampPresent_notStale() throws Exception {
    // A mismatched source whose class never lands at the derived path — a matching stamp is fresh.
    writeJava("Mismatch", 3_000L);
    writeStamps(Map.of("com/example/Mismatch.java", 3_000L));

    final var scan = WorkspaceSession.staleModules(List.of(config));

    assertThat(scan.modules()).isEmpty();
  }

  @Test
  void staleModules_orphanStampForDeletedSource_ignored() throws Exception {
    writeJava("Live", 2_000L);
    writeStamps(Map.of("com/example/Live.java", 2_000L, "com/example/Ghost.java", 1_000L));

    final var scan = WorkspaceSession.staleModules(List.of(config));

    assertThat(scan.modules()).isEmpty();
  }

  @Test
  void staleModules_includesOpenFiles_excludesGeneratedRoots() throws Exception {
    // The reconcile batch recompiles the whole changed set together, so a saved rename's open files
    // must be included -- an interface rename saves its users, which resolve the new type only if
    // they compile in the same batch. So the newest-stale mtime here is the open file's.
    writeJava("Open", 9_000L); // stale and open -> still counted
    writeJava("Real", 5_000L);

    // A module whose sole source root IS its annotation-processor output: still wholly excluded.
    final var genRoot = tmp.resolve("gen-module/target/generated-sources/annotations");
    TestCompiler.writeAt(genRoot.resolve("com/example/Gen.java"), "", 8_000L);
    final var genConfig =
        TestCompiler.moduleConfig(
            tmp.resolve(".lathe/gen-module"),
            tmp.resolve("gen-module/target/classes"),
            genRoot,
            genRoot);

    final var scan = WorkspaceSession.staleModules(List.of(config, genConfig));

    assertThat(scan.newestMtime()).isEqualTo(9_000L);
    assertThat(scan.modules()).containsExactly(config);
  }

  @Test
  void staleMtimes_samePathStaleUnderTwoConfigs_dedupesNotThrows() {
    // Overlapping build-tool source roots can report the same file as stale under two configs; its
    // mtime is identical either way, so the scan dedupes by path rather than crashing on a dup key.
    final var shared = tmp.resolve("make/jdk/src/classes/build/tools/depend/Depend.java");
    final var scan =
        new WorkspaceSession.StaleScan(
            1_000L,
            Map.of(config, List.of(shared), config(tmp.resolve("other/src")), List.of(shared)));

    assertThat(WorkspaceSession.staleMtimes(scan)).containsOnlyKeys(shared);
  }

  @Test
  void isInPackageScope_generatedSourcesCandidate_reactorScope_inScope() {
    // FR-012/FR-013 + EG-052: a reactor-scoped search uses a null packageRel; the generated builder
    // lives under the .lathe generated-sources mirror (the fresh copy Lathe keeps in sync), never
    // under a regular source root, yet must stay in scope. The stale Maven target/ copy is not.
    final var config = configWithGen(tmp.resolve("module/target/generated-sources/annotations"));
    final var sourceRoot = tmp.resolve("module/src/main/java");
    final var genCandidate = config.generatedSourcesDir().resolve("com/example/FooBuilder.java");
    final var staleTargetCandidate =
        config.originalGenSourcesDir().resolve("com/example/FooBuilder.java");
    final List<Path> roots = config.searchRoots();

    assertThat(WorkspaceSession.isInPackageScope(genCandidate, roots, null)).isTrue();
    assertThat(WorkspaceSession.isInPackageScope(staleTargetCandidate, roots, null)).isFalse();
    assertThat(
            WorkspaceSession.isInPackageScope(
                sourceRoot.resolve("com/example/Foo.java"), roots, null))
        .isTrue();
  }

  @Test
  void isInPackageScope_pathOutsideEverySearchRoot_notInScope() {
    final var config = configWithGen(tmp.resolve("module/target/generated-sources/annotations"));
    final var outside = tmp.resolve("other-module/target/classes/com/example/Bar.java");
    final List<Path> roots = config.searchRoots();

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

  private void writeStamps(final Map<String, Long> stamps) throws IOException {
    CompiledStamps.writeAll(config.moduleDir(), config.sourceTree(), stamps);
  }

  @Test
  void renderNewType_class_skeletonPathAndBodyCaret() {
    final var result = render(TypeKind.CLASS, "Foo", "com.example");

    assertThat(result.path()).isEqualTo(sourceRoot.resolve("com/example/Foo.java").toString());
    assertThat(result.content()).isEqualTo("package com.example;\n\npublic class Foo {\n\n}\n");
    assertThat(result.caret().getLine()).isEqualTo(3); // the empty body line
    assertThat(result.caret().getCharacter()).isZero();
  }

  @Test
  void renderNewType_record_caretInComponentList() {
    final var result = render(TypeKind.RECORD, "Point", "com.example");

    assertThat(result.content()).isEqualTo("package com.example;\n\npublic record Point() {\n}\n");
    assertThat(result.caret().getLine()).isEqualTo(2);
    assertThat(result.caret().getCharacter()).isEqualTo("public record Point(".length());
  }

  @Test
  void renderNewType_interfaceEnumAndDefaultPackage_useKeywordAndOmitPackageLine() {
    assertThat(render(TypeKind.INTERFACE, "Bar", "com.example").content())
        .isEqualTo("package com.example;\n\npublic interface Bar {\n\n}\n");
    assertThat(render(TypeKind.ENUM, "Color", "com.example").content())
        .isEqualTo("package com.example;\n\npublic enum Color {\n\n}\n");

    final var defaultPkg = render(TypeKind.CLASS, "Foo", "");
    assertThat(defaultPkg.content()).isEqualTo("public class Foo {\n\n}\n");
    assertThat(defaultPkg.path()).isEqualTo(sourceRoot.resolve("Foo.java").toString());
    assertThat(defaultPkg.caret().getLine())
        .isEqualTo(1); // no package header → body line shifts up
  }

  @Test
  void renderNewType_test_junitSkeletonWithMethodBodyCaret() {
    final var result = render(TypeKind.TEST, "FooTest", "com.example");

    assertThat(result.path()).isEqualTo(sourceRoot.resolve("com/example/FooTest.java").toString());
    assertThat(result.content())
        .isEqualTo(
            "package com.example;\n\nimport org.junit.jupiter.api.Test;\n\nclass FooTest {\n\n"
                + "  @Test\n  void name() {\n\n  }\n}\n");
    assertThat(result.caret().getLine()).isEqualTo(8); // the empty @Test method body line
    assertThat(result.caret().getCharacter()).isZero();

    // No package header shifts every line — and the caret — up by the two header lines.
    assertThat(render(TypeKind.TEST, "FooTest", "").caret().getLine()).isEqualTo(6);
  }

  @Test
  void renderNewType_invalidNamesAndPlacement_rejected() {
    assertThatThrownBy(() -> render(TypeKind.CLASS, "9Foo", "com.example"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> render(TypeKind.CLASS, "class", "com.example"))
        .isInstanceOf(IllegalArgumentException.class);
    // module-info: a qualified module name — malformed or a keyword segment is rejected.
    assertThatThrownBy(() -> render(TypeKind.MODULE_INFO, "com.9bad", ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> render(TypeKind.MODULE_INFO, "com.class", ""))
        .isInstanceOf(IllegalArgumentException.class);
    // package-info: the default package cannot carry one.
    assertThatThrownBy(() -> render(TypeKind.PACKAGE_INFO, "ignored", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void renderNewType_packageInfo_javadocStubInPackageDir() {
    final var result = render(TypeKind.PACKAGE_INFO, "ignored", "com.example");

    assertThat(result.path())
        .isEqualTo(sourceRoot.resolve("com/example/package-info.java").toString());
    assertThat(result.content()).isEqualTo("/**\n * \n */\npackage com.example;\n");
    assertThat(result.caret().getLine()).isEqualTo(1); // the javadoc body line
    assertThat(result.caret().getCharacter()).isEqualTo(3);
  }

  @Test
  void renderNewType_moduleInfo_qualifiedNameAtSourceRootIgnoringPackage() {
    final var result = render(TypeKind.MODULE_INFO, "com.example.app", "any.pkg");

    // module-info.java lands at the source root, never a package dir, whatever the package.
    assertThat(result.path()).isEqualTo(sourceRoot.resolve("module-info.java").toString());
    assertThat(result.content()).isEqualTo("module com.example.app {\n\n}\n");
    assertThat(result.caret().getLine()).isEqualTo(1); // the empty body line
    assertThat(result.caret().getCharacter()).isZero();
  }

  @Test
  void enums_fromWireAndSourceTree_mapKnownTokensAndRejectUnknown() {
    assertThat(TypeKind.fromWire("record")).isEqualTo(TypeKind.RECORD);
    assertThat(TypeKind.fromWire("test")).isEqualTo(TypeKind.TEST);
    assertThat(TypeKind.fromWire("package-info")).isEqualTo(TypeKind.PACKAGE_INFO);
    assertThat(TypeKind.fromWire("module-info")).isEqualTo(TypeKind.MODULE_INFO);
    assertThat(SourceScope.fromWire("test")).isEqualTo(SourceScope.TEST);
    assertThat(SourceScope.ofSourceTree(LatheLayout.TEST_CLASSES_DIR)).isEqualTo(SourceScope.TEST);
    assertThat(SourceScope.ofSourceTree(LatheLayout.CLASSES_DIR)).isEqualTo(SourceScope.MAIN);
    assertThatThrownBy(() -> TypeKind.fromWire("annotation"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SourceScope.fromWire("prod"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SourceScope.ofSourceTree("bogus"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void modules_distinctSortedModuleRels() {
    final var testCfg = testConfig("module", "src/test/java");
    final var otherCfg =
        TestCompiler.moduleConfig(
            tmp.resolve(".lathe/other"),
            tmp.resolve("other/target/classes"),
            tmp.resolve("other/src/main/java"));

    assertThat(WorkspaceSession.modules(List.of(config, testCfg, otherCfg), tmp))
        .containsExactly("module", "other"); // "module" appears twice (main+test) → distinct
  }

  @Test
  void packages_onlyDirectoriesHoldingSourcesTaggedByScope() throws Exception {
    // real packages are the parent directories of `.java` files; intermediate dirs are not packages
    Files.createDirectories(sourceRoot.resolve("com/example/sub"));
    Files.writeString(
        sourceRoot.resolve("com/example/Foo.java"), "package com.example; class Foo {}");
    Files.writeString(
        sourceRoot.resolve("com/example/sub/Deep.java"), "package com.example.sub; class Deep {}");
    final var testCfg = testConfig("module", "src/test/java");
    Files.createDirectories(tmp.resolve("module/src/test/java/com/verify"));
    Files.writeString(
        tmp.resolve("module/src/test/java/com/verify/VerifyTest.java"),
        "package com.verify; class VerifyTest {}");

    final List<PackageEntry> pkgs =
        WorkspaceSession.packages(List.of(config, testCfg), tmp, "module");

    assertThat(pkgs)
        .contains(
            new PackageEntry("com.example", "main"),
            new PackageEntry("com.example.sub", "main"),
            new PackageEntry("com.verify", "test"));
    // intermediate directories and the (empty) default package are not offered
    assertThat(pkgs).extracting(PackageEntry::pkg).doesNotContain("com", "");

    // main-only module has no test-scope entries
    assertThat(WorkspaceSession.packages(List.of(config), tmp, "module"))
        .allMatch(entry -> entry.scope().equals("main"));
  }

  @Test
  void resolveContext_underMainTestAndOutsideRoot() {
    assertThat(WorkspaceSession.resolveContext(List.of(config), tmp, sourceFile))
        .isEqualTo(new ContextInfo("module", "main", "com.example"));

    final var testCfg = testConfig("module", "src/test/java");
    final var testFile = tmp.resolve("module/src/test/java/com/verify/FooTest.java");
    assertThat(WorkspaceSession.resolveContext(List.of(testCfg), tmp, testFile))
        .isEqualTo(new ContextInfo("module", "test", "com.verify"));

    assertThat(WorkspaceSession.resolveContext(List.of(config), tmp, tmp.resolve("loose/Bar.java")))
        .isNull();
  }

  @Test
  void dirRun_packageDirInsideTestTree_singlePackageSelector() {
    // A package dir resolves by pure path arithmetic (its package relative to the test root), so no
    // sources on disk are needed -- unlike the module fan-out below, which walks for packages.
    final var testCfg = testConfig("module", "src/test/java");
    final var pkgDir = tmp.resolve("module/src/test/java/com/verify");

    final DirRun run = WorkspaceSession.dirRun(List.of(config, testCfg), tmp, pkgDir);

    assertThat(run.moduleRel()).isEqualTo("module");
    assertThat(run.selections()).containsExactly(new DirRun.Selector("PACKAGE", "com.verify"));
  }

  @Test
  void dirRun_moduleRootAndTestSourceRoot_fanOutAllTestPackages() throws Exception {
    final var testCfg = testConfig("module", "src/test/java");
    Files.createDirectories(tmp.resolve("module/src/test/java/com/a"));
    Files.writeString(
        tmp.resolve("module/src/test/java/com/a/ATest.java"), "package com.a; class ATest {}");
    Files.createDirectories(tmp.resolve("module/src/test/java/com/b"));
    Files.writeString(
        tmp.resolve("module/src/test/java/com/b/BTest.java"), "package com.b; class BTest {}");

    final DirRun fromRoot =
        WorkspaceSession.dirRun(List.of(config, testCfg), tmp, tmp.resolve("module"));
    assertThat(fromRoot.moduleRel()).isEqualTo("module");
    assertThat(fromRoot.selections())
        .containsExactlyInAnyOrder(
            new DirRun.Selector("PACKAGE", "com.a"), new DirRun.Selector("PACKAGE", "com.b"));

    // The test source root itself carries the default package, so it runs the whole module too.
    final DirRun fromTestRoot =
        WorkspaceSession.dirRun(List.of(config, testCfg), tmp, tmp.resolve("module/src/test/java"));
    assertThat(fromTestRoot.selections()).isEqualTo(fromRoot.selections());
  }

  @Test
  void dirRun_reactorRootOrUnmatchedDir_empty() {
    // Enclosure is decided from the configured source roots, not from files on disk.
    final var testCfg = testConfig("module", "src/test/java");
    final var otherTestCfg = testConfig("other", "src/test/java");

    // The reactor root encloses two modules -- not a single run target.
    final DirRun reactor = WorkspaceSession.dirRun(List.of(testCfg, otherTestCfg), tmp, tmp);
    assertThat(reactor.moduleRel()).isEmpty();
    assertThat(reactor.selections()).isEmpty();

    // A directory under no source root resolves to nothing either.
    assertThat(WorkspaceSession.dirRun(List.of(testCfg), tmp, tmp.resolve("loose")).selections())
        .isEmpty();
  }

  @Test
  void testSources_resolvesClassesToFilesAndStripsInnerAndLeavesUnknownEmpty() throws Exception {
    final var testCfg = testConfig("module", "src/test/java");
    Files.createDirectories(tmp.resolve("module/src/test/java/com/verify"));
    Files.writeString(
        tmp.resolve("module/src/test/java/com/verify/FooTest.java"),
        "package com.verify; class FooTest {}");

    final List<TestSource> sources =
        WorkspaceSession.testSources(
            List.of(config, testCfg),
            tmp,
            "module",
            List.of("com.verify.FooTest", "com.verify.FooTest$Nested", "com.verify.GoneTest"));

    final String foo = tmp.resolve("module/src/test/java/com/verify/FooTest.java").toString();
    assertThat(sources)
        .containsExactly(
            new TestSource("com.verify.FooTest", foo),
            // inner class resolves to its top-level source file
            new TestSource("com.verify.FooTest$Nested", foo),
            // a class with no source file on disk resolves to an empty path
            new TestSource("com.verify.GoneTest", ""));
  }

  @Test
  void resources_reactorDirAndDependencyJar_tagsOriginsAndSkipsClasses() throws Exception {
    final var resourceRoot = tmp.resolve("app/src/main/resources");
    Files.createDirectories(resourceRoot.resolve("com/x"));
    Files.writeString(resourceRoot.resolve("com/x/schema.graphqls"), "type Query");
    final var jar =
        writeJar(
            tmp.resolve("lib.jar"),
            Map.of("com/y/config.xml", "<c/>", "com/y/App.class", "bytecode"));

    final List<ResourceEntry> entries =
        WorkspaceSession.resources(Map.of(resourceRoot, "app"), Map.of(jar, "g:a:1"));

    assertThat(entries)
        .containsExactlyInAnyOrder(
            // reactor file: editable path, origin from the captured module; App.class excluded
            ResourceEntry.reactor(
                "com/x/schema.graphqls",
                "app",
                resourceRoot.resolve("com/x/schema.graphqls").toString()),
            ResourceEntry.dependency("g:a:1", jar.toString(), "com/y/config.xml"));
  }

  private static Path writeJar(final Path path, final Map<String, String> entries)
      throws IOException {
    try (final var out = new ZipOutputStream(Files.newOutputStream(path))) {
      for (final Map.Entry<String, String> entry : entries.entrySet()) {
        out.putNextEntry(new ZipEntry(entry.getKey()));
        out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }

    return path;
  }

  private CreateTypeResult render(final TypeKind type, final String name, final String pkg) {
    return WorkspaceSession.renderNewType(sourceRoot, pkg, type, name);
  }

  private ModuleSourceConfig config(final Path sourceRoot) {
    return TestCompiler.moduleConfig(
        tmp.resolve(".lathe/module"), tmp.resolve("module/target/classes"), sourceRoot);
  }

  private ModuleSourceConfig testConfig(final String module, final String relRoot) {
    return TestCompiler.moduleConfig(
        tmp.resolve(".lathe/%s".formatted(module)),
        tmp.resolve("%s/target/test-classes".formatted(module)),
        tmp.resolve("%s/%s".formatted(module, relRoot)),
        null,
        LatheLayout.TEST_CLASSES_DIR);
  }

  private ModuleSourceConfig configWithGen(final Path genRoot) {
    return TestCompiler.moduleConfig(
        tmp.resolve(".lathe/module"),
        tmp.resolve("module/target/classes"),
        tmp.resolve("module/src/main/java"),
        genRoot);
  }

  // Real class files, so each carries the SourceFile attribute naming the source it came from.
  private void compileIntoMirror(final Map<String, String> bodiesByFileName) throws IOException {
    final var sourceDir = Files.createDirectories(tmp.resolve("compile-src/com/example"));
    bodiesByFileName.forEach(
        (fileName, body) ->
            IOUtil.unchecked(
                () ->
                    Files.writeString(
                        sourceDir.resolve(fileName), "package com.example; %s".formatted(body))));
    final String[] args =
        Stream.concat(
                Stream.of("-d", config.latheClassesDir().toString()),
                bodiesByFileName.keySet().stream().map(sourceDir::resolve).map(Path::toString))
            .toArray(String[]::new);

    assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, args)).isZero();
  }
}
