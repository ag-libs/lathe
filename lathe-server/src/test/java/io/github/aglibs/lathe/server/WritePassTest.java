package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheLock;
import io.github.aglibs.lathe.core.schema.OutputData;
import io.github.aglibs.lathe.core.schema.ResourceRootData;
import io.github.aglibs.lathe.core.schema.SourceStamp;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

// The write pass: external changes, startup catch-up, reported files, failures, deletions,
// resources. Counts compiles through a JVM-wide log, so no other test class may compile meanwhile.
@Isolated
class WritePassTest extends ServiceTestBase {

  private static final Path FOO_KEY = Path.of("com/example/Foo.java");
  private static final String FOO = "package com.example; class Foo {}";
  private static final String CHANGED_FOO = "package com.example; class Foo { int changed; }";

  @Test
  void initialize_sourceUpToDateWithItsClass_doesNotPrompt() throws Exception {
    writeStaleModule(5_000L, 5_000L); // the stamp is the mtime compiled, so equal → fresh
    service.setWorkDoneProgressSupported(true);

    service.initialize(tmp);

    verify(client, after(500).never()).showMessageRequest(any());
    // Nothing is stale, so no in-process recompile progress is reported (only workspace indexing).
    verify(client, never()).notifyProgress(argThat(WritePassTest::isRecompileBegin));
  }

  @Test
  void initialize_moreExternalChangesThanTheOldBulkThreshold_compiledInProcessWithoutNotice()
      throws Exception {
    final Path sourceRoot = tmp.resolve("module/src/main/java");
    IntStream.range(0, 61)
        .forEach(
            i ->
                IOUtil.unchecked(
                    () ->
                        TestCompiler.writeAt(
                            sourceRoot.resolve("com/example/T%d.java".formatted(i)),
                            "package com.example; class T%d {}".formatted(i),
                            5_000L)));
    TestCompiler.writeModuleParams(tmp, "module", sourceRoot, null);

    service.initialize(tmp);
    service.reconcileNow(true).get(30, TimeUnit.SECONDS); // runs after the startup pass

    assertThat(moduleStamps())
        .as("every changed source compiled and stamped")
        .hasSize(61)
        .allSatisfy((source, mtime) -> assertThat(mtime).isEqualTo(5_000L));
    verify(client, never())
        .showMessage(argThat((MessageParams p) -> p.getType() == MessageType.Warning));
    verify(client, never()).showMessageRequest(any());
  }

  @Test
  void reconcileNow_brokenFileInABatch_restOfTheTreeIsStillWritten() throws Exception {
    final var sources = new LinkedHashMap<String, String>();
    sources.put(
        "com/example/Good.java", "package com.example; class Good { int g() { return 1; } }");
    sources.put("com/example/Broken.java", "package com.example; class Broken {}");
    final List<Path> files =
        TestCompiler.writeMirroredModule(tmp, "module", sources, List.of(), 1_000L);
    service.initialize(tmp);
    awaitStartup();

    // Both change on disk; Broken no longer compiles. javac writes nothing for a task with an
    // error, so the batch is written again without Broken.
    TestCompiler.writeAt(
        files.get(0), "package com.example; class Good { int g() { return 2; } }", 5_000L);
    TestCompiler.writeAt(
        files.get(1),
        "package com.example; class Broken { int b() { return missing(); } }",
        5_000L);
    service.reconcileNow(true).get(10, TimeUnit.SECONDS);

    assertThat(moduleStamps())
        .as("stamps: the good file written, the broken one left stale")
        .containsEntry(Path.of("com/example/Good.java"), 5_000L)
        .containsEntry(Path.of("com/example/Broken.java"), 1_000L);
    assertThat(service.notesFuture(List.of()).get(5, TimeUnit.SECONDS).notCompiled())
        .as("the broken file stays recorded though the rest of its tree was written after it")
        .singleElement()
        .asString()
        .startsWith("%s:1: ".formatted(Path.of("module/src/main/java/com/example/Broken.java")));
  }

