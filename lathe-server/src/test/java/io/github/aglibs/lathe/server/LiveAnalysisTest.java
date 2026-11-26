package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

// Analysis of an open file with the unsaved buffers of its source tree.
class LiveAnalysisTest extends ServiceTestBase {

  @Test
  void didOpen_subtypeOfAnUnsavedSealedRoot_neverReportsTheStaleSealedClause() throws Exception {
    final Path shape = mirroredShapes();
    final Path square = shape.resolveSibling("Square.java");
    host.initialize(tmp);

    // Shape's buffer permits Square, unsaved (opened restored with changes, as after an editor
    // restart); Square is analyzed with it, not against the written Shape that does not permit it.
    service.didOpen(
        new DidOpenTextDocumentParams(
            new TextDocumentItem(
                shape.toUri().toString(),
                "java",
                1,
                "package shapes; sealed interface Shape permits Circle, Square {}")));
    service.didOpen(
        new DidOpenTextDocumentParams(
            new TextDocumentItem(
                square.toUri().toString(),
                "java",
                1,
                "package shapes; final class Square implements Shape {}")));

    verify(client, timeout(TIMEOUT_MS)).publishDiagnostics(argThat(p -> isFor(p, square)));
    awaitWorkerIdle();
    final var captor = ArgumentCaptor.forClass(PublishDiagnosticsParams.class);
    verify(client, atLeastOnce()).publishDiagnostics(captor.capture());
    assertThat(captor.getAllValues())
        .filteredOn(p -> isFor(p, square))
        .as("every publish for Square")
        .allMatch(p -> p.getDiagnostics().isEmpty());
  }

  @Test
  void didChange_unsavedSiblingChangedSignature_analyzedAgainstIt_unlessItNoLongerParses()
      throws Exception {
    // An unused field is reported only by an analysis without errors, so a publish carrying it is
    // a clean analysis, not an empty one.
    final var sources = new LinkedHashMap<String, String>();
    sources.put(
        "com/example/Greeter.java",
        "package com.example; class Greeter { static String greet(String name) { return name; } }");
    sources.put(
        "com/example/User.java",
        "package com.example; class User { private int marker; String u() { return Greeter.greet(\"a\"); } }");
    final List<Path> files =
        TestCompiler.writeMirroredModule(tmp, "module", sources, List.of(), 1_000L);
    final Path greeter = files.get(0);
    final Path user = files.get(1);
    host.initialize(tmp);
    awaitStartup();
    openDoc(greeter, Files.readString(greeter));
    openDoc(user, Files.readString(user));
    awaitWorkerIdle();

    // Greeter gains a parameter, unsaved; User calls it the new way. Against the written Greeter
    // that call is an error -- a kind the old error-code widening never looked at.
    clearInvocations(client);
    change(
        greeter,
        "package com.example; class Greeter { private static int g; static String greet(String name, int times) { return name; } }",
        2);
    verify(client, timeout(TIMEOUT_MS))
        .publishDiagnostics(argThat(p -> isFor(p, greeter) && hasMessage(p, "'g'")));
    clearInvocations(client);
    change(
        user,
        "package com.example; class User { private int marker; String u() { return Greeter.greet(\"a\", 2); } }",
        2);
    verify(client, timeout(TIMEOUT_MS))
        .publishDiagnostics(
            argThat(p -> isFor(p, user) && hasMessage(p, "'marker'") && !hasError(p)));

    // Greeter is mid-edit and no longer parses: User falls back to the written Greeter.
    clearInvocations(client);
    change(
        greeter,
        "package com.example; class Greeter { static String greet(String name, int times) { return name; }",
        3);
    verify(client, timeout(TIMEOUT_MS))
        .publishDiagnostics(argThat(p -> isFor(p, greeter) && hasError(p)));
    clearInvocations(client);
    change(
        user,
        "package com.example; class User { private int marker; String u() { return Greeter.greet(\"a\", 2);  } }",
        3);
    verify(client, timeout(TIMEOUT_MS))
        .publishDiagnostics(argThat(p -> isFor(p, user) && hasError(p)));
  }

  // The diagnostics stay while the file is edited, until the next analysis replaces them: an empty
  // publish on each change made every error in the file flash off at each keystroke.
  @Test
  void didChange_errorStillThere_keepsTheDiagnosticsUntilTheNextAnalysis() throws Exception {
    final Path broken =
        TestCompiler.writeMirroredModule(
                tmp,
                "module",
                Map.of("com/example/Broken.java", "package com.example; class Broken {}"),
                List.of(),
                1_000L)
            .get(0);
    host.initialize(tmp);
    awaitStartup();
    openDoc(broken, "package com.example; class Broken { Missing m; }");
    verify(client, timeout(TIMEOUT_MS))
        .publishDiagnostics(argThat(p -> isFor(p, broken) && hasError(p)));
    awaitWorkerIdle();

    clearInvocations(client);
    change(broken, "package com.example; class Broken { Missing m; int x; }", 2);
    verify(client, timeout(TIMEOUT_MS))
        .publishDiagnostics(argThat(p -> isFor(p, broken) && hasError(p)));
    awaitWorkerIdle();
    verify(client, never())
        .publishDiagnostics(argThat(p -> isFor(p, broken) && p.getDiagnostics().isEmpty()));

    clearInvocations(client);
    change(broken, "package com.example; class Broken { int x; }", 3);
    verify(client, timeout(TIMEOUT_MS))
        .publishDiagnostics(argThat(p -> isFor(p, broken) && p.getDiagnostics().isEmpty()));
  }

  // An edit undone and saved before its analysis: the saved file matches what the build compiled,
  // so no write runs, and the error of the edit must not stay on the clean file.
  @Test
  void didSave_editUndoneBeforeItsAnalysis_clearsTheEditsDiagnostics() throws Exception {
    final Path file =
        TestCompiler.writeMirroredModule(
                tmp,
                "module",
                Map.of("com/example/Clean.java", "package com.example; class Clean {}"),
                List.of(),
                1_000L)
            .get(0);
    final String saved = Files.readString(file);
    host.initialize(tmp);
    awaitStartup();
    openDoc(file, saved);
    save(file);
    reconcileNow(true).get(10, TimeUnit.SECONDS);
    awaitWorkerIdle();
    change(file, "package com.example; class Clean { Missing m; }", 2);
    verify(client, timeout(TIMEOUT_MS))
        .publishDiagnostics(argThat(p -> isFor(p, file) && hasError(p)));
    awaitWorkerIdle();

    clearInvocations(client);
    change(file, saved, 3);
    save(file);
    verify(client, timeout(TIMEOUT_MS))
        .publishDiagnostics(argThat(p -> isFor(p, file) && p.getDiagnostics().isEmpty()));
  }

  private static boolean hasError(final PublishDiagnosticsParams params) {
    return params.getDiagnostics().stream()
        .anyMatch(diagnostic -> diagnostic.getSeverity() == DiagnosticSeverity.Error);
  }
}
