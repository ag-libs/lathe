package io.github.aglibs.lathe.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.aglibs.lathe.analysis.testing.TempSourceCompiler;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceSymbolTest {

  @TempDir private Path src;

  private static TypeIndexEntry entry(final String simpleName, final String pkg) {
    final String qualName = pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
    return new TypeIndexEntry(simpleName, qualName, pkg, TypeKind.CLASS, true, List.of(), null);
  }

  private WorkspaceTypeIndex indexOf(final TypeIndexEntry... entries) {
    return WorkspaceTypeIndex.build(List.of(), TempSourceCompiler.reactorShards(List.of(entries)));
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
    final var index = indexOf(entry("MyClass", "com.example"));

    assertThat(WorkspaceSymbolResolver.resolve("", index, List.of(src), entry -> true)).isEmpty();
    assertThat(WorkspaceSymbolResolver.resolve("   ", index, List.of(src), entry -> true))
        .isEmpty();
  }

  // --- resolve: basic matching ---

  @Test
  void resolve_matchingClass_returnsSymbol() throws IOException {
    createSourceFile("com.example", "UserService");
    final var index = indexOf(entry("UserService", "com.example"));
    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve("User", index, List.of(src), entry -> true);
    assertThat(result).hasSize(1);
    assertThat(result.get(0).getName()).isEqualTo("UserService");
    assertThat(result.get(0).getKind()).isEqualTo(SymbolKind.Class);
    assertThat(result.get(0).getContainerName()).isEqualTo("com.example");
  }

  @Test
  void resolve_nonBlankQuery_keepsPrefixSearchBehavior() throws IOException {
    createSourceFile("com.example", "FooService");
    createSourceFile("com.example", "BarService");
    final var index =
        indexOf(entry("FooService", "com.example"), entry("BarService", "com.example"));

    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve("Foo", index, List.of(src), entry -> true);

    assertThat(result).extracting(SymbolInformation::getName).containsExactly("FooService");
  }

  // A narrowed search filters before the cap: 150 dependency matches must not hide the one kept.
  @Test
  void resolve_keepNarrowsBeforeTheCap_findsTheOneKeptTypePastManyOthers() throws IOException {
    createSourceFile("com.example", "Shop");
    final var index =
        indexOf(
            Stream.concat(
                    IntStream.range(0, 150).mapToObj(i -> entry("S%03d".formatted(i), "org.dep")),
                    Stream.of(entry("Shop", "com.example")))
                .toArray(TypeIndexEntry[]::new));

    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve(
            "S", index, List.of(src), entry -> entry.packageName().equals("com.example"));

    assertThat(result).extracting(SymbolInformation::getName).containsExactly("Shop");
  }

  @Test
  void resolve_camelCaseAbbreviation_findsReactorMatchMissedByPrefix() throws IOException {
    createSourceFile("com.example", "AbstractServerFactory");
    final var index = indexOf(entry("AbstractServerFactory", "com.example"));

    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve("ASF", index, List.of(src), entry -> true);

    assertThat(result)
        .extracting(SymbolInformation::getName)
        .containsExactly("AbstractServerFactory");
  }

  // Two letters prefix-match more dependency types than the list holds: the project's types found
  // by their initials are ranked with them, not added after the cap (spring-petclinic's BE listed
  // a hundred Bean… types and not BaseEntity). The ranking stands: a java.lang type still leads,
  // and
  // the project's types come before the other dependencies' alike.
  @Test
  void resolve_initialsQueryWithManyDependencyPrefixMatches_ranksTheProjectsTypesIn(
      @TempDir final Path shards) throws IOException {
    final List<TypeIndexEntry> dependencyTypes =
        Stream.concat(
                Stream.of(entry("Beacon", "java.lang")),
                IntStream.range(0, 120).mapToObj(i -> entry("Bean%03d".formatted(i), "org.dep")))
            .toList();
    for (final TypeIndexEntry type : dependencyTypes) {
      createSourceFile(type.packageName(), type.simpleName());
    }

    createSourceFile("com.example", "BaseEntity");
    createSourceFile("com.example", "Bench");
    final WorkspaceTypeIndex index =
        TempSourceCompiler.typeIndex(
            shards.resolve("dep.json"),
            dependencyTypes,
            List.of(entry("BaseEntity", "com.example"), entry("Bench", "com.example")));

    final List<String> found =
        WorkspaceSymbolResolver.resolve("BE", index, List.of(src), entry -> true).stream()
            .map(SymbolInformation::getName)
            .toList();
    assertThat(found).hasSize(100).startsWith("Beacon", "Bench", "BaseEntity");
    assertThat(found.subList(3, found.size())).allMatch(name -> name.startsWith("Bean"));

    assertThat(WorkspaceSymbolResolver.resolve("Bean00", index, List.of(src), entry -> true))
        .extracting(SymbolInformation::getName)
        .as("a query only dependency types match still lists them")
        .hasSize(10)
        .allMatch(name -> name.startsWith("Bean00"));
  }

  @Test
  void resolve_prefixAndCamelCaseOverlap_deduplicatesResult() throws IOException {
    createSourceFile("com.example", "TaskManager");
    final var index = indexOf(entry("TaskManager", "com.example"));

    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve("Task", index, List.of(src), entry -> true);

    assertThat(result).extracting(SymbolInformation::getName).containsExactly("TaskManager");
  }

  @Test
  void resolve_noMatchingFile_excludesResult() {
    final var index = indexOf(entry("Missing", "com.example"));
    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve("Miss", index, List.of(src), entry -> true);
    assertThat(result).isEmpty();
  }

  @Test
  void resolve_defaultPackage_resolvesRootFile() throws IOException {
    createSourceFile("", "Standalone");
    final var index = indexOf(entry("Standalone", ""));
    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve("Stand", index, List.of(src), entry -> true);
    assertThat(result).hasSize(1);
    assertThat(result.get(0).getName()).isEqualTo("Standalone");
  }

  @Test
  void resolve_multipleMatchingTypes_returnsAll() throws IOException {
    createSourceFile("com.example", "FooBar");
    createSourceFile("com.example", "FooBaz");
    final var index = indexOf(entry("FooBar", "com.example"), entry("FooBaz", "com.example"));
    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve("Foo", index, List.of(src), entry -> true);
    assertThat(result).hasSize(2);
    assertThat(result)
        .extracting(SymbolInformation::getName)
        .containsExactlyInAnyOrder("FooBar", "FooBaz");
  }

  // An import or a stack trace names a type by its qualified name, whole or a tail of it.
  @Test
  void resolve_qualifiedName_matchesOnlyTypesDeclaredInThatPackageOrType() throws IOException {
    createSourceFile("io.dropwizard.util", "Duration");
    createSourceFile("java.time", "Duration");
    createSourceFile("java.util", "Map");
    createSourceFile("com.example", "Entry");
    final var index =
        indexOf(
            entry("Duration", "io.dropwizard.util"),
            entry("Duration", "java.time"),
            new TypeIndexEntry(
                "Entry",
                "java.util.Map$Entry",
                "java.util",
                TypeKind.INTERFACE,
                true,
                List.of(),
                null),
            entry("Entry", "com.example"));

    assertThat(
            WorkspaceSymbolResolver.resolve(
                "io.dropwizard.util.Duration", index, List.of(src), entry -> true))
        .extracting(SymbolInformation::getContainerName)
        .containsExactly("io.dropwizard.util");
    assertThat(WorkspaceSymbolResolver.resolve("util.Dur", index, List.of(src), entry -> true))
        .extracting(SymbolInformation::getContainerName)
        .containsExactly("io.dropwizard.util");
    assertThat(WorkspaceSymbolResolver.resolve("Map.Entry", index, List.of(src), entry -> true))
        .extracting(SymbolInformation::getContainerName)
        .containsExactly("java.util.Map");
    assertThat(
            WorkspaceSymbolResolver.resolve(
                "org.other.Duration", index, List.of(src), entry -> true))
        .isEmpty();
    assertThat(WorkspaceSymbolResolver.resolve("Duration", index, List.of(src), entry -> true))
        .as("an unqualified query is unchanged")
        .hasSize(2);
  }

  // --- resolve: location ---

  @Test
  void resolve_location_hasCorrectUriAndRange() throws IOException {
    createSourceFile("com.example", "Foo");
    final var index = indexOf(entry("Foo", "com.example"));
    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve("Foo", index, List.of(src), entry -> true);
    final var loc = result.get(0).getLocation();
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
    final var index = indexOf(entry("Remote", "com.example"));
    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve("Rem", index, List.of(src, src2), entry -> true);
    assertThat(result).hasSize(1);
    assertThat(result.get(0).getName()).isEqualTo("Remote");
  }

  // --- reactor: package-private types ---

  @Test
  void workspaceSymbol_mainClass_andTestClass_sameWorkspace_bothVisible() throws IOException {
    createSourceFile("com.example", "Foo");
    createSourceFile("com.example", "FooTest");
    final var index =
        indexOf(
            entry("Foo", "com.example"),
            new TypeIndexEntry(
                "FooTest",
                "com.example.FooTest",
                "com.example",
                TypeKind.CLASS,
                false,
                List.of(),
                null));

    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve("Foo", index, List.of(src), entry -> true);

    assertThat(result)
        .extracting(SymbolInformation::getName)
        .containsExactlyInAnyOrder("Foo", "FooTest");
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

  // --- declaration position ---

  @Test
  void resolve_class_rangePointsToDeclaration() throws IOException {
    final Path dir = src.resolve("com/example");
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve("Service.java"), "package com.example;\n\npublic class Service {}\n");
    final var index = indexOf(entry("Service", "com.example"));
    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve("Service", index, List.of(src), entry -> true);
    assertThat(result).hasSize(1);
    final var range = result.get(0).getLocation().getRange();
    // "public class Service" — "Service" starts at character 13, line 2 (0-indexed)
    assertThat(range.getStart().getLine()).isEqualTo(2);
    assertThat(range.getStart().getCharacter()).isEqualTo(13);
  }

  @Test
  void resolve_nestedTypesSharingAName_rangesOnTheNamedOne() throws IOException {
    final Path dir = src.resolve("com/example");
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve("Api.java"),
        """
        package com.example;
        class Api {
          static class Request { static class Builder {} }
          static class Response { static class Builder {} }
        }
        """);
    final var index =
        indexOf(
            new TypeIndexEntry(
                "Builder",
                "com.example.Api$Request$Builder",
                "com.example",
                TypeKind.CLASS,
                true,
                List.of(),
                null),
            new TypeIndexEntry(
                "Builder",
                "com.example.Api$Response$Builder",
                "com.example",
                TypeKind.CLASS,
                true,
                List.of(),
                null));

    assertThat(WorkspaceSymbolResolver.resolve("Builder", index, List.of(src), entry -> true))
        .extracting(
            SymbolInformation::getContainerName,
            symbol -> symbol.getLocation().getRange().getStart().getLine())
        .containsExactlyInAnyOrder(
            tuple("com.example.Api.Request", 2), tuple("com.example.Api.Response", 3));
  }

  @Test
  void resolve_noSourceFile_returnsEmpty() {
    final var index = indexOf(entry("Ghost", "com.example"));
    // no source file → result is empty (not FILE_START fallback)
    final List<SymbolInformation> result =
        WorkspaceSymbolResolver.resolve("Ghost", index, List.of(src), entry -> true);
    assertThat(result).isEmpty();
  }
}