  @Test
  void reconcileNow_mutuallyDependentChangesLandingApart_failedOneRidesAlongAndBothCompile()
      throws Exception {
    final var sources = new LinkedHashMap<String, String>();
    sources.put("com/example/A.java", "package com.example; class A { int a() { return 1; } }");
    sources.put("com/example/B.java", "package com.example; class B { int b() { return 2; } }");
    final List<Path> files =
        TestCompiler.writeMirroredModule(tmp, "module", sources, List.of(), 1_000L);
    service.initialize(tmp);
    awaitStartup();

    // A changes first and needs B's new method, so it fails alone.
    TestCompiler.writeAt(
        files.get(0),
        "package com.example; class A { int a() { return new B().newB(); } int newA() { return 3; } }",
        5_000L);
    service.reconcileNow(true).get(10, TimeUnit.SECONDS);
    // B changes next and needs A's new method: alone it would fail too, leaving both stuck.
    TestCompiler.writeAt(
        files.get(1),
        "package com.example; class B { int b() { return 2; } int newB() { return new A().newA(); } }",
        6_000L);
    service.reconcileNow(true).get(10, TimeUnit.SECONDS);

    assertThat(moduleStamps())
        .as("both written together once B's write takes the failed A along")
        .containsEntry(Path.of("com/example/A.java"), 5_000L)
        .containsEntry(Path.of("com/example/B.java"), 6_000L);
  }

  @Test
  void reconcileNow_retryWritesNothing_earlierFailuresStayRecorded_nextPassWritesNothing()
      throws Exception {
    final Path core =
        TestCompiler.writeMirroredModule(
                tmp,
                "core",
                Map.of(
                    "com/example/A.java", "package com.example; public class A {}",
                    "com/example/B.java", "package com.example; public class B {}"),
                List.of(),
                1_000L)
            .get(0)
            .getParent();
    final Path user =
        TestCompiler.writeMirroredModule(
                tmp,
                "app",
                Map.of("com/example/app/C.java", "package com.example.app; class C {}"),
                List.of("core"),
                1_000L)
            .get(0);
    service.initialize(tmp);
    awaitStartup();
    // C fails in an earlier pass and is recorded.
    TestCompiler.writeAt(user, "package com.example.app; class C { Missing m; }", 5_000L);
    service.reconcileNow(true).get(10, TimeUnit.SECONDS);

    // A breaks; B needs the X only A's new version declares, so the batch fails on A and its retry
    // without A fails on B: javac writes nothing.
    TestCompiler.writeAt(
        core.resolve("A.java"),
        "package com.example; public class A { Missing m; public static class X {} }",
        6_000L);
    TestCompiler.writeAt(
        core.resolve("B.java"), "package com.example; public class B { A.X x; }", 6_000L);
    service.reconcileNow(true).get(10, TimeUnit.SECONDS);

    try (final var compiles = new CompileLog()) {
      service.reconcileNow(true).get(10, TimeUnit.SECONDS);
      assertThat(writes(compiles))
          .as("nothing changed and nothing was written, so no failure is retried")
          .isEmpty();
    }
  }

  @Test
  void writeReported_generatedSource_neverWritten() throws Exception {
    final Path sourceRoot = tmp.resolve("module/src/main/java");
    TestCompiler.writeMirroredModule(
        tmp,
        "module",
        Map.of("com/example/User.java", "package com.example; class User {}"),
        List.of(),
        1_000L);
    TestCompiler.writeModuleParams(
        tmp, "module", sourceRoot, tmp.resolve("module/target/generated-sources/annotations"));
    final Path generated =
        TestCompiler.writeAt(
            tmp.resolve(".lathe/module")
                .resolve(LatheLayout.generatedSourcesDir("classes"))
                .resolve("com/example/UserBuilder.java"),
            "package com.example; class UserBuilder {}",
            5_000L);
    service.initialize(tmp);
    awaitStartup();

    try (final var compiles = new CompileLog()) {
      service.writeReported(Set.of(generated)).get(10, TimeUnit.SECONDS);
      assertThat(writes(compiles))
          .as("a processor's output is never an input of a write")
          .isEmpty();
    }
  }

