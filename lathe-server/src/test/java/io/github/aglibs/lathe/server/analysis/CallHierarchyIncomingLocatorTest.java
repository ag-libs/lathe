package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.nio.file.Path;
import java.util.List;
import javax.lang.model.element.ElementKind;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CallHierarchyIncomingLocatorTest {

  @TempDir private Path sourceRoot;

  @Test
  void searchIncomingCalls_memberSelectCall_returnsCallerAndRange() {
    final String content =
        """
        class Caller { void invoke() { new Target().run(); } }
        class Target { void run() {} }
        """;
    // cursor on "run" declaration in Target at (1, 20)
    final var targetRequest = request(content, new Position(1, 20));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(targetRequest.uri(), content, 1, CompileMode.OPEN);

      final List<CallHierarchyItem> items = session.prepareCallHierarchy(targetRequest);
      assertThat(items).hasSize(1);
      assertThat(items.getFirst().getName()).isEqualTo("run");

      final List<CallHierarchyIncomingCall> calls =
          session.searchIncomingCalls(
              TempSourceCompiler.TEST_URI, content, 1, targetFrom(items.getFirst()), () -> {});

      assertThat(calls).hasSize(1);
      assertThat(calls.getFirst().getFrom().getName()).isEqualTo("invoke");
      assertThat(calls.getFirst().getFromRanges()).hasSize(1);
      assertThat(calls.getFirst().getFromRanges().getFirst().getStart().getLine()).isEqualTo(0);
    }
  }

  @Test
  void searchIncomingCalls_constructorCall_returnsCallerAndRange() {
    final String content =
        """
        class Caller { void build() { new Box(); } }
        class Box { Box() {} }
        """;
    // cursor on "Box" constructor at (1, 12)
    final var targetRequest = request(content, new Position(1, 12));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(targetRequest.uri(), content, 1, CompileMode.OPEN);

      final List<CallHierarchyItem> items = session.prepareCallHierarchy(targetRequest);
      assertThat(items).hasSize(1);
      assertThat(items.getFirst().getName()).isEqualTo("Box");

      final CallHierarchyItemData data =
          CallHierarchyItemDataCodec.decode(items.getFirst().getData());
      assertThat(data).isNotNull();
      assertThat(data.kind()).isEqualTo(ElementKind.CONSTRUCTOR);

      final List<CallHierarchyIncomingCall> calls =
          session.searchIncomingCalls(
              TempSourceCompiler.TEST_URI, content, 1, targetFrom(items.getFirst()), () -> {});

      assertThat(calls).hasSize(1);
      assertThat(calls.getFirst().getFrom().getName()).isEqualTo("build");
      assertThat(calls.getFirst().getFromRanges()).hasSize(1);
    }
  }

  @Test
  void searchIncomingCalls_noCallers_returnsEmpty() {
    final String content =
        """
        class Isolated { void helper() {} }
        class Other { void doWork() {} }
        """;
    // cursor on "helper" at (0, 22)
    final var targetRequest = request(content, new Position(0, 22));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(targetRequest.uri(), content, 1, CompileMode.OPEN);

      final List<CallHierarchyItem> items = session.prepareCallHierarchy(targetRequest);
      assertThat(items).hasSize(1);

      final List<CallHierarchyIncomingCall> calls =
          session.searchIncomingCalls(
              TempSourceCompiler.TEST_URI, content, 1, targetFrom(items.getFirst()), () -> {});

      assertThat(calls).isEmpty();
    }
  }

  private static ReferenceTarget targetFrom(final CallHierarchyItem item) {
    final CallHierarchyItemData data = CallHierarchyItemDataCodec.decode(item.getData());
    return new ReferenceTarget(
        data.kind(),
        data.ownerBinaryName(),
        data.methodName(),
        data.erasedDescriptor(),
        data.scope(),
        List.of(),
        false);
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
