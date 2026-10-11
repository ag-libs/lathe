package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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
    service.initialize(tmp);

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

    verify(client, timeout(5_000)).publishDiagnostics(argThat(p -> isFor(p, square)));
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
    // a clean analysis -- not the empty publish that clears a file on each change.
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
    service.initialize(tmp);
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
    verify(client, timeout(5_000))
        .publishDiagnostics(argThat(p -> isFor(p, greeter) && hasMessage(p, "'g'")));
    clearInvocations(client);
    change(
        user,
        "package com.example; class User { private int marker; String u() { return Greeter.greet(\"a\", 2); } }",
        2);
    verify(client, timeout(5_000))
        .publishDiagnostics(
            argThat(p -> isFor(p, user) && hasMessage(p, "'marker'") && !hasError(p)));

    // Greeter is mid-edit and no longer parses: User falls back to the written Greeter.
    clearInvocations(client);
    change(
        greeter,
        "package com.example; class Greeter { static String greet(String name, int times) { return name; }",
        3);
    verify(client, timeout(5_000))
        .publishDiagnostics(argThat(p -> isFor(p, greeter) && hasError(p)));
    clearInvocations(client);
    change(
        user,
        "package com.example; class User { private int marker; String u() { return Greeter.greet(\"a\", 2);  } }",
        3);
    verify(client, timeout(5_000)).publishDiagnostics(argThat(p -> isFor(p, user) && hasError(p)));
  }

  private static boolean hasError(final PublishDiagnosticsParams params) {
    return params.getDiagnostics().stream()
        .anyMatch(diagnostic -> diagnostic.getSeverity() == DiagnosticSeverity.Error);
  }
}