  @Test
  void writeReported_whileABuildHoldsTheLock_writesReportedFilesEvenAtTheirStamp_externalWaits()
      throws Exception {
    final var sources = new LinkedHashMap<String, String>();
    sources.put("com/example/A.java", "package com.example; class A {}");
    sources.put("com/example/B.java", "package com.example; class B {}");
    sources.put("com/example/C.java", "package com.example; class C {}");
    final List<Path> files =
        TestCompiler.writeMirroredModule(tmp, "module", sources, List.of(), 1_000L);
    service.initialize(tmp);
    awaitStartup();
    final Path lock = LatheLayout.reactorLockFile(tmp.resolve(LatheLayout.LATHE_DIR));
    LatheLock.acquire(lock);
    clearInvocations(client);

    // A and B are reported -- B with its mtime still equal to its stamp -- and C changed
    // externally.
    TestCompiler.writeAt(files.get(0), "package com.example; class A { int addedA; }", 5_000L);
    TestCompiler.writeAt(files.get(1), "package com.example; class B { int addedB; }", 1_000L);
    TestCompiler.writeAt(files.get(2), "package com.example; class C { int addedC; }", 5_000L);
    service.writeReported(Set.of(files.get(0), files.get(1))).get(10, TimeUnit.SECONDS);

    final Path classes = tmp.resolve(".lathe/module/classes/com/example");
    assertThat(Files.readString(classes.resolve("B.class"), StandardCharsets.ISO_8859_1))
        .as("B written though its mtime equals its stamp")
        .contains("addedB");
    assertThat(moduleStamps())
        .as("stamps during the build: the reported A written, the external C left for later")
        .containsEntry(Path.of("com/example/A.java"), 5_000L)
        .containsEntry(Path.of("com/example/C.java"), 1_000L);
    verify(client, never()).notifyProgress(argThat(WritePassTest::isRecompileBegin));

    LatheLock.release(lock);
    service.reconcileNow(true).get(10, TimeUnit.SECONDS);
    assertThat(moduleStamps())
        .as("after the build, the external change catches up")
        .containsEntry(Path.of("com/example/C.java"), 5_000L);
  }

  @Test
  void reconcileNow_eager_recompilesStaleSourceInOnePass() throws Exception {
    writeStaleModule(1_000L, 1_000L); // fresh at init (mtime == stamp) → startup eager is a no-op
    service.initialize(tmp);
    awaitStartup(); // let startup reconciliation finish before mutating the source
    writeFoo(CHANGED_FOO, 5_000L); // changed since its stamp → stale

    // A single eager pass compiles it (no two-tick wait), advancing the stamp to the source mtime.
    service.reconcileNow(true).get(5, TimeUnit.SECONDS);

    assertThat(moduleStamps()).containsEntry(Path.of("com/example/Foo.java"), 5_000L);
  }

  @Test
  void reconcileNow_sealedRootAndNewPermittedSubtypeChangedExternally_recompilesBothInOneBatch()
      throws Exception {
    final Path shape = mirroredShapes();
    final Path square = shape.resolveSibling("Square.java");
    final Path classesDir = tmp.resolve(".lathe/module/classes");

    service.initialize(tmp);
    awaitStartup();

    // The edit lands on disk: Shape permits a new Square, and Square is created -- both stale. A
    // per-file recompile would deadlock (each needs the other); the batch resolves them together.
    TestCompiler.writeAt(
        shape, "package shapes; sealed interface Shape permits Circle, Square {}", 5_000L);
    TestCompiler.writeAt(square, "package shapes; final class Square implements Shape {}", 5_000L);

    service.reconcileNow(true).get(5, TimeUnit.SECONDS);

    assertThat(classesDir.resolve("shapes/Square.class")).exists();
    assertThat(moduleStamps())
        .containsEntry(Path.of("shapes/Shape.java"), 5_000L)
        .containsEntry(Path.of("shapes/Square.java"), 5_000L);
  }

