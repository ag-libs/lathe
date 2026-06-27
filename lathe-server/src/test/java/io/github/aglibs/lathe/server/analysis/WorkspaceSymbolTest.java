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
    return new TypeIndexEntry(simpleName, qualName, pkg, kind, true, List.of());
  }

  private WorkspaceTypeIndex indexOf(final TypeIndexEntry... entries) {
    return WorkspaceTypeIndex.build(List.of(), List.of(List.of(entries)));
  }

  private void createSourceFile(final String pkg, final String simpleName) throws IOException {
    final Path dir = pkg.isEmpty() ? src : src.resolve(pkg.replace('.', '/'));
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(simpleName + ".java"), "");
  }

  // --- resolve: query guards ---

  @Test
  void resolve_emptyOrBlankQuery_returnsEmpty() throws IOException {
    createSourceFile("com.example", "MyClass");
    final var index = indexOf(entry("MyClass", "com.example", TypeKind.CLASS));
    assertThat(WorkspaceSymbolResolver.resolve("", index, List.of(src))).isEmpty();
    assertThat(WorkspaceSymbolResolver.resolve("   ", index, List.of(src))).isEmpty();
  }

  // --- resolve: basic matching ---

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
  void resolve_defaultPackage_resolvesRootFile() throws IOException {
    createSourceFile("", "Standalone");
    final var index = indexOf(entry("Standalone", "", TypeKind.CLASS));
    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve("Stand", index, List.of(src));
    assertThat(result).hasSize(1);
    assertThat(result.getFirst().getName()).isEqualTo("Standalone");
  }

  @Test
  void resolve_multipleMatchingTypes_returnsAll() throws IOException {
    createSourceFile("com.example", "FooBar");
    createSourceFile("com.example", "FooBaz");
    final var index =
        indexOf(
            entry("FooBar", "com.example", TypeKind.CLASS),
            entry("FooBaz", "com.example", TypeKind.CLASS));
    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve("Foo", index, List.of(src));
    assertThat(result).hasSize(2);
    assertThat(result)
        .extracting(SymbolInformation::getName)
        .containsExactlyInAnyOrder("FooBar", "FooBaz");
  }

  // --- resolve: location ---

  @Test
  void resolve_location_hasCorrectUriAndRange() throws IOException {
    createSourceFile("com.example", "Foo");
    final var index = indexOf(entry("Foo", "com.example", TypeKind.CLASS));
    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve("Foo", index, List.of(src));
    final var loc = result.getFirst().getLocation();
    assertThat(loc.getUri()).startsWith("file://");
    assertThat(loc.getUri()).endsWith("com/example/Foo.java");
    assertThat(loc.getRange().getStart().getLine()).isZero();
    assertThat(loc.getRange().getStart().getCharacter()).isZero();
    assertThat(loc.getRange().getEnd().getLine()).isZero();
    assertThat(loc.getRange().getEnd().getCharacter()).isZero();
  }

  // --- resolve: multiple source dirs ---

  @Test
  void resolve_typeInSecondSourceDir_found(@TempDir final Path src2) throws IOException {
    final Path dir = src2.resolve("com/example");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("Remote.java"), "");
    final var index = indexOf(entry("Remote", "com.example", TypeKind.CLASS));
    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve("Rem", index, List.of(src, src2));
    assertThat(result).hasSize(1);
    assertThat(result.getFirst().getName()).isEqualTo("Remote");
  }

  // --- resolveSourcePath: source path resolution ---

  @Test
  void resolveSourcePath_innerClass_resolvesToOuterFile() throws IOException {
    createSourceFile("com.example", "Outer");
    final var innerEntry =
        new TypeIndexEntry(
            "Inner", "com.example.Outer$Inner", "com.example", TypeKind.CLASS, true, List.of());
    final Path result = WorkspaceSymbolResolver.resolveSourcePath(innerEntry, List.of(src));
    assertThat(result).isNotNull();
    assertThat(result.getFileName().toString()).isEqualTo("Outer.java");
  }

  @Test
  void resolveSourcePath_noMatchingDir_returnsNull() {
    final var e = entry("Ghost", "com.missing", TypeKind.CLASS);
    assertThat(WorkspaceSymbolResolver.resolveSourcePath(e, List.of(src))).isNull();
  }

  @Test
  void resolveSourcePath_jdkModuleSubdir_resolvesInsideModuleDir(@TempDir final Path jdkRoot)
      throws IOException {
    // JDK sources are organized by module: <jdkRoot>/<module>/<package>/<Type>.java
    // WorkspaceManifest.jdkModuleSourceDirs() expands jdkRoot into its immediate module subdirs,
    // so resolveSourcePath receives each module dir (e.g. java.base/) as a separate source root
    final Path moduleDir = jdkRoot.resolve("java.base/java/util");
    Files.createDirectories(moduleDir);
    Files.writeString(moduleDir.resolve("ArrayList.java"), "");
    final var jdkEntry =
        new TypeIndexEntry(
            "ArrayList", "java.util.ArrayList", "java.util", TypeKind.CLASS, true, List.of());
    final Path result =
        WorkspaceSymbolResolver.resolveSourcePath(jdkEntry, List.of(jdkRoot.resolve("java.base")));
    assertThat(result).isNotNull();
    assertThat(result.getFileName().toString()).isEqualTo("ArrayList.java");
  }

  // --- SymbolKinds.fromTypeIndex ---

  @Test
  void fromTypeIndex_interfaceAndEnum_mapToMatchingKind() {
    assertThat(SymbolKinds.fromTypeIndex(TypeKind.INTERFACE)).isEqualTo(SymbolKind.Interface);
    assertThat(SymbolKinds.fromTypeIndex(TypeKind.ENUM)).isEqualTo(SymbolKind.Enum);
    assertThat(SymbolKinds.fromTypeIndex(TypeKind.RECORD)).isEqualTo(SymbolKind.Struct);
    assertThat(SymbolKinds.fromTypeIndex(TypeKind.ANNOTATION)).isEqualTo(SymbolKind.Interface);
  }

  @Test
  void fromTypeIndex_classAndUnknown_mapToClass() {
    assertThat(SymbolKinds.fromTypeIndex(TypeKind.CLASS)).isEqualTo(SymbolKind.Class);
    assertThat(SymbolKinds.fromTypeIndex(TypeKind.UNKNOWN)).isEqualTo(SymbolKind.Class);
  }
}
