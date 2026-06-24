package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.lang.model.element.ElementKind;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SymbolKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CallHierarchyPrepareTest {

  @TempDir private Path sourceRoot;

  @Test
  void prepareCallHierarchy_onMethodDeclaration_returnsItemWithRanges() throws IOException {
    final String content = "class Service { void execute(String s) {} }\n";
    Files.writeString(sourceRoot.resolve("Service.java"), content);
    final var request = request(content, new Position(0, 22));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);

      final List<CallHierarchyItem> items = session.prepareCallHierarchy(request);
      assertThat(items).hasSize(1);

      final CallHierarchyItem item = items.getFirst();
      assertThat(item.getName()).isEqualTo("execute");
      assertThat(item.getKind()).isEqualTo(SymbolKind.Function);
      assertThat(item.getDetail()).isEqualTo("Service");
      assertThat(item.getSelectionRange().getStart()).isEqualTo(new Position(0, 21));

      final CallHierarchyItemData data = CallHierarchyItemDataCodec.decode(item.getData());
      assertThat(data).isNotNull();
      assertThat(data.ownerBinaryName()).isEqualTo("Service");
      assertThat(data.methodName()).isEqualTo("execute");
      assertThat(data.kind()).isEqualTo(ElementKind.METHOD);
      assertThat(data.scope()).isEqualTo(ReferenceTarget.SearchScope.DECLARING_MODULE);
    }
  }

  @Test
  void prepareCallHierarchy_onConstructor_returnsItemWithClassName() throws IOException {
    final String content = "class Builder { Builder() {} }\n";
    Files.writeString(sourceRoot.resolve("Builder.java"), content);
    final var request = request(content, new Position(0, 17));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);

      final List<CallHierarchyItem> items = session.prepareCallHierarchy(request);
      assertThat(items).hasSize(1);

      final CallHierarchyItem item = items.getFirst();
      assertThat(item.getName()).isEqualTo("Builder");
      assertThat(item.getKind()).isEqualTo(SymbolKind.Constructor);

      final CallHierarchyItemData data = CallHierarchyItemDataCodec.decode(item.getData());
      assertThat(data).isNotNull();
      assertThat(data.methodName()).isEqualTo("<init>");
      assertThat(data.kind()).isEqualTo(ElementKind.CONSTRUCTOR);
    }
  }

  @Test
  void prepareCallHierarchy_onTypeName_returnsEmpty() throws IOException {
    final String content = "class Service { void execute() {} }\n";
    Files.writeString(sourceRoot.resolve("Service.java"), content);
    final var request = request(content, new Position(0, 7));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);

      assertThat(session.prepareCallHierarchy(request)).isEmpty();
    }
  }

  private SourceFeatureRequest request(final String content, final Position position) {
    return new SourceFeatureRequest(
        TempSourceCompiler.TEST_URI,
        content,
        position,
        List.of(sourceRoot),
        WorkspaceManifest.empty());
  }
}
