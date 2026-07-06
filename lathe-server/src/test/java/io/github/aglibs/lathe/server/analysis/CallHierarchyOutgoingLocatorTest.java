package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CallHierarchyOutgoingLocatorTest {

  @TempDir private Path sourceRoot;

  @Test
  void outgoingCalls_methodWithCallee_returnsCallWithRange() throws IOException {
    Files.writeString(
        sourceRoot.resolve("Callee.java"),
        """
        class Callee { void doStuff(String s) {} }
        """);
    final String content =
        """
        class Target {
            void work() {
                Callee c = make();
                c.doStuff("x");
            }
            private Callee make() { return null; }
        }
        class Callee { void doStuff(String s) {} }
        """;
    final var request = request(content, new Position(1, 9));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);

      final List<CallHierarchyItem> items = session.prepareCallHierarchy(request);
      assertThat(items).hasSize(1);

      final List<CallHierarchyOutgoingCall> calls =
          session.outgoingCalls(items.getFirst(), request.uri(), content, 1, List.of(sourceRoot));

      assertThat(calls).hasSize(1);
      assertThat(calls.getFirst().getTo().getName()).isEqualTo("doStuff");
      assertThat(calls.getFirst().getFromRanges()).hasSize(1);
    }
  }

  @Test
  void outgoingCalls_constructorCall_returnsConstructorCallee() throws IOException {
    Files.writeString(
        sourceRoot.resolve("Box.java"),
        """
        class Box { Box(int n) {} }
        """);
    final String content =
        """
        class Factory {
            Object create() {
                return new Box(42);
            }
        }
        class Box { Box(int n) {} }
        """;
    final var request = request(content, new Position(1, 11));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);

      final List<CallHierarchyItem> items = session.prepareCallHierarchy(request);
      assertThat(items).hasSize(1);

      final List<CallHierarchyOutgoingCall> calls =
          session.outgoingCalls(items.getFirst(), request.uri(), content, 1, List.of(sourceRoot));

      assertThat(calls).hasSize(1);
      assertThat(calls.getFirst().getTo().getName()).isEqualTo("Box");
      assertThat(calls.getFirst().getFromRanges()).hasSize(1);
    }
  }

  @Test
  void outgoingCalls_anonymousClassInstantiation_excludedFromResults() throws IOException {
    final String content =
        """
        class Task {
            void run() {
                execute(new Runnable() {
                    public void run() {}
                });
            }
            private void execute(Runnable r) {}
        }
        """;
    Files.writeString(sourceRoot.resolve("Task.java"), content);
    final var request = request(content, new Position(1, 9));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);

      final List<CallHierarchyItem> items = session.prepareCallHierarchy(request);
      assertThat(items).hasSize(1);

      final List<CallHierarchyOutgoingCall> calls =
          session.outgoingCalls(items.getFirst(), request.uri(), content, 1, List.of(sourceRoot));

      assertThat(calls).noneMatch(c -> c.getTo().getName().isEmpty());
    }
  }

  @Test
  void outgoingCalls_emptyMethodBody_returnsEmpty() {
    final String content = "class Thing { void noop() {} }\n";
    final var request = request(content, new Position(0, 19));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);

      final List<CallHierarchyItem> items = session.prepareCallHierarchy(request);
      assertThat(items).hasSize(1);

      assertThat(
              session.outgoingCalls(
                  items.getFirst(), request.uri(), content, 1, List.of(sourceRoot)))
          .isEmpty();
    }
  }

  private SourceFeatureRequest request(final String content, final Position position) {
    return new SourceFeatureRequest(
        TempSourceCompiler.TEST_URI,
        content,
        0,
        position,
        List.of(sourceRoot),
        WorkspaceManifest.empty());
  }
}
