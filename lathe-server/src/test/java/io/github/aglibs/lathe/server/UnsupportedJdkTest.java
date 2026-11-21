package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.junit.jupiter.api.Test;

// WS-46: a module built for a Java the server's JDK does not know (here 99, which no javac knows)
// fails fast, saying why and how to fix it, and the rest of the workspace works as before.
class UnsupportedJdkTest extends ServiceTestBase {

  @Test
  void open_moduleBuiltForANewerJava_toldOnceAndItsFileSaysWhyOtherModulesWork() throws Exception {
    final Path newer =
        TestCompiler.writeMirroredModule(
                tmp,
                "newer",
                Map.of("com/example/A.java", "package com.example; class A {}"),
                List.of(),
                1_000L)
            .get(0);
    TestCompiler.writeModuleParams(
        tmp,
        "newer",
        List.of(tmp.resolve("newer/src/main/java")),
        null,
        List.of(),
        List.of(),
        List.of("--release", "99"));
    final Path other =
        TestCompiler.writeMirroredModule(
                tmp,
                "other",
                Map.of("com/example/b/B.java", "package com.example.b; class B {}"),
                List.of(),
                1_000L)
            .get(0);
    host.initialize(tmp);
    awaitStartup();

    verify(client, timeout(TIMEOUT_MS))
        .showMessage(
            argThat(
                message ->
                    message.getType() == MessageType.Error
                        && message.getMessage().contains("module newer needs JDK 99")
                        && message.getMessage().contains("LATHE_JAVA_HOME")));

    openDoc(newer, Files.readString(newer));
    openDoc(other, "package com.example.b; class B { int x = \"s\"; }");
    awaitWorkerIdle();

    verify(client, timeout(TIMEOUT_MS))
        .publishDiagnostics(
            argThat(
                p ->
                    isFor(p, newer)
                        && p.getDiagnostics().size() == 1
                        && p.getDiagnostics().get(0).getSeverity() == DiagnosticSeverity.Error
                        && hasMessage(p, "this file's module newer is built for Java 99")));
    verify(client, timeout(TIMEOUT_MS))
        .publishDiagnostics(argThat(p -> isFor(p, other) && hasMessage(p, "incompatible types")));
    verify(client, never())
        .publishDiagnostics(argThat(p -> isFor(p, newer) && hasMessage(p, "release version")));
  }

  // A build's sync reloads the workspace: the user, told once, is not told again while the same
  // modules are left out, and hears the reload as usual.
  @Test
  void reload_sameModulesLeftOut_notToldAgain() throws Exception {
    writeNewerModule();
    host.initialize(tmp);
    awaitStartup();
    verify(client, timeout(TIMEOUT_MS)).showMessage(argThat(UnsupportedJdkTest::jdkError));

    TestCompiler.writeMirroredModule(
        tmp,
        "lib",
        Map.of("com/lib/Lib.java", "package com.lib;\npublic class Lib {}\n"),
        List.of(),
        1_000L);
    reconcileForVerify().get(TIMEOUT_S, TimeUnit.SECONDS);

    verify(client).showMessage(new MessageParams(MessageType.Info, "Lathe: workspace reloaded."));
    verify(client, times(1)).showMessage(argThat(UnsupportedJdkTest::jdkError));
  }

  // A module at the reactor root has an empty path; the user reads it as ".".
  @Test
  void initialize_rootModuleBuiltForANewerJava_namesItAsTheRoot() throws Exception {
    TestCompiler.writeModuleParams(
        tmp,
        "",
        List.of(tmp.resolve("src/main/java")),
        null,
        List.of(),
        List.of(),
        List.of("--release", "99"));
    Files.createDirectories(tmp.resolve("src/main/java"));
    host.initialize(tmp);
    awaitStartup();

    verify(client, timeout(TIMEOUT_MS))
        .showMessage(argThat(message -> message.getMessage().contains("module . needs JDK 99")));
  }

  private static boolean jdkError(final MessageParams message) {
    return message.getType() == MessageType.Error && message.getMessage().contains("needs JDK 99");
  }

  private Path writeNewerModule() throws Exception {
    final Path newer =
        TestCompiler.writeMirroredModule(
                tmp,
                "newer",
                Map.of("com/example/A.java", "package com.example; class A {}"),
                List.of(),
                1_000L)
            .get(0);
    TestCompiler.writeModuleParams(
        tmp,
        "newer",
        List.of(tmp.resolve("newer/src/main/java")),
        null,
        List.of(),
        List.of(),
        List.of("--release", "99"));
    return newer;
  }

  // What an agent's get_diagnostics answers for such a file: the same reason, not an empty list.
  @Test
  void diagnostics_fileOfAModuleBuiltForANewerJava_theReason() throws Exception {
    final Path newer =
        TestCompiler.writeMirroredModule(
                tmp,
                "newer",
                Map.of("com/example/A.java", "package com.example; class A {}"),
                List.of(),
                1_000L)
            .get(0);
    TestCompiler.writeModuleParams(
        tmp,
        "newer",
        List.of(tmp.resolve("newer/src/main/java")),
        null,
        List.of(),
        List.of(),
        List.of("--release", "99"));
    host.initialize(tmp);
    awaitStartup();

    final String content = Files.readString(newer);
    final List<Diagnostic> answer =
        host.onLoopFuture(
                session -> session.diagnosticsFuture(newer.toUri().toString(), content, 1))
            .get(TIMEOUT_S, TimeUnit.SECONDS);

    assertThat(answer)
        .singleElement()
        .satisfies(
            diagnostic -> {
              assertThat(diagnostic.getSeverity()).isEqualTo(DiagnosticSeverity.Error);
              assertThat(diagnostic.getMessage().getLeft()).contains("built for Java 99");
            });
  }
}