  @Test
  void reconcileNow_upstreamAndDependentChangedTogether_compilesDependentAfterUpstreamInOnePass()
      throws Exception {
    // Mirrored baseline: app calls Api.a().
    final Path api =
        TestCompiler.writeMirroredModule(
                tmp,
                "api",
                Map.of(
                    "com/example/api/Api.java",
                    "package com.example.api; public class Api { public int a() { return 1; } }"),
                List.of(),
                1_000L)
            .get(0);
    final Path app =
        TestCompiler.writeMirroredModule(
                tmp,
                "app",
                Map.of(
                    "com/example/app/App.java",
                    "package com.example.app;"
                        + " class App { int u() { return new com.example.api.Api().a(); } }"),
                List.of("api"),
                1_000L)
            .get(0);

    service.initialize(tmp);
    awaitStartup();

    // A rename lands on disk: a() becomes b() and its caller follows. App compiles only against
    // the new Api class, so its batch must run after api's has written the mirror.
    TestCompiler.writeAt(
        api, "package com.example.api; public class Api { public int b() { return 1; } }", 5_000L);
    TestCompiler.writeAt(
        app,
        "package com.example.app; class App { int u() { return new com.example.api.Api().b(); } }",
        5_000L);

    service.reconcileNow(true).get(10, TimeUnit.SECONDS);

    assertThat(TestCompiler.stampedMtimes(tmp.resolve(".lathe/api"), "classes"))
        .containsEntry(Path.of("com/example/api/Api.java"), 5_000L);
    assertThat(TestCompiler.stampedMtimes(tmp.resolve(".lathe/app"), "classes"))
        .containsEntry(Path.of("com/example/app/App.java"), 5_000L);
  }

