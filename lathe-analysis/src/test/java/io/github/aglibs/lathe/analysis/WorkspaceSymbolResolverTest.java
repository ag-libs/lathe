package io.github.aglibs.lathe.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.analysis.testing.TempSourceCompiler;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceSymbolResolverTest {

  @TempDir private Path tmp;

  // A package-private (typeNameCandidate == false) top-level dependency type -- the shape a stack
  // frame into a library's internal impl class has, which regressed before searchSymbols() existed.
  private static final TypeIndexEntry INTERNAL =
      new TypeIndexEntry(
          "Widget",
          "com.example.impl.Widget",
          "com.example.impl",
          TypeKind.CLASS,
          false,
          List.of());

  private Path writeWidgetSource() throws IOException {
    final var srcRoot = tmp.resolve("src");
    final var file = srcRoot.resolve("com/example/impl/Widget.java");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "package com.example.impl;\n\nclass Widget {}\n");
    return srcRoot;
  }

  @Test
  void resolve_packagePrivateTypeWithSource_returnsSymbol() throws IOException {
    final var srcRoot = writeWidgetSource();
    final var index = TempSourceCompiler.typeIndex(tmp.resolve("shard.json"), INTERNAL);

    final List<SymbolInformation> results =
        WorkspaceSymbolResolver.resolve("Widget", index, List.of(srcRoot), entry -> true);

    assertThat(results).hasSize(1);
    final SymbolInformation info = results.get(0);
    assertThat(info.getName()).isEqualTo("Widget");
    assertThat(info.getContainerName()).isEqualTo("com.example.impl");
    assertThat(info.getLocation().getUri()).endsWith("com/example/impl/Widget.java");
  }

  @Test
  void resolve_typeWithoutSourceOnDisk_returnsEmpty() throws IOException {
    final var index = TempSourceCompiler.typeIndex(tmp.resolve("shard.json"), INTERNAL);

    // The type is reachable through the symbol index now, but with no source file on disk there is
    // nothing to link to, so it is dropped rather than returned without a location.
    assertThat(
            WorkspaceSymbolResolver.resolve(
                "Widget", index, List.of(tmp.resolve("absent")), entry -> true))
        .isEmpty();
  }

  // A dependency without sources still declares its types: the search names its dependency instead
  // of a file, where a dependency that only lacks this one file says nothing.
  @Test
  void sourceless_typeOfADependencyWithoutSources_namesItsDependency_otherDependencyNot()
      throws IOException {
    final Path shard = tmp.resolve("shard.json");
    TempSourceCompiler.typeIndex(shard, INTERNAL);
    final var sourceless = WorkspaceTypeIndex.build(List.of(shard), Map.of(), Set.of(shard));
    final var withSources = WorkspaceTypeIndex.build(List.of(shard));

    assertThat(WorkspaceSymbolResolver.sourceless("Widget", sourceless, entry -> true))
        .containsExactly(
            new SourcelessSymbol("Widget", SymbolKind.Class, "com.example.impl", "test:lib:1.0"));
    assertThat(WorkspaceSymbolResolver.sourceless("Widget", withSources, entry -> true)).isEmpty();
    assertThat(WorkspaceSymbolResolver.sourceless("Widget", sourceless, entry -> false))
        .as("narrowed out")
        .isEmpty();
  }

  // The search keeps its first hits only; the types with sources that match first must not push
  // a sourceless one out of them.
  @Test
  void sourceless_moreTypesWithSourcesMatchFirst_stillFindsTheSourcelessOne() throws IOException {
    final Path sourced = tmp.resolve("sourced.json");
    final Path sourceless = tmp.resolve("sourceless.json");
    TempSourceCompiler.typeIndex(
        sourced,
        IntStream.range(0, 150)
            .mapToObj(
                i ->
                    new TypeIndexEntry(
                        "WidgetA%03d".formatted(i),
                        "com.example.WidgetA%03d".formatted(i),
                        "com.example",
                        TypeKind.CLASS,
                        true,
                        List.of()))
            .toArray(TypeIndexEntry[]::new));
    // Ranked after them all: a longer name, as the ranking puts the shorter name first.
    TempSourceCompiler.typeIndex(
        sourceless,
        new TypeIndexEntry(
            "WidgetZFactoryImpl",
            "com.example.WidgetZFactoryImpl",
            "com.example",
            TypeKind.CLASS,
            true,
            List.of()));
    final var index =
        WorkspaceTypeIndex.build(List.of(sourced, sourceless), Map.of(), Set.of(sourceless));

    assertThat(WorkspaceSymbolResolver.sourceless("Widget", index, entry -> true))
        .extracting(SourcelessSymbol::name)
        .containsExactly("WidgetZFactoryImpl");
  }
}
