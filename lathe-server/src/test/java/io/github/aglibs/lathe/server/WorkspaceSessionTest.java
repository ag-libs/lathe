package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.core.LatheLayout;
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
  void staleModules_returnsNewestMtimeAndTheStaleModule() throws Exception {
    writeClass("Edited", 1_000L); // compiled, then edited after → stale
    writeJava("Edited", 5_000L);
    writeJava("Added", 9_000L); // never compiled (a newly added file) → stale, and the newest
    writeClass("Fresh", 8_000L); // compiled after its last edit → up to date, ignored
    writeJava("Fresh", 2_000L);

    final var scan = WorkspaceSession.staleModules(List.of(config), Set.of());

    assertThat(scan.newestMtime()).isEqualTo(9_000L);
    assertThat(scan.modules()).containsExactly(config);
  }

  @Test
  void syncPromptMessage_partialNamesOrCounts_fullOrStructuralGeneric() {
    // structural / POM (no modules) → generic
    assertThat(WorkspaceSession.syncPromptMessage(List.of(), List.of()))
        .isEqualTo("Maven project changed. Lathe will run a full refresh.");
    // partial, few → names in brackets (scope == changed)
    assertThat(WorkspaceSession.syncPromptMessage(List.of("app", "core"), List.of("app", "core")))
        .isEqualTo("Sources changed in [app, core]. Lathe will run a partial refresh.");
    // partial, many → count
    final List<String> many = List.of("a", "b", "c", "d");
    assertThat(WorkspaceSession.syncPromptMessage(many, many))
        .isEqualTo("Sources changed in 4 modules. Lathe will run a partial refresh.");
    // full fallback (empty scope but modules changed) → count
    assertThat(WorkspaceSession.syncPromptMessage(many, List.of()))
        .isEqualTo("Sources changed in 4 modules. Lathe will run a full refresh.");
  }

  @Test
  void syncScope_targetedBelowThreshold_fullAtOrAbove() {
    assertThat(WorkspaceSession.syncScope(List.of("a"), 10)).containsExactly("a"); // 10% → targeted
    assertThat(WorkspaceSession.syncScope(List.of("a", "b"), 4))
        .containsExactly("a", "b"); // 50% → targeted
    assertThat(WorkspaceSession.syncScope(List.of("a", "b", "c"), 4)).isEmpty(); // 75% → full
  }

  @Test
  void staleModules_ignoresOpenFilesGeneratedRootsAndPackageInfo() throws Exception {
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

    final var scan = WorkspaceSession.staleModules(List.of(config, genConfig), Set.of(open));

    assertThat(scan.newestMtime()).isEqualTo(5_000L);
    assertThat(scan.modules()).containsExactly(config);
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
  void packages_walkDiskTaggedByScopeReflectingNewDirs() throws Exception {
    Files.createDirectories(sourceRoot.resolve("com/example/sub")); // main
    final var testCfg = testConfig("module", "src/test/java");
    Files.createDirectories(tmp.resolve("module/src/test/java/com/verify"));

    final List<PackageEntry> pkgs =
        WorkspaceSession.packages(List.of(config, testCfg), tmp, "module");

    assertThat(pkgs)
        .contains(
            new PackageEntry("", "main"),
            new PackageEntry("com", "main"),
            new PackageEntry("com.example", "main"),
            new PackageEntry("com.example.sub", "main"),
            new PackageEntry("com.verify", "test"));

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
}
