package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceSymbolTest {

  @TempDir private Path src;

  private static TypeIndexEntry entry(
      final String simpleName, final String pkg, final TypeKind kind) {
    final String qualName = pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
    return new TypeIndexEntry(simpleName, qualName, pkg, kind);
  }

  private WorkspaceTypeIndex indexOf(final TypeIndexEntry... entries) {
    return WorkspaceTypeIndex.build(List.of(), List.of(List.of(entries)));
  }

  private void createSourceFile(final String pkg, final String simpleName) throws IOException {
    final Path dir = pkg.isEmpty() ? src : src.resolve(pkg.replace('.', '/'));
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(simpleName + ".java"), "");
  }

  // --- resolve: basic matching ---

  @Test
  void resolve_emptyQuery_returnsEmpty() {
    final var index = indexOf(entry("MyClass", "com.example", TypeKind.CLASS));
    final List<SymbolInformation> result = WorkspaceSymbolResolver.resolve("", index, List.of(src));
    assertThat(result).isEmpty();
  }

  @Test
  void resolve_blankQuery_returnsEmpty() throws IOException {
    createSourceFile("com.example", "MyClass");
    final var index = indexOf(entry("MyClass", "com.example", TypeKind.CLASS));
    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve("   ", index, List.of(src));
    assertThat(result).isEmpty();
  }

  @Test
  void resolve_matchingClass_returnsSymbol() throws IOException {
    createSourceFile("com.example", "UserService");
    final var index = indexOf(entry("UserService", "com.example", TypeKind.CLASS));
    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve("User", index, List.of(src));
    assertThat(result).hasSize(1);
    assertThat(result.getFirst().getName()).isEqualTo("UserService");
    assertThat(result.getFirst().getKind()).isEqualTo(SymbolKind.Class);
    assertThat(result.getFirst().getContainerName()).isEqualTo("com.example");
  }

  @Test
  void resolve_noMatchingFile_excludesResult() {
    final var index = indexOf(entry("Missing", "com.example", TypeKind.CLASS));
    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve("Miss", index, List.of(src));
    assertThat(result).isEmpty();
  }

  @Test
  void resolve_interfaceKind_mapsToInterface() throws IOException {
    createSourceFile("com.example", "Printable");
    final var index = indexOf(entry("Printable", "com.example", TypeKind.INTERFACE));
    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve("Print", index, List.of(src));
    assertThat(result.getFirst().getKind()).isEqualTo(SymbolKind.Interface);
  }

  @Test
  void resolve_enumKind_mapsToEnum() throws IOException {
    createSourceFile("com.example", "Color");
    final var index = indexOf(entry("Color", "com.example", TypeKind.ENUM));
    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve("Col", index, List.of(src));
    assertThat(result.getFirst().getKind()).isEqualTo(SymbolKind.Enum);
  }

  @Test
  void resolve_recordKind_mapsToClass() throws IOException {
    createSourceFile("com.example", "Point");
    final var index = indexOf(entry("Point", "com.example", TypeKind.RECORD));
    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve("Poi", index, List.of(src));
    assertThat(result.getFirst().getKind()).isEqualTo(SymbolKind.Class);
  }

  @Test
  void resolve_defaultPackage_resolvesRootFile() throws IOException {
    createSourceFile("", "Standalone");
    final var index = indexOf(entry("Standalone", "", TypeKind.CLASS));
    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve("Stand", index, List.of(src));
    assertThat(result).hasSize(1);
    assertThat(result.getFirst().getName()).isEqualTo("Standalone");
  }

  @Test
  void resolve_locationPointsToFileStart() throws IOException {
    createSourceFile("com.example", "Foo");
    final var index = indexOf(entry("Foo", "com.example", TypeKind.CLASS));
    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve("Foo", index, List.of(src));
    final var range = result.getFirst().getLocation().getRange();
    assertThat(range.getStart().getLine()).isZero();
    assertThat(range.getStart().getCharacter()).isZero();
    assertThat(range.getEnd().getLine()).isZero();
    assertThat(range.getEnd().getCharacter()).isZero();
  }

  // --- resolveSourcePath: inner class handling ---

  @Test
  void resolveSourcePath_innerClass_resolvesToOuterFile() throws IOException {
    createSourceFile("com.example", "Outer");
    final var innerEntry =
        new TypeIndexEntry("Inner", "com.example.Outer.Inner", "com.example", TypeKind.CLASS);
    final Path result = WorkspaceSymbolResolver.resolveSourcePath(innerEntry, List.of(src));
    assertThat(result).isNotNull();
    assertThat(result.getFileName().toString()).isEqualTo("Outer.java");
  }

  @Test
  void resolveSourcePath_noMatchingDir_returnsNull() {
    final var entry = entry("Ghost", "com.missing", TypeKind.CLASS);
    assertThat(WorkspaceSymbolResolver.resolveSourcePath(entry, List.of(src))).isNull();
  }
}
