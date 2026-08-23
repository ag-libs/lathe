package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.SymbolInformation;
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
        WorkspaceSymbolResolver.resolve("Widget", index, List.of(srcRoot));

    assertThat(results).hasSize(1);
    final SymbolInformation info = results.getFirst();
    assertThat(info.getName()).isEqualTo("Widget");
    assertThat(info.getContainerName()).isEqualTo("com.example.impl");
    assertThat(info.getLocation().getUri()).endsWith("com/example/impl/Widget.java");
  }

  @Test
  void resolve_typeWithoutSourceOnDisk_returnsEmpty() throws IOException {
    final var index = TempSourceCompiler.typeIndex(tmp.resolve("shard.json"), INTERNAL);

    // The type is reachable through the symbol index now, but with no source file on disk there is
    // nothing to link to, so it is dropped rather than returned without a location.
    assertThat(WorkspaceSymbolResolver.resolve("Widget", index, List.of(tmp.resolve("absent"))))
        .isEmpty();
  }
}