  @Test
  void reconcileNow_deletedDependency_republishesOpenDependentWithError() throws Exception {
    final DepFixture fx = depWithUsers(1);
    service.initialize(tmp);
    final Path user = fx.users().get(0);
    openDoc(user, Files.readString(user));
    verify(client, timeout(5_000))
        .publishDiagnostics(argThat(p -> isFor(p, user) && p.getDiagnostics().isEmpty()));

    Files.delete(fx.dep());
    service.reconcileNow().get(5, TimeUnit.SECONDS);

    verify(client, timeout(5_000))
        .publishDiagnostics(
            argThat(
                p ->
                    isFor(p, user)
                        && p.getDiagnostics().stream()
                            .anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error)));
  }

  @Test
  void reconcileNow_closedSourceChangedExternally_recompilesInProcessWithoutPrompting()
      throws Exception {
    writeStaleModule(1_000L, 1_000L); // fresh at init → startup eager is a no-op
    service.setWorkDoneProgressSupported(true);
    service.initialize(tmp);
    awaitStartup();
    writeFoo(CHANGED_FOO, 5_000L); // now stale

    // First pass records the change as pending (two-tick stability); the second recompiles it.
    service.reconcileNow().get(5, TimeUnit.SECONDS);
    service.reconcileNow().get(5, TimeUnit.SECONDS);

    // Recompiled in-process (its stamp advanced to the source's mtime) and not prompted for Maven.
    assertThat(moduleStamps()).containsEntry(Path.of("com/example/Foo.java"), 5_000L);
    verify(client, never()).showMessageRequest(any());
    // The in-process recompile reports a self-clearing progress task (begin "recompiling" + end).
    verify(client, timeout(5_000).atLeastOnce())
        .notifyProgress(argThat(WritePassTest::isRecompileBegin));
  }

  // Another Lathe server on the workspace (an editor's beside an agent's) writes under .lathe/'s
  // write lock: a pass waits for it instead of compiling the same files beside it, then writes.
  @Test
  void reconcileNow_anotherServerHoldsTheWriteLock_waitsForItThenWrites() throws Exception {
    writeStaleModule(1_000L, 1_000L);
    service.initialize(tmp);
    awaitStartup();
    writeFoo(CHANGED_FOO, 5_000L);
    final Closeable otherServer =
        LatheLock.tryExclusive(LatheLayout.writeLockFile(tmp.resolve(LatheLayout.LATHE_DIR)))
            .orElseThrow();
    final CompletableFuture<Void> pass;
    try {
      pass = service.reconcileNow(true);

      assertThatThrownBy(() -> pass.get(300, TimeUnit.MILLISECONDS))
          .as("waits while the other server writes")
          .isInstanceOf(TimeoutException.class);
      assertThat(moduleStamps()).containsEntry(FOO_KEY, 1_000L);
    } finally {
      otherServer.close();
    }

    pass.get(10, TimeUnit.SECONDS);
    assertThat(moduleStamps()).containsEntry(FOO_KEY, 5_000L);
  }

  @Test
  void reconcileNow_callersChangedExternally_referencesFollowDisk() throws Exception {
    final DepFixture fx = depWithUsers(1);
    service.initialize(tmp);
    awaitStartup();
    final String dep = Files.readString(fx.dep());
    openDoc(fx.dep(), dep);
    final Path user = fx.users().get(0);
    assertThat(referencingFiles(fx.dep(), dep)).contains(user);

    // Written outside the editor: neither file is ever opened. The new caller sits in another
    // package, so only the candidate index can lead the search to it.
    Files.writeString(user, "package com.example;\nclass User0 { int u() { return 2; } }\n");
    final Path added =
        Files.createDirectories(fx.dep().getParent().resolveSibling("other")).resolve("Added.java");
    Files.writeString(
        added,
        "package com.other;\nclass Added { int a() { return new com.example.Dep().v(); } }\n");
    service.reconcileNow().get(5, TimeUnit.SECONDS);

    assertThat(referencingFiles(fx.dep(), dep)).contains(added).doesNotContain(user);
  }

  private List<Path> referencingFiles(final Path file, final String content) throws Exception {
    return service
        .references(referenceParams(file, content, content.indexOf("v()"), null))
        .get(10, TimeUnit.SECONDS)
        .stream()
        .map(location -> LatheUri.toPath(location.getUri()))
        .toList();
  }

  // Only the content tells a touch from a change; the reference is what the mirror holds, so a
  // change put back after the server compiled it is a change again.
  @Test
  void reconcileNow_touchedSource_restampsWithoutCompiling_changedOrPutBackOneCompiles()
      throws Exception {
    writeStaleModule(1_000L, 1_000L);
    service.initialize(tmp);
    awaitStartup();

    writeFoo(FOO, 5_000L);
    assertPassCompiles(false, 5_000L);
    writeFoo(CHANGED_FOO, 6_000L);
    assertPassCompiles(true, 6_000L);
    // Back to what the build compiled, but the mirror holds the change.
    writeFoo(FOO, 9_000L);
    assertPassCompiles(true, 9_000L);
  }

  // A stamp without content (OpenJDK's) leaves the mtime alone to decide.
  @Test
  void reconcileNow_touchedSourceStampedWithoutContent_compiles() throws Exception {
    writeStaleModule(1_000L, 1_000L);
    CompiledStamps.writeAll(
        tmp.resolve(".lathe/module"),
        "classes",
        Map.of(FOO_KEY, new SourceStamp(1_000L, null)),
        Map.of());
    service.initialize(tmp);
    awaitStartup();

    writeFoo(FOO, 5_000L);
    assertPassCompiles(true, 5_000L);
  }

  private void assertPassCompiles(final boolean compiles, final long stampedMtime)
      throws Exception {
    try (final var compileLog = new CompileLog()) {
      service.reconcileNow(true).get(5, TimeUnit.SECONDS);
      assertThat(writes(compileLog)).as("compiled at %d", stampedMtime).hasSize(compiles ? 1 : 0);
    }

    assertThat(moduleStamps()).containsEntry(FOO_KEY, stampedMtime);
  }

  @Test
  void reconcileNow_compileFails_doesNotAdvanceStamp() throws Exception {
    writeStaleModule(1_000L, 1_000L);
    service.initialize(tmp);
    awaitStartup();
    // A reference to a missing type: the compile emits no class for Foo.
    TestCompiler.writeAt(
        tmp.resolve("module/src/main/java/com/example/Foo.java"),
        "package com.example; class Foo { Missing field; }",
        5_000L);

    service.reconcileNow().get(5, TimeUnit.SECONDS);
    service.reconcileNow().get(5, TimeUnit.SECONDS);

    // A failed compile must not stamp the source fresh: it stays stale so a later batch retries it
    // once its dependency lands, instead of leaving a stale mirror class marked up to date.
    assertThat(moduleStamps()).containsEntry(Path.of("com/example/Foo.java"), 1_000L);
  }

  @Test
  void initialize_copiesOnlyStaleResourcesIntoLathe() throws Exception {
    final Path resDir = tmp.resolve("app/src/main/resources");
    TestCompiler.writeAt(resDir.resolve("stale.conf"), "new", 9_000L); // newer than dest → copied
    TestCompiler.writeAt(
        resDir.resolve("fresh.conf"), "src", 1_000L); // older than dest → untouched
    final Path staleDest = writeResourceDest("stale.conf", "old", 1_000L);
    final Path freshDest = writeResourceDest("fresh.conf", "keep", 5_000L);
    writeResourceWorkspace("app/src/main/resources", "app/target/classes");

    service.initialize(tmp);
    awaitWorkerIdle();

    assertThat(Files.readString(staleDest)).isEqualTo("new"); // stale → copied
    assertThat(Files.readString(freshDest)).isEqualTo("keep"); // up to date → untouched
  }

  @Test
  void reconcile_resourceDeletedOnDisk_removesItsCopy_keepsOtherFilesInTheMirror()
      throws Exception {
    final Path resDir = tmp.resolve("app/src/main/resources");
    final Path gone = TestCompiler.writeAt(resDir.resolve("gone.conf"), "gone", 9_000L);
    TestCompiler.writeAt(resDir.resolve("kept.conf"), "kept", 9_000L);
    final Path generated =
        writeResourceDest("META-INF/services/com.example.Spi", "com.example.Impl", 1_000L);
    writeResourceWorkspace("app/src/main/resources", "app/target/classes");
    service.initialize(tmp);
    awaitWorkerIdle();
    final Path goneCopy = tmp.resolve(".lathe/app/classes/gone.conf");
    assertThat(goneCopy).exists();

    Files.delete(gone);
    service.reconcileNow().get(5, TimeUnit.SECONDS);

    assertThat(goneCopy).doesNotExist();
    assertThat(tmp.resolve(".lathe/app/classes/kept.conf")).hasContent("kept");
    assertThat(generated).as("not a resource the server copied").exists();
  }

  private Path writeResourceDest(final String name, final String content, final long mtime)
      throws Exception {
    return TestCompiler.writeAt(tmp.resolve(".lathe/app/classes").resolve(name), content, mtime);
  }

  private void writeResourceWorkspace(final String resourceDir, final String outputDir)
      throws Exception {
    final Path latheDir = tmp.resolve(LatheLayout.LATHE_DIR);
    Files.createDirectories(latheDir);
    Json.write(
        new WorkspaceManifestData(
            LatheLayout.SCHEMA_VERSION,
            tmp.toString(),
            null,
            List.of(),
            null,
            List.of(),
            List.of(),
            List.of(new ResourceRootData(resourceDir, outputDir, "", false, "app")),
            List.of(new OutputData(outputDir, "app", LatheLayout.CLASSES_DIR)),
            null),
        latheDir.resolve(LatheLayout.WORKSPACE_JSON));
  }

  // A configured single-module workspace whose one source and its .class are stamped at the given
  // mtimes, so the startup source-staleness scan sees the source as stale (source > class) or
  // fresh.
  private void writeStaleModule(final long sourceMtime, final long stampMtime) throws Exception {
    final Path foo = writeFoo(FOO, sourceMtime);
    final var compiled = FileUtil.fingerprint(foo);
    CompiledStamps.writeAll(
        tmp.resolve(".lathe/module"),
        "classes",
        Map.of(FOO_KEY, new SourceStamp(stampMtime, compiled)),
        Map.of(FOO_KEY, compiled));
    TestCompiler.writeModuleParams(tmp, "module", tmp.resolve("module/src/main/java"), null);
  }

  private Map<Path, Long> moduleStamps() {
    return TestCompiler.stampedMtimes(tmp.resolve(".lathe/module"), "classes");
  }

  private Path writeFoo(final String content, final long mtime) throws Exception {
    return TestCompiler.writeAt(
        tmp.resolve("module/src/main/java").resolve(FOO_KEY), content, mtime);
  }

  // A $/progress "begin" for the in-process recompile (its title carries "recompiling"), as opposed
  // to the workspace-indexing progress that also fires on initialize.
  private static boolean isRecompileBegin(final ProgressParams params) {
    return params.getValue().getLeft() instanceof WorkDoneProgressBegin begin
        && begin.getTitle().contains("recompiling");
  }
}
