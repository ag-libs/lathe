package io.github.aglibs.lathe.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.analysis.testing.SampleFixture;
import io.github.aglibs.lathe.analysis.testing.TempSourceCompiler;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
            TempSourceCompiler.reactorShards(
                List.of(
                    entry("Parent", TypeKind.INTERFACE),
                    entry("Service", TypeKind.INTERFACE, "Parent"),
                    entry("Direct", TypeKind.CLASS, "Service"),
                    entry("Grandchild", TypeKind.CLASS, "Direct"),
                    entry("Internal", TypeKind.CLASS, "Service"))));
    final var request = request(content, new Position(0, 12));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);

      final List<TypeHierarchyItem> prepared =
          session.hierarchy().prepareTypeHierarchy(request, index);
      final var item = prepared.get(0);
      final var data = TypeHierarchyItemDataCodec.decode(item.getData());
      final List<TypeHierarchyItem> supertypes =
          session.hierarchy().typeHierarchySupertypes(item, index, List.of(sourceRoot));
      final List<TypeHierarchyItem> subtypes =
          session.hierarchy().typeHierarchySubtypes(item, index, List.of(sourceRoot));

      assertThat(item.getName()).isEqualTo("Service");
      assertThat(item.getSelectionRange().getStart()).isEqualTo(new Position(0, 10));
      assertThat(data).isEqualTo(new TypeHierarchyItemData("Service", item.getUri()));
      assertThat(supertypes).extracting(TypeHierarchyItem::getName).containsExactly("Parent");
      assertThat(subtypes).extracting(TypeHierarchyItem::getName).containsExactly("Direct");
    }
  }

  // Sibling is declared in Direct.java: only the file the index records leads to it.
  @Test
  void hierarchy_secondaryTopLevelSubtype_listedOnlyWhenTheIndexRecordsItsFile()
      throws IOException {
    final String content = "interface Service {}\n";
    write("Service.java", content);
    write(
        "Direct.java", "class Direct implements Service {}\nclass Sibling implements Service {}\n");
    final var request = request(content, new Position(0, 12));
    final var unrecorded = entry("Sibling", TypeKind.CLASS, "Service");
    final var recorded =
        new TypeIndexEntry(
            "Sibling", "Sibling", "", TypeKind.CLASS, false, List.of("Service"), "Direct.java");

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);

      for (final var sibling : List.of(recorded, unrecorded)) {
        final var index =
            WorkspaceTypeIndex.build(
                List.of(),
                TempSourceCompiler.reactorShards(
                    List.of(
                        entry("Service", TypeKind.INTERFACE),
                        entry("Direct", TypeKind.CLASS, "Service"),
                        sibling)));
        final var item = session.hierarchy().prepareTypeHierarchy(request, index).get(0);
        final List<TypeHierarchyItem> subtypes =
            session.hierarchy().typeHierarchySubtypes(item, index, List.of(sourceRoot));

        if (sibling == recorded) {
          assertThat(subtypes)
              .extracting(TypeHierarchyItem::getName)
              .containsExactlyInAnyOrder("Direct", "Sibling");
          assertThat(subtypes)
              .filteredOn(subtype -> subtype.getName().equals("Sibling"))
              .singleElement()
              .satisfies(subtype -> assertThat(subtype.getUri()).endsWith("Direct.java"));
        } else {
          assertThat(subtypes).extracting(TypeHierarchyItem::getName).containsExactly("Direct");
        }
      }
    }
  }

  @Test
  void explore_typeCursor_returnsTransitiveSupertypesSelfAndSubtypes() throws IOException {
    final String content = "interface Service {}\n";
    write("Service.java", content);
    write("Parent.java", "interface Parent {}\n");
    write("Direct.java", "class Direct implements Service {}\n");
    write("Grandchild.java", "class Grandchild extends Direct {}\n");
    write("Internal.java", "class Internal implements Service {}\n");
    final var index =
        WorkspaceTypeIndex.build(
            List.of(),
            TempSourceCompiler.reactorShards(
                List.of(
                    entry("Parent", TypeKind.INTERFACE),
                    entry("Service", TypeKind.INTERFACE, "Parent"),
                    entry("Direct", TypeKind.CLASS, "Service"),
                    entry("Grandchild", TypeKind.CLASS, "Direct"),
                    entry("Internal", TypeKind.CLASS, "Service"))));
    final var request = request(content, new Position(0, 12));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);
      final var item = session.hierarchy().prepareTypeHierarchy(request, index).get(0);

      final var result =
          session.hierarchy().typeHierarchyExplore(item, index, List.of(sourceRoot), 2000);

      assertThat(result.self().getName()).isEqualTo("Service");
      assertThat(result.supertypes())
          .extracting(TypeHierarchyItem::getName)
          .containsExactly("Parent");
      assertThat(result.subtypes())
          .extracting(TypeHierarchyItem::getName)
          .containsExactlyInAnyOrder("Direct", "Grandchild", "Internal");
      assertThat(result.truncated()).isFalse();
    }
  }

  @Test
  void explore_nodeCap_truncatesSubtypes() throws IOException {
    final String content = "interface Service {}\n";
    write("Service.java", content);
    write("Direct.java", "class Direct implements Service {}\n");
    write("Grandchild.java", "class Grandchild extends Direct {}\n");
    final var index =
        WorkspaceTypeIndex.build(
            List.of(),
            TempSourceCompiler.reactorShards(
                List.of(
                    entry("Service", TypeKind.INTERFACE),
                    entry("Direct", TypeKind.CLASS, "Service"),
                    entry("Grandchild", TypeKind.CLASS, "Direct"))));
    final var request = request(content, new Position(0, 12));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);
      final var item = session.hierarchy().prepareTypeHierarchy(request, index).get(0);

      final var result =
          session.hierarchy().typeHierarchyExplore(item, index, List.of(sourceRoot), 1);

      assertThat(result.subtypes()).hasSize(1);
      assertThat(result.truncated()).isTrue();
    }
  }

  private static final String BASE = "class Base { int id; int getId() { return id; } }\n";
  private static final String SHOP =
      """
      class Shop extends Base {
          void sell(Base other) {
              int count = other.getId();
              Runnable r = new Runnable() { public void run() {} };
              System.out.println("hi" + other.toString());
          }
      }
      """;

  // EG-118. A literal in a call's arguments is about the class it is in, not the called method's;
  // an anonymous class and Object, neither of them a hierarchy to show, give way to that class.
  @ParameterizedTest
  @CsvSource({
    "int getId(, getId, Base",
    "int id;, id, Base",
    "other.getId(), getId, Base",
    "int count, count, Shop",
    "void run(), run, Shop",
    "new Runnable() {, {, Shop",
    "\"hi\" +, hi, Shop",
    "other.toString(), toString, Shop",
    "import java.util.List;, import, ''",
  })
  void prepareTypeHierarchy_cursorOffATypeName_preparesTheClassThePositionIsAbout(
      final String snippet, final String token, final String expected) throws IOException {
    write("Base.java", BASE);
    write("Shop.java", SHOP);
    final var content = "import java.util.List;\n%s%s".formatted(BASE, SHOP);
    final var index =
        WorkspaceTypeIndex.build(
            List.of(),
            TempSourceCompiler.reactorShards(
                List.of(entry("Base", TypeKind.CLASS), entry("Shop", TypeKind.CLASS, "Base"))));
    final var request = request(content, SampleFixture.posOf(content, snippet, token));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);

      assertThat(session.hierarchy().prepareTypeHierarchy(request, index))
          .extracting(TypeHierarchyItem::getName)
          .containsExactlyElementsOf(expected.isEmpty() ? List.of() : List.of(expected));
    }
  }

  @Test
  void prepareTypeHierarchy_outOfRangePosition_returnsEmpty() {
    final String content = "interface Service {}\n";
    final var request = request(content, new Position(9999, 0));
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);
      assertThat(session.hierarchy().prepareTypeHierarchy(request, WorkspaceTypeIndex.empty()))
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
        LibrarySources.none());
  }

  private void write(final String name, final String content) throws IOException {
    Files.writeString(sourceRoot.resolve(name), content);
  }

  private static TypeIndexEntry entry(
      final String binaryName, final TypeKind kind, final String... directSupertypes) {
    return new TypeIndexEntry(
        binaryName, binaryName, "", kind, true, List.of(directSupertypes), null);
  }
}
