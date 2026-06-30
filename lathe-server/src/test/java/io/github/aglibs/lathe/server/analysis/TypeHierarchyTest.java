package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TypeHierarchyTest {

  @TempDir private Path sourceRoot;

  @Test
  void hierarchy_typeCursor_preparesAndReturnsDirectSourceBackedRelations() throws IOException {
    final String content = "interface Service {}\n";
    write("Service.java", content);
    write("Parent.java", "interface Parent {}\n");
    write("Direct.java", "class Direct implements Service {}\n");
    write("Grandchild.java", "class Grandchild extends Direct {}\n");
    final var index =
        WorkspaceTypeIndex.build(
            List.of(),
            List.of(
                List.of(
                    entry("Parent", TypeKind.INTERFACE),
                    entry("Service", TypeKind.INTERFACE, "Parent"),
                    entry("Direct", TypeKind.CLASS, "Service"),
                    entry("Grandchild", TypeKind.CLASS, "Direct"),
                    entry("Internal", TypeKind.CLASS, "Service"))));
    final var request = request(content, new Position(0, 12));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);

      final List<TypeHierarchyItem> prepared = session.prepareTypeHierarchy(request, index);
      final var item = prepared.getFirst();
      final var data = TypeHierarchyItemDataCodec.decode(item.getData());
      final List<TypeHierarchyItem> supertypes =
          session.typeHierarchySupertypes(item, index, List.of(sourceRoot));
      final List<TypeHierarchyItem> subtypes =
          session.typeHierarchySubtypes(item, index, List.of(sourceRoot));

      assertThat(item.getName()).isEqualTo("Service");
      assertThat(item.getSelectionRange().getStart()).isEqualTo(new Position(0, 10));
      assertThat(data).isEqualTo(new TypeHierarchyItemData("Service", item.getUri()));
      assertThat(supertypes).extracting(TypeHierarchyItem::getName).containsExactly("Parent");
      assertThat(subtypes).extracting(TypeHierarchyItem::getName).containsExactly("Direct");
    }
  }

  @Test
  void prepareTypeHierarchy_methodCursor_returnsEmpty() {
    final String content = "class Service { void execute() {} }\n";
    final var request = request(content, new Position(0, 22));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);

      assertThat(session.prepareTypeHierarchy(request, WorkspaceTypeIndex.empty())).isEmpty();
    }
  }

  @Test
  void prepareTypeHierarchy_outOfRangePosition_returnsEmpty() {
    final String content = "interface Service {}\n";
    final var request = request(content, new Position(9999, 0));
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);
      assertThat(session.prepareTypeHierarchy(request, WorkspaceTypeIndex.empty())).isEmpty();
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

  private void write(final String name, final String content) throws IOException {
    Files.writeString(sourceRoot.resolve(name), content);
  }

  private static TypeIndexEntry entry(
      final String binaryName, final TypeKind kind, final String... directSupertypes) {
    return new TypeIndexEntry(binaryName, binaryName, "", kind, true, List.of(directSupertypes));
  }
}
